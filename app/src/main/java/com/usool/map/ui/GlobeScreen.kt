package com.usool.map.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.GeoPoint
import com.usool.map.core.RegionProfile
import com.usool.map.imagery.SatelliteSource

/** Receives status messages from the globe page. Only plain strings cross this boundary. */
private class GlobeHost(private val onReport: (String, String) -> Unit) {
    @JavascriptInterface
    fun report(name: String, value: String) = onReport(name, value)
}

/** Settings handed to the globe page when it starts. */
fun globeConfig(source: SatelliteSource, centre: GeoPoint, heightM: Double, ionToken: String): String = JsonObject().apply {
    addProperty("imageryUrl", source.tileUrl)
    addProperty("imageryMaxZoom", source.maxZoom)
    addProperty("imageryName", source.name)
    addProperty("imageryCredit", source.attribution)
    addProperty("lat", centre.lat)
    addProperty("lon", centre.lon)
    addProperty("height", heightM)
    if (ionToken.isNotBlank()) addProperty("ionToken", ionToken.trim())
}.toString()

/** Camera height above the ground that shows roughly the same area as the map at [zoom]. */
fun globeHeightForZoom(zoom: Double): Double = (40_000_000.0 / Math.pow(2.0, zoom)).coerceIn(800.0, 15_000_000.0)

/**
 * 3D globe for exploring: a separate view drawn by CesiumJS inside a WebView. Navigation stays
 * on the native MapLibre map, which is faster and lighter; this view is opened on demand and
 * closed again, and shows its own frame rate so its cost on this phone is visible.
 *
 * The CesiumJS library is bundled in the app, so the page itself loads with no connection.
 * Imagery, terrain and 3D buildings are streamed and do need one.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlobeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val nav by vm.navigation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf<Int?>(null) }
    var layers by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    BackHandler { vm.setGlobeOpen(false) }

    val centre = remember { ui.visibleBounds?.let { GeoPoint((it.south + it.north) / 2, (it.west + it.east) / 2) } ?: gnss.fix?.point ?: RegionProfile.DEFAULT_CENTER }
    val config = remember { globeConfig(vm.container.satellite, centre, globeHeightForZoom(ui.zoom), vm.container.settings.cesiumIonToken) }

    val webView = remember {
        // Bundled files are served from a private https origin so that Cesium's web workers and
        // module loading work, which they do not from file:// addresses.
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(android.graphics.Color.BLACK)
            addJavascriptInterface(GlobeHost { name, value ->
                post {
                    when (name) {
                        "loaded" -> evaluateJavascript("window.usool.init($config)", null)
                        "ready" -> {
                            ready = true
                            runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull()?.let { o ->
                                val terrain = o.get("terrain")?.asString ?: "none"
                                val buildings = o.get("buildings")?.asString ?: "none"
                                layers = listOf(
                                    o.get("imagery")?.asString.orEmpty(),
                                    if (terrain == "none") "flat terrain" else terrain,
                                    if (buildings == "none") "no 3D buildings" else buildings,
                                ).filter { it.isNotBlank() }.joinToString(" · ")
                            }
                        }
                        "fps" -> fps = value.toIntOrNull()
                        "warning", "error" -> problem = value
                    }
                }
            }, "UsoolHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                // The globe never navigates anywhere: links in credits open nothing inside the app.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            loadUrl("https://appassets.androidplatform.net/assets/globe/index.html")
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    // Keep the route and the position in step with the map.
    val route = nav.route ?: ui.route
    LaunchedEffect(ready, route) {
        if (!ready) return@LaunchedEffect
        val coords = JsonArray()
        // A long route is thinned: the globe needs its shape, not every vertex.
        val points = route?.geometry.orEmpty()
        val step = (points.size / 800).coerceAtLeast(1)
        points.filterIndexed { i, _ -> i % step == 0 || i == points.size - 1 }.forEach { p -> coords.add(JsonArray().apply { add(p.lon); add(p.lat) }) }
        webView.evaluateJavascript("window.usool.setRoute($coords)", null)
    }
    LaunchedEffect(ready, gnss.fix?.point) {
        val p = gnss.fix?.point ?: return@LaunchedEffect
        if (ready) webView.evaluateJavascript("window.usool.setPosition(${p.lat}, ${p.lon})", null)
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (!ready && problem == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            FilledTonalIconButton(onClick = { vm.setGlobeOpen(false) }) { Icon(Icons.Filled.Close, contentDescription = "Close the globe") }
            Surface(Modifier.weight(1f).padding(horizontal = 8.dp), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("3D globe" + (fps?.let { " · $it frames per second" } ?: ""), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    if (layers.isNotEmpty()) Text(layers, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    problem?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
                    if (ready && vm.container.settings.cesiumIonToken.isBlank()) {
                        Text("Terrain and 3D buildings need a free Cesium ion token (Settings).", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { if (ready) webView.evaluateJavascript("window.usool.orbit()", null) }) { Icon(Icons.Filled.Autorenew, contentDescription = "Fly around this point") }
            FilledTonalIconButton(onClick = {
                val p = gnss.fix?.point
                if (p != null && ready) webView.evaluateJavascript("window.usool.flyTo(${p.lat}, ${p.lon}, 2500)", null) else vm.showMessage("Waiting for a satellite position.")
            }) { Icon(Icons.Filled.MyLocation, contentDescription = "Fly to my position") }
        }
    }
}
