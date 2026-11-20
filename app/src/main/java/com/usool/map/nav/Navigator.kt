package com.usool.map.nav

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.usool.map.core.BBox
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.core.RegionProfile
import com.usool.map.gnss.FixState
import com.usool.map.gnss.GnssMonitor
import com.usool.map.routing.InstructionText
import com.usool.map.routing.Route
import com.usool.map.routing.RoutingRepository
import com.usool.map.traffic.RoadDataRegistry
import com.usool.map.traffic.RoadIncident
import com.usool.map.traffic.RoadService
import com.usool.map.traffic.SpeedCamera
import com.usool.map.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NavigationState(
    val active: Boolean = false,
    val route: Route? = null,
    val destination: GeoPoint? = null,
    val progress: NavigationProgress? = null,
    val rerouting: Boolean = false,
    val incidents: List<RoadIncident> = emptyList(),
    val cameras: List<SpeedCamera> = emptyList(),
    val speedLimitKmh: Int? = null,
    val message: String? = null,
)

/**
 * Runs active navigation for the whole process: feeds satellite fixes into the current
 * [NavigationSession], speaks announcements, reroutes when the driver leaves the route and loads
 * road information from whichever providers cover the current country.
 */
class Navigator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gnss: GnssMonitor,
    private val routing: RoutingRepository,
    private val roadData: RoadDataRegistry,
    private val voice: VoiceGuide,
    private val region: () -> RegionProfile,
    private val cameraAlertsEnabled: () -> Boolean,
) {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var session: NavigationSession? = null
    private var job: Job? = null
    private var lastFixState: FixState? = null
    private val alertedCameras = HashSet<GeoPoint>()
    private var lastSpeedLimitAt: GeoPoint? = null

    fun start(route: Route, destination: GeoPoint) {
        stopInternal()
        val lang = voice.language.tag
        session = NavigationSession(route, region(), lang)
        _state.value = NavigationState(active = true, route = route, destination = destination)
        voice.start()
        voice.prepare(session!!.speakableInstructions())
        gnss.start(navigating = true)
        ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
        refreshRoadInfo()
        job = scope.launch {
            gnss.snapshot.collect { snap ->
                val text = InstructionText.forLanguage(voice.language.tag)
                // Tell the driver once when the satellite signal goes and once when it returns.
                val previous = lastFixState
                if (previous == FixState.FIXED && (snap.state == FixState.DEAD_RECKONING || snap.state == FixState.LOST)) voice.speak(text.signalLost)
                if ((previous == FixState.DEAD_RECKONING || previous == FixState.LOST) && snap.state == FixState.FIXED) voice.speak(text.signalBack)
                lastFixState = snap.state

                val fix = snap.fix ?: return@collect
                if (snap.state == FixState.LOST) return@collect
                val current = session ?: return@collect
                val update = current.update(fix)
                _state.update { it.copy(progress = update.progress) }
                update.announcements.forEach { voice.speak(it.text, it.jokeKey) }
                if (update.needsReroute) reroute(fix.point, destination, route)
                checkCameras(fix.point)
                maybeLoadSpeedLimit(fix.point)
                if (update.progress.arrived) {
                    _state.update { it.copy(message = "You have arrived.") }
                    stop()
                }
            }
        }
    }

    fun stop() {
        stopInternal()
        gnss.start(navigating = false)
        context.stopService(Intent(context, NavigationService::class.java))
        _state.update { NavigationState(message = it.message) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun stopInternal() {
        job?.cancel(); job = null
        session = null
        lastFixState = null
        alertedCameras.clear()
        lastSpeedLimitAt = null
    }

    private fun reroute(from: GeoPoint, destination: GeoPoint, old: Route) {
        if (_state.value.rerouting) return
        _state.update { it.copy(rerouting = true) }
        scope.launch {
            try {
                val lang = voice.language.tag
                val fresh = routing.route(from, destination, old.mode, lang)
                session = NavigationSession(fresh, region(), lang)
                voice.prepare(session!!.speakableInstructions())
                _state.update { it.copy(route = fresh, rerouting = false) }
            } catch (e: Exception) {
                // Keep guiding along the old route; the next off-route detection tries again.
                _state.update { it.copy(rerouting = false, message = "A new route could not be calculated: ${e.message}") }
            }
        }
    }

    /** Loads incidents and cameras around the route from the providers that cover this country. */
    fun refreshRoadInfo() {
        val route = _state.value.route ?: return
        val area = Geo.bounds(route.geometry)?.let { BBox(it.south - 0.01, it.west - 0.01, it.north + 0.01, it.east + 0.01) } ?: return
        val cc = region().countryCode
        scope.launch {
            val incidents = roadData.providerFor(RoadService.INCIDENTS, cc)?.let { runCatching { it.incidents(area, voice.language.tag) }.getOrNull() }
            val camerasAllowed = cameraAlertsEnabled() && roadData.availability(RoadService.SPEED_CAMERAS, cc).isAvailable
            val cameras = if (camerasAllowed && area.areaKm2 < 20_000) {
                roadData.providerFor(RoadService.SPEED_CAMERAS, cc)?.let { runCatching { it.speedCameras(area) }.getOrNull() }
                    // Keep only cameras close to the route itself.
                    ?.filter { cam -> route.geometry.any { Geo.distanceM(it, cam.point) < 60 } }
            } else null
            _state.update { it.copy(incidents = incidents ?: it.incidents, cameras = cameras ?: emptyList()) }
        }
    }

    private fun checkCameras(p: GeoPoint) {
        val cameras = _state.value.cameras
        if (cameras.isEmpty() || !region().speedCameraAlertsAllowed) return
        val near = cameras.firstOrNull { it.point !in alertedCameras && Geo.distanceM(p, it.point) < 500 } ?: return
        alertedCameras += near.point
        val text = InstructionText.forLanguage(voice.language.tag)
        voice.speak(text.speedCamera(region().formatDistance(Geo.distanceM(p, near.point))))
    }

    private fun maybeLoadSpeedLimit(p: GeoPoint) {
        val last = lastSpeedLimitAt
        if (last != null && Geo.distanceM(last, p) < 400) return
        lastSpeedLimitAt = p
        val provider = roadData.providerFor(RoadService.SPEED_LIMITS, region().countryCode) ?: run {
            _state.update { it.copy(speedLimitKmh = null) }
            return
        }
        scope.launch {
            val limit = runCatching { provider.speedLimitKmh(p) }.getOrNull()
            _state.update { it.copy(speedLimitKmh = limit) }
        }
    }
}
