package com.usool.map.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.request.ImageRequest
import com.usool.map.core.Geo
import com.usool.map.imagery.PanoMath
import com.usool.map.imagery.StreetHop
import com.usool.map.imagery.StreetImage
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private suspend fun load(loader: ImageLoader, context: android.content.Context, url: String, maxSide: Int): Bitmap? {
    val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(maxSide).build()
    return (loader.execute(request).drawable as? BitmapDrawable)?.bitmap
}

private fun compass(deg: Double): String = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((deg % 360 + 360 + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)]

/**
 * Street-level viewer. It covers the top of the screen and leaves the map visible underneath,
 * where a camera marker shows exactly where the photo was taken and which way the view faces.
 *
 *  - A 360° photo is turned in any direction with one finger and zoomed with two.
 *  - Arrows lead to neighbouring photos, so you travel along the street without leaving the viewer.
 *  - Tapping the map below moves the camera to that spot.
 *
 * Whether a place can be looked around in depends on the photo: only photos taken with a 360°
 * camera can be rotated all the way round. Ordinary photos can be zoomed and panned.
 */
@Composable
fun StreetViewScreen(image: StreetImage, vm: AppViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val loader = vm.container.imageLoader
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(image.id) { mutableStateOf(false) }
    var fullDetail by remember(image.id) { mutableStateOf(false) }
    // Direction of view in the world, kept when stepping to the next photo so you keep facing the same way.
    var worldBearing by remember { mutableDoubleStateOf(image.headingDeg ?: 0.0) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var fov by remember { mutableFloatStateOf(Math.toRadians(75.0).toFloat()) }

    BackHandler { vm.closeStreetView() }

    LaunchedEffect(image.id) {
        if (!image.isPanorama) worldBearing = image.headingDeg ?: worldBearing
        val preview = runCatching { load(loader, context, image.previewUrl, 1024) }.getOrNull()
        if (preview != null) bitmap = preview
        val full = runCatching { load(loader, context, image.fullUrl, PanoView.MAX_TEXTURE) }.getOrNull()
        if (full != null) { bitmap = full; fullDetail = true } else if (preview == null) failed = true
    }
    LaunchedEffect(ui.streetHops) {
        // Fetch the neighbours' previews ahead, so a step along the street shows a picture at once.
        ui.streetHops.forEach { loader.enqueue(ImageRequest.Builder(context).data(it.image.previewUrl).size(1024).build()) }
    }
    LaunchedEffect(worldBearing) { vm.setStreetBearing(worldBearing) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(STREET_VIEW_FRACTION).background(Color.Black)) {
            val bmp = bitmap
            when {
                bmp != null && image.isPanorama -> Panorama(
                    bmp, yaw = Math.toRadians(worldBearing - (image.headingDeg ?: 0.0)).toFloat(), pitch = pitch, fov = fov,
                    onDrag = { dYaw, dPitch ->
                        worldBearing = ((worldBearing + Math.toDegrees(dYaw.toDouble())) % 360 + 360) % 360
                        pitch = (pitch + dPitch).coerceIn(-1.45f, 1.45f)
                    },
                    onZoom = { z -> fov = (fov / z).coerceIn(Math.toRadians(25.0).toFloat(), Math.toRadians(100.0).toFloat()) },
                )
                bmp != null -> PhotoCanvas(bmp, key = image.id)
                failed -> Text("This photo could not be loaded.", Modifier.align(Alignment.Center), color = Color.White)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }

            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = vm::closeStreetView) { Icon(Icons.Filled.Close, contentDescription = "Close street view") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        (if (image.isPanorama) "360° photo · drag to look around" else "Photo (not 360°) · pinch to zoom") + " · facing ${compass(worldBearing)}",
                        color = Color.White, style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        (image.capturedOn?.let { "Taken $it" } ?: "Date not recorded") + if (fullDetail) "" else " · loading full detail…",
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HopArrows(ui.streetHops, worldBearing, onGo = { vm.goToStreetImage(it.image) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                val coordinates = String.format(Locale.US, "%.6f, %.6f", image.point.lat, image.point.lon)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(coordinates, color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text("Copy", Modifier.padding(start = 12.dp).clickable { clipboard.setText(AnnotatedString(coordinates)); vm.showMessage("Coordinates copied.") }, color = MaterialTheme.colorScheme.primaryContainer, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    ui.streetPicked?.let { picked ->
                        Text("${Geo.distanceM(picked, image.point).roundToInt()} m from your point", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Where this photo was taken, as recorded by its camera. © ${image.author ?: "contributor"} · ${image.provider} · ${image.licence}",
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        // The map shows through here. A tap on it moves the camera to that spot.
        Box(Modifier.fillMaxWidth().weight(1f - STREET_VIEW_FRACTION)) {
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shadowElevation = 2.dp) {
                Text(
                    if (ui.streetLoading) "Looking for a photo there…" else "Tap the map to move the camera",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Arrows to the neighbouring photos, placed around a ring according to where each one lies relative to the view. */
@Composable
private fun HopArrows(hops: List<StreetHop>, viewBearing: Double, onGo: (StreetHop) -> Unit, modifier: Modifier = Modifier) {
    if (hops.isEmpty()) return
    Box(modifier.size(width = 220.dp, height = 120.dp)) {
        for (hop in hops) {
            val relative = Geo.angleDiffDeg(viewBearing, hop.bearingDeg)
            val a = Math.toRadians(relative)
            // Flattened ring: ahead is at the top, behind at the bottom, like arrows lying on the road.
            val x = (84 * sin(a)).dp
            val y = (-38 * cos(a)).dp
            Surface(
                onClick = { onGo(hop) },
                modifier = Modifier.align(Alignment.Center).offset(x, y).size(52.dp),
                shape = CircleShape, color = Color.White.copy(alpha = 0.92f), shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Navigation, contentDescription = "Go ${hop.distanceM.roundToInt()} metres ${compass(hop.bearingDeg)}", tint = Color(0xFF111111), modifier = Modifier.size(28.dp).rotate(relative.toFloat()))
                }
            }
        }
    }
}

/** The 360° picture with one-finger turning and two-finger zoom. */
@Composable
private fun Panorama(bitmap: Bitmap, yaw: Float, pitch: Float, fov: Float, onDrag: (dYaw: Float, dPitch: Float) -> Unit, onZoom: (Float) -> Unit) {
    var view by remember { mutableStateOf<PanoView?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        AndroidView(factory = { ctx -> PanoView(ctx).also { view = it } }, modifier = Modifier.fillMaxSize())
        // Gestures are read here rather than in the GL view, so they work the same as elsewhere in the app.
        Box(
            Modifier.fillMaxSize().pointerInput(heightPx) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val perPixel = fov / heightPx
                    // The picture follows the finger: dragging right turns the view left.
                    onDrag(-pan.x * perPixel, pan.y * perPixel)
                    if (zoom != 1f) onZoom(zoom)
                }
            },
        )
    }
    LaunchedEffect(view, bitmap) { view?.setImage(bitmap) }
    LaunchedEffect(view, yaw, pitch, fov) {
        view?.let { it.yaw = yaw; it.pitch = pitch; it.fov = fov; it.requestRender() }
    }
}

/** An ordinary photo with pinch-zoom and drag. */
@Composable
private fun PhotoCanvas(bitmap: Bitmap, key: String) {
    val picture = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember(key) { mutableFloatStateOf(1f) }
    var panX by remember(key) { mutableFloatStateOf(0f) }
    var panY by remember(key) { mutableFloatStateOf(0f) }

    Canvas(
        Modifier.fillMaxSize().pointerInput(key) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                zoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                panX += pan.x
                panY += pan.y
            }
        },
    ) {
        val scale = minOf(size.width / picture.width, size.height / picture.height) * zoom
        val w = picture.width * scale
        val h = picture.height * scale
        val maxX = ((w - size.width) / 2).coerceAtLeast(0f)
        val maxY = ((h - size.height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        drawImage(
            picture,
            dstOffset = IntOffset(((size.width - w) / 2 + panX).roundToInt(), ((size.height - h) / 2 + panY).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}
