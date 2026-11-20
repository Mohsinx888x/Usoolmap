package com.usool.map.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.request.ImageRequest
import com.usool.map.core.Geo
import com.usool.map.imagery.StreetImage
import kotlin.math.roundToInt

private suspend fun load(loader: ImageLoader, context: android.content.Context, url: String, maxSide: Int): Bitmap? {
    val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(maxSide).build()
    return (loader.execute(request).drawable as? BitmapDrawable)?.bitmap
}

/**
 * Full-screen street-level photo viewer. The small preview appears first and is replaced by the
 * full-detail picture when it arrives; the next photo is fetched in the background so stepping
 * along the street feels immediate. Pinch to zoom; drag to pan (360° photos wrap around).
 */
@Composable
fun StreetViewScreen(images: List<StreetImage>, index: Int, vm: AppViewModel, modifier: Modifier = Modifier) {
    val image = images[index.coerceIn(0, images.size - 1)]
    val context = LocalContext.current
    val loader = vm.container.imageLoader
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(image.id) { mutableStateOf(false) }
    var fullDetail by remember(image.id) { mutableStateOf(false) }

    BackHandler { vm.closeStreetView() }

    LaunchedEffect(image.id) {
        val preview = runCatching { load(loader, context, image.previewUrl, 1024) }.getOrNull()
        if (preview != null) bitmap = preview
        val full = runCatching { load(loader, context, image.fullUrl, if (image.isPanorama) 6000 else 4096) }.getOrNull()
        if (full != null) { bitmap = full; fullDetail = true } else if (preview == null) failed = true
        // Warm the cache for the neighbour the user is most likely to open next.
        images.getOrNull(index + 1)?.let { next -> loader.enqueue(ImageRequest.Builder(context).data(next.previewUrl).size(1024).build()) }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        val bmp = bitmap
        when {
            bmp != null -> PhotoCanvas(bmp, image.isPanorama, key = image.id)
            failed -> Text("This photo could not be loaded.", Modifier.align(Alignment.Center), color = Color.White)
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = vm::closeStreetView) { Icon(Icons.Filled.Close, contentDescription = "Close street view") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                val facing = image.headingDeg?.let { " · facing ${compass(it)}" } ?: ""
                Text((image.capturedOn?.let { "Taken $it" } ?: "Date not recorded") + facing, color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(
                    (if (image.isPanorama) "360° photo, drag to look around" else "Photo") + if (fullDetail) " · full detail" else " · loading full detail…",
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { vm.stepStreetImage(-1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Closer photo") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val away = Geo.distanceM(images.first().point, image.point).roundToInt()
                    Text("Photo ${index + 1} of ${images.size}" + if (index > 0) " · $away m from the first" else " · nearest to where you tapped", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Text("© ${image.author ?: "contributor"} · ${image.provider} · ${image.licence}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalIconButton(onClick = { vm.stepStreetImage(1) }, enabled = index < images.size - 1) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next photo") }
            }
        }
    }
}

private fun compass(deg: Double): String = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((deg + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)]

/**
 * Draws the photo with pinch-zoom and drag. A panorama fills the height and scrolls sideways
 * without end; it is shown as a flat strip, so straight lines near the top and bottom look curved.
 */
@Composable
private fun PhotoCanvas(bitmap: Bitmap, panorama: Boolean, key: String) {
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
        val base = if (panorama) size.height / picture.height else minOf(size.width / picture.width, size.height / picture.height)
        val scale = base * zoom * (if (panorama) 1.4f else 1f)
        val w = picture.width * scale
        val h = picture.height * scale
        // Vertical movement stops at the picture's edges.
        val maxY = ((h - size.height) / 2).coerceAtLeast(0f)
        panY = panY.coerceIn(-maxY, maxY)
        val top = (size.height - h) / 2 + panY
        val dst = IntSize(w.roundToInt(), h.roundToInt())
        if (panorama) {
            // Repeat the strip so dragging past either end continues around the circle.
            var left = ((size.width - w) / 2 + panX) % w
            if (left > 0) left -= w
            while (left < size.width) {
                drawImage(picture, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = dst, filterQuality = FilterQuality.High)
                left += w
            }
        } else {
            val maxX = ((w - size.width) / 2).coerceAtLeast(0f)
            panX = panX.coerceIn(-maxX, maxX)
            drawImage(picture, dstOffset = IntOffset(((size.width - w) / 2 + panX).roundToInt(), top.roundToInt()), dstSize = dst, filterQuality = FilterQuality.High)
        }
    }
}
