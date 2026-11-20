package com.usool.map.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.usool.map.R
import com.usool.map.UsoolMapApp
import com.usool.map.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps satellite positioning and voice guidance running while the screen
 * is off or another app is in front. It exists only during active navigation: it starts with a
 * route and stops when the route ends, so the app never tracks location in the background
 * otherwise.
 */
class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW))
        val first = notification("Navigation is running")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        } catch (e: SecurityException) {
            // Location permission was revoked while starting: guidance continues only in the foreground.
            stopSelf()
            return START_NOT_STICKY
        }

        val container = (application as UsoolMapApp).container
        scope.launch {
            container.navigator.state.collect { state ->
                if (!state.active) { stopSelf(); return@collect }
                val p = state.progress
                val text = when {
                    p == null -> "Waiting for satellite position"
                    p.nextManeuver != null -> "${container.region().formatDistance(p.distanceToManeuverM)} · ${p.nextManeuver.instruction}"
                    else -> "Navigation is running"
                }
                manager.notify(NOTIFICATION_ID, notification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 1
    }
}
