package com.usool.map.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usool.map.R
import com.usool.map.gnss.Constellation
import com.usool.map.gnss.FixState
import com.usool.map.gnss.GnssSnapshot
import com.usool.map.gnss.SatelliteInfo
import com.usool.map.gnss.SignalQuality
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private fun Constellation.color(): Color = when (this) {
    Constellation.GPS -> Color(0xFF2F7DF6)
    Constellation.GLONASS -> Color(0xFFE53935)
    Constellation.BEIDOU -> Color(0xFFF0A53A)
    Constellation.GALILEO -> Color(0xFF2E9E5B)
    Constellation.NAVIC -> Color(0xFF8E44AD)
    Constellation.QZSS -> Color(0xFF00A3B4)
    Constellation.SBAS -> Color(0xFF7A8794)
    Constellation.UNKNOWN -> Color(0xFF9E9E9E)
}

/** UsoolMap Satellite Center: live view of what the device's GNSS receiver reports. */
@Composable
fun SatelliteCenterScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val snap by vm.gnss.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    val region = vm.container.region().also { country }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.satellite_center), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            StatusCard(snap)

            Panel("Position") {
                val fix = snap.fix
                Readout("Latitude", fix?.let { String.format(Locale.US, "%.6f°", it.point.lat) })
                Readout("Longitude", fix?.let { String.format(Locale.US, "%.6f°", it.point.lon) })
                Readout("Horizontal accuracy", fix?.accuracyM?.let { "± ${region.formatDistance(it.toDouble())}" })
                Readout("Altitude (WGS-84)", fix?.altitudeM?.let { a -> String.format(Locale.US, "%.0f m", a) + (fix.verticalAccuracyM?.let { String.format(Locale.US, " ± %.0f m", it) } ?: "") })
                Readout("Speed", fix?.speedMps?.let { String.format(Locale.US, "%.0f %s", region.speedFromMps(it.toDouble()), region.speedUnitLabel) })
                Readout("Heading", fix?.bearingDeg?.let { String.format(Locale.US, "%.0f° %s", it, compassPoint(it)) })
                Readout("Last update", snap.msSinceLastFix?.let { if (it < 1500) "Live" else "${it / 1000} s ago" })
            }

            Panel("Satellites") {
                if (snap.satellites.isEmpty()) {
                    Text(
                        if (snap.state == FixState.DISABLED) "Satellite status is not available while positioning is off."
                        else "No satellites reported yet. Satellite status needs a view of the sky; indoors it can stay empty.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Counter("${snap.visibleCount}", "in view")
                        Counter("${snap.usedCount}", "used in fix")
                        Counter(snap.meanCn0DbHz?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "avg dB-Hz")
                    }
                    SkyPlot(snap.satellites, Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp))
                    Text("Sky view: centre is straight up, the edge is the horizon, north at the top. Filled dots are used in the position fix.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (snap.satellites.isNotEmpty()) {
                Panel("Constellations") {
                    snap.byConstellation.forEach { (c, counts) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(c.color(), CircleShape))
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(c.label, fontWeight = FontWeight.SemiBold)
                                if (c.operator.isNotEmpty()) Text(c.operator, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${counts.second} used / ${counts.first} in view", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val missing = Constellation.entries.filter { it != Constellation.UNKNOWN && it !in snap.byConstellation.keys }
                    if (missing.isNotEmpty()) {
                        Text(
                            "Not reported right now: ${missing.joinToString { it.label }}. A system can be absent because the receiver does not support it, it is not visible from here (NavIC and QZSS are regional), or its signals are blocked.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Panel("Signal strength (C/N0, dB-Hz)") {
                    SignalBars(snap.satellites.sortedWith(compareBy({ it.constellation.ordinal }, { it.svid })))
                }
            }

            Text(
                "This screen shows what Android reports from the receiver${snap.hardwareModel?.let { " ($it)" } ?: ""}. " +
                    "Which satellite systems are tracked is fixed by the device's hardware and firmware; UsoolMap cannot switch systems on or off or select individual satellites. " +
                    "Positioning works without internet. A connection only speeds up the first fix.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusCard(snap: GnssSnapshot) {
    val (title, color) = when (snap.state) {
        FixState.FIXED -> "Position fixed" to Color(0xFF2E9E5B)
        FixState.SEARCHING -> "Searching for satellites" to Color(0xFFF0A53A)
        FixState.DEAD_RECKONING -> "Signal lost, estimating position" to Color(0xFFEF6C00)
        FixState.LOST -> "Satellite signal lost" to Color(0xFFC62828)
        FixState.DISABLED -> "Positioning unavailable" to Color(0xFF7A8794)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "GNSS receiver: ${if (snap.providerEnabled) "on" else "off"} · Signal quality: ${qualityLabel(snap.signalQuality)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                snap.warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

private fun qualityLabel(q: SignalQuality) = when (q) {
    SignalQuality.NONE -> "no signal"
    SignalQuality.POOR -> "poor"
    SignalQuality.FAIR -> "fair"
    SignalQuality.GOOD -> "good"
    SignalQuality.EXCELLENT -> "excellent"
}

private fun compassPoint(deg: Float): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Readout(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "Not available", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = if (value == null) MaterialTheme.colorScheme.outline else Color.Unspecified)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Counter(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SkyPlot(satellites: List<SatelliteInfo>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val radius = size.minDimension / 2 - 8.dp.toPx()
        val centre = Offset(size.width / 2, size.height / 2)
        // Rings at the horizon, 30° and 60° elevation.
        listOf(1f, 2f / 3f, 1f / 3f).forEach { drawCircle(grid, radius * it, centre, style = Stroke(1.dp.toPx())) }
        drawLine(grid, Offset(centre.x - radius, centre.y), Offset(centre.x + radius, centre.y), 1.dp.toPx())
        drawLine(grid, Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y + radius), 1.dp.toPx())
        for (s in satellites) {
            if (s.elevationDeg < 0f) continue
            val r = radius * (1f - s.elevationDeg.coerceIn(0f, 90f) / 90f)
            val a = Math.toRadians(s.azimuthDeg.toDouble())
            val p = Offset(centre.x + (r * sin(a)).toFloat(), centre.y - (r * cos(a)).toFloat())
            val dot = (4 + (s.cn0DbHz.coerceIn(0f, 50f) / 50f) * 5).dp.toPx()
            if (s.usedInFix) drawCircle(s.constellation.color(), dot, p)
            else drawCircle(s.constellation.color(), dot, p, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun SignalBars(satellites: List<SatelliteInfo>) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        satellites.take(48).forEach { s ->
            Box(Modifier.weight(1f).fillMaxHeight().background(track, RoundedCornerShape(2.dp)), contentAlignment = Alignment.BottomCenter) {
                val fraction = (s.cn0DbHz / 50f).coerceIn(0.02f, 1f)
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(fraction)
                        .background(s.constellation.color().copy(alpha = if (s.usedInFix) 1f else 0.4f), RoundedCornerShape(2.dp)),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("One bar per satellite · full height = 50 dB-Hz", style = MaterialTheme.typography.bodySmall)
        Text("Faded = not used", style = MaterialTheme.typography.bodySmall)
    }
    if (satellites.size > 48) Text("Showing 48 of ${satellites.size} satellites.", style = MaterialTheme.typography.bodySmall)
    Box(Modifier.width(0.dp))
}
