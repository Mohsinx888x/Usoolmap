package com.usool.map.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.usool.map.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads position and satellite status straight from the device's GNSS receiver through
 * [LocationManager]. It uses the raw `gps` provider rather than a network-fused one, so
 * positioning keeps working with no internet connection. Assisted data (A-GNSS) is used
 * automatically by Android when a connection exists and only shortens the time to first fix.
 *
 * Whatever constellations the chipset tracks (GPS, GLONASS, BeiDou, Galileo, NavIC, QZSS, SBAS)
 * are combined by the receiver itself; this class reports them and never tries to control them.
 */
class GnssMonitor(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _snapshot = MutableStateFlow(GnssSnapshot())
    val snapshot: StateFlow<GnssSnapshot> = _snapshot.asStateFlow()

    private val detector = SignalLossDetector()
    private val deadReckoning = DeadReckoning()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var hasEverFixed = false
    private var lastFix: PositionFix? = null
    private var lastRestartMs = 0L
    private var intervalMs = INTERVAL_BROWSING_MS

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** [navigating] requests 1 Hz updates; otherwise a slower rate saves battery. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(navigating: Boolean) {
        val wanted = if (navigating) INTERVAL_NAVIGATION_MS else INTERVAL_BROWSING_MS
        if (running && wanted == intervalMs) return
        if (running) stop()
        if (!hasPermission()) {
            _snapshot.value = GnssSnapshot(state = FixState.DISABLED, providerEnabled = false, warning = "Location permission has not been granted.")
            return
        }
        if (!locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            _snapshot.value = GnssSnapshot(state = FixState.DISABLED, providerEnabled = false, satelliteStatusAvailable = false, warning = "This device has no satellite positioning hardware.")
            return
        }
        intervalMs = wanted
        val t = HandlerThread("usool-gnss").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        running = true
        register(h)
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, h)
        }
        h.postDelayed(watchdog, WATCHDOG_MS)
        publish()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        unregister()
        sensorManager.unregisterListener(sensorListener)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    @SuppressLint("MissingPermission")
    private fun register(h: Handler) {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, locationListener, h.looper)
            locationManager.registerGnssStatusCallback(statusCallback, h)
            // Seed with the last known fix so the map can centre immediately; it is replaced by a live one.
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                if (SystemClock.elapsedRealtime() - loc.elapsedRealtimeNanos / 1_000_000 < 60_000) onLocation(loc)
            }
        } catch (e: SecurityException) {
            _snapshot.value = GnssSnapshot(state = FixState.DISABLED, providerEnabled = false, warning = "Location permission has not been granted.")
        } catch (e: IllegalArgumentException) {
            _snapshot.value = GnssSnapshot(state = FixState.DISABLED, providerEnabled = false, warning = "Satellite positioning is not available on this device.")
        }
    }

    private fun unregister() {
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(statusCallback) }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) = publish()
        override fun onProviderDisabled(provider: String) = publish()
        @Deprecated("Required on API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    private fun onLocation(loc: Location) {
        val fix = PositionFix(
            point = GeoPoint(loc.latitude, loc.longitude),
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            verticalAccuracyM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            elapsedMs = loc.elapsedRealtimeNanos / 1_000_000,
        )
        hasEverFixed = true
        lastFix = fix
        deadReckoning.onFix(fix)
        publish()
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = ArrayList<SatelliteInfo>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                list += SatelliteInfo(
                    svid = status.getSvid(i),
                    constellation = Constellation.fromAndroid(status.getConstellationType(i)),
                    cn0DbHz = status.getCn0DbHz(i),
                    elevationDeg = status.getElevationDegrees(i),
                    azimuthDeg = status.getAzimuthDegrees(i),
                    usedInFix = status.usedInFix(i),
                    carrierFrequencyHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                )
            }
            satellites = list
            publish()
        }
    }

    @Volatile private var satellites: List<SatelliteInfo> = emptyList()

    private val sensorListener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            deadReckoning.sensorHeadingDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Runs once a second: flags signal loss, publishes a dead-reckoned position while the outage
     * is short, and re-registers the listeners if the receiver has been silent for a long time
     * (some chipsets stop delivering updates after a long tunnel until asked again).
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            publish()
            val now = SystemClock.elapsedRealtime()
            val silentFor = lastFix?.let { now - it.elapsedMs }
            if (hasEverFixed && silentFor != null && silentFor > RESTART_AFTER_MS && now - lastRestartMs > RESTART_AFTER_MS) {
                lastRestartMs = now
                unregister()
                handler?.let { register(it) }
            }
            handler?.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val enabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val since = lastFix?.let { now - it.elapsedMs }
        val state = detector.evaluate(hasEverFixed, since, deadReckoning.canEstimate(), enabled)
        val fix = when (state) {
            FixState.DEAD_RECKONING -> deadReckoning.estimate(now) ?: lastFix
            else -> lastFix
        }
        val warning = when (state) {
            FixState.DISABLED -> "Device location is switched off. Turn it on to navigate."
            FixState.DEAD_RECKONING -> "Satellite signal lost. Position is estimated from your last speed and heading."
            FixState.LOST -> "Satellite signal lost. Waiting for the receiver to recover."
            else -> null
        }
        _snapshot.value = GnssSnapshot(
            state = state,
            fix = fix,
            // Satellite lists go stale during an outage; keep them only while they are being refreshed.
            satellites = satellites,
            satelliteStatusAvailable = true,
            providerEnabled = enabled,
            hardwareModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.gnssHardwareModelName else null,
            lastUpdateElapsedMs = lastFix?.elapsedMs,
            msSinceLastFix = since,
            warning = warning,
        )
    }

    companion object {
        const val INTERVAL_NAVIGATION_MS = 1_000L
        const val INTERVAL_BROWSING_MS = 3_000L
        private const val WATCHDOG_MS = 1_000L
        private const val RESTART_AFTER_MS = 30_000L
    }
}
