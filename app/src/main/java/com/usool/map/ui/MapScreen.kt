package com.usool.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Streetview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usool.map.BuildConfig
import com.usool.map.core.Availability
import com.usool.map.core.GeoPoint
import com.usool.map.core.RegionProfile
import com.usool.map.gnss.FixState
import com.usool.map.imagery.SatelliteCatalog
import com.usool.map.map.HeightQuality
import com.usool.map.map.MapConfig
import com.usool.map.map.MapController
import com.usool.map.map.MapLabels
import com.usool.map.map.MapThemeMode
import com.usool.map.map.SunClock
import com.usool.map.nav.NavigationState
import com.usool.map.routing.Route
import com.usool.map.routing.TravelMode
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MapScreen(vm: AppViewModel, visible: Boolean, contentPadding: PaddingValues) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val nav by vm.navigation.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    val region = remember(country) { vm.container.region() }

    var controller by remember { mutableStateOf<MapController?>(null) }
    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }

    // Night colours: forced in settings, or automatic with the phone's dark mode or after sunset.
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val sunDown by androidx.compose.runtime.produceState(false, ui.visibleBounds == null) {
        while (true) {
            val b = vm.ui.value.visibleBounds
            val lat = b?.let { (it.south + it.north) / 2 } ?: RegionProfile.DEFAULT_CENTER.lat
            val lon = b?.let { (it.west + it.east) / 2 } ?: RegionProfile.DEFAULT_CENTER.lon
            value = SunClock.isNight(System.currentTimeMillis(), lat, lon)
            kotlinx.coroutines.delay(60_000)
        }
    }
    val dark = when (ui.mapTheme) {
        MapThemeMode.DAY -> false
        MapThemeMode.NIGHT -> true
        MapThemeMode.AUTO -> systemDark || sunDown
    }
    val labelLanguage = when (ui.mapLabels) {
        MapLabels.PHONE -> java.util.Locale.getDefault().language.ifBlank { "en" }
        else -> ui.mapLabels.code
    }
    var centredOnce by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                // Opens on the default launch region; the camera is free to go anywhere on Earth.
                map.cameraPosition = CameraPosition.Builder().target(LatLng(RegionProfile.DEFAULT_CENTER.lat, RegionProfile.DEFAULT_CENTER.lon)).zoom(10.5).build()
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = true
                // Zoom in far enough for the sharpest imagery; tiles around the view are fetched ahead of time.
                map.setMaxZoomPreference(21.0)
                map.prefetchZoomDelta = 4
                fun report() {
                    val b = map.projection.visibleRegion.latLngBounds
                    vm.onCameraIdle(com.usool.map.core.BBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast), map.cameraPosition.zoom)
                }
                map.addOnCameraIdleListener { report() }
                map.addOnMapLongClickListener { p -> vm.selectPoint(GeoPoint(p.latitude, p.longitude)); true }
                map.addOnMapClickListener { p ->
                    // In street-view mode a tap opens the nearest photo.
                    if (vm.ui.value.streetMode && !vm.navigation.value.active) { vm.openStreetView(GeoPoint(p.latitude, p.longitude)); true } else false
                }
                mapRef = map
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // The style is (re)loaded whenever the look changes; everything drawn on top is then restored
    // by the effects below, which all depend on the new controller.
    LaunchedEffect(mapRef, dark, labelLanguage) {
        val map = mapRef ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) { style ->
            val c = MapController(
                map, style, BuildConfig.TERRAIN_TILES_URL, vm.container.satellite, vm.container.streetView.coverageLayers,
                if (dark) vm.container.nightTheme else vm.container.dayTheme, labelLanguage,
            )
            controller = c
            vm.onCameraIdle(c.visibleBounds(), c.zoom)
        }
    }

    val c = controller
    val shownRoute = nav.route ?: ui.route
    // Remembered so that a restyle does not move the camera again.
    var fittedRoute by remember { mutableStateOf<Route?>(null) }
    var shownDestination by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(c, shownRoute) {
        c ?: return@LaunchedEffect
        c.setRoute(shownRoute?.geometry)
        if (shownRoute != null && !nav.active && fittedRoute !== shownRoute) c.fit(shownRoute.geometry)
        fittedRoute = shownRoute
    }
    LaunchedEffect(c, ui.destination, nav.destination) { c?.setDestination(nav.destination ?: ui.destination?.point) }
    LaunchedEffect(c, ui.is3D) { c?.set3D(ui.is3D) }
    LaunchedEffect(c, ui.satellite) { c?.setSatellite(ui.satellite) }
    LaunchedEffect(c, ui.streetMode, nav.active) { c?.setStreetCoverage(ui.streetMode && !nav.active) }
    LaunchedEffect(c, ui.builtGeoJson) { c?.setBuiltBuildings(ui.builtGeoJson) }
    LaunchedEffect(c, nav.incidents, nav.cameras) { c?.setRoadInfo(nav.incidents, nav.cameras) }
    LaunchedEffect(c, ui.destination) {
        c ?: return@LaunchedEffect
        val p = ui.destination?.point
        if (p != null && p != shownDestination && !nav.active) c.moveTo(p)
        shownDestination = p
    }
    LaunchedEffect(c, gnss.fix, nav.progress) {
        c ?: return@LaunchedEffect
        val progress = nav.progress
        val point = progress?.matched ?: gnss.fix?.point
        c.setPosition(point, estimated = gnss.fix?.estimated == true || gnss.state == FixState.LOST)
        if (nav.active && progress != null) c.follow(progress.matched, progress.bearingDeg)
        else if (!centredOnce && point != null) { centredOnce = true; c.moveTo(point, 14.5) }
    }

    Box(Modifier.fillMaxSize().alpha(if (visible) 1f else 0f)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!visible) return@Box

        Column(Modifier.fillMaxSize().padding(contentPadding).padding(12.dp)) {
            if (nav.active) {
                ManeuverBanner(nav, region)
            } else {
                SearchBar(ui, vm)
                ModeChips(ui, vm)
            }
            if (ui.satellite) {
                val lat = ui.visibleBounds?.let { (it.south + it.north) / 2 } ?: 0.0
                InfoPill(SatelliteCatalog.describe(vm.container.satellite, lat, ui.zoom))
            }
            StatusBanners(online = online, gnssWarning = gnss.warning, navigating = nav.active, routeOffline = shownRoute?.offline == true)

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                if (ui.buildingSummary != null && ui.is3D) BuildingLegend(onClear = vm::clearBuiltBuildings)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(
                        onClick = { vm.setSatellite(!ui.satellite) },
                        containerColor = if (ui.satellite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    ) { Icon(Icons.Filled.Satellite, contentDescription = "Satellite view") }
                    if (!nav.active) {
                        SmallFloatingActionButton(
                            onClick = { vm.setStreetMode(!ui.streetMode) },
                            containerColor = if (ui.streetMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            if (ui.streetLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Icon(Icons.Filled.Streetview, contentDescription = "Street view")
                        }
                    }
                    SmallFloatingActionButton(onClick = { vm.set3D(!ui.is3D) }) { Text(if (ui.is3D) "2D" else "3D", fontWeight = FontWeight.Bold) }
                    if (!nav.active) {
                        SmallFloatingActionButton(onClick = vm::buildMissing3D) {
                            if (ui.buildingBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Apartment, contentDescription = "Build missing 3D buildings")
                        }
                    }
                    SmallFloatingActionButton(onClick = {
                        val p = gnss.fix?.point
                        if (p != null) c?.moveTo(p, 16.0) else vm.showMessage(gnss.warning ?: "Waiting for a satellite position.")
                    }) { Icon(Icons.Filled.MyLocation, contentDescription = "My position") }
                }
            }
            Spacer(Modifier.size(8.dp))

            when {
                nav.active -> NavigationPanel(nav, region, speedMps = gnss.fix?.speedMps, onStop = vm::stopNavigation)
                ui.destination != null -> DestinationCard(ui, region, vm)
            }
        }
    }
}

@Composable
private fun SearchBar(ui: MapUiState, vm: AppViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp) {
        Column {
            TextField(
                value = ui.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search any place, address or coordinates") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        ui.searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        ui.query.isNotEmpty() || ui.destination != null -> IconButton(onClick = vm::clearDestination) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.submitSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (ui.results.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(ui.results) { place ->
                        Column(Modifier.fillMaxWidth().clickable { keyboard?.hide(); vm.selectPlace(place) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(place.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Only modes that can be routed with the data available now are offered. */
@Composable
private fun ModeChips(ui: MapUiState, vm: AppViewModel) {
    val available = TravelMode.entries.filter { ui.modeAvailability[it]?.isAvailable == true }
    if (available.isEmpty()) {
        val reason = (ui.modeAvailability[TravelMode.DRIVING] as? Availability.Unavailable)?.reason ?: "No routing data is available."
        InfoPill("Routing unavailable: $reason")
        return
    }
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        available.forEach { mode ->
            FilterChip(selected = ui.mode == mode, onClick = { vm.setMode(mode) }, label = { Text(mode.label) })
        }
    }
}

@Composable
private fun StatusBanners(online: Boolean, gnssWarning: String?, navigating: Boolean, routeOffline: Boolean) {
    if (gnssWarning != null) InfoPill(gnssWarning, warning = true)
    if (!online) {
        InfoPill(
            if (navigating && routeOffline) "Offline: guiding with downloaded data. Live traffic is unavailable."
            else "Offline: search, live traffic and AI voice need a connection. Positioning and downloaded regions still work.",
        )
    }
}

@Composable
private fun InfoPill(text: String, warning: Boolean = false) {
    Surface(
        modifier = Modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BuildingLegend(onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) {
        Column(Modifier.padding(10.dp).width(210.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("3D buildings", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            LegendRow(MapConfig.COLOR_MEASURED, HeightQuality.MEASURED.label)
            LegendRow(MapConfig.COLOR_FROM_LEVELS, HeightQuality.FROM_LEVELS.label)
            LegendRow(MapConfig.COLOR_FROM_TYPE, HeightQuality.FROM_TYPE.label)
            LegendRow(MapConfig.COLOR_FOOTPRINT, HeightQuality.FOOTPRINT_ONLY.label)
            Text("Estimated shapes are approximate.", style = MaterialTheme.typography.labelSmall)
            Text("Clear", Modifier.clickable(onClick = onClear).padding(top = 2.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LegendRow(hex: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape))
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DestinationCard(ui: MapUiState, region: RegionProfile, vm: AppViewModel) {
    val dest = ui.destination ?: return
    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(dest.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dest.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Street view here", Modifier.clickable { vm.openStreetView(dest.point) }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            val route = ui.route
            if (route != null) {
                RouteSummary(route, region)
                Button(onClick = vm::startNavigation, modifier = Modifier.fillMaxWidth()) { Text("Start navigation") }
            } else {
                val canRoute = ui.modeAvailability[ui.mode]?.isAvailable == true
                Button(onClick = vm::calculateRoute, enabled = !ui.routing && canRoute, modifier = Modifier.fillMaxWidth()) {
                    if (ui.routing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("${ui.mode.label} route")
                }
            }
        }
    }
}

@Composable
private fun RouteSummary(route: Route, region: RegionProfile) {
    Text("${formatDuration(route.durationS)} · ${region.formatDistance(route.distanceM)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    val tags = buildList {
        add(if (route.offline) "Offline route" else "Online route")
        add(route.source)
        if (route.hasToll) add("Tolls")
        if (route.hasFerry) add("Ferry")
    }
    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    route.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ManeuverBanner(nav: NavigationState, region: RegionProfile) {
    val p = nav.progress
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when {
                nav.rerouting -> Text("Recalculating…", style = MaterialTheme.typography.titleLarge)
                p == null -> Text("Waiting for satellite position…", style = MaterialTheme.typography.titleLarge)
                p.offRoute -> Text("Off route", style = MaterialTheme.typography.titleLarge)
                p.nextManeuver != null -> {
                    Text(region.formatDistance(p.distanceToManeuverM), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(p.nextManeuver.instruction, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun NavigationPanel(nav: NavigationState, region: RegionProfile, speedMps: Float?, onStop: () -> Unit) {
    val p = nav.progress
    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (p != null) "${formatDuration(p.remainingS)} · ${region.formatDistance(p.remainingM)}" else "—",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
                val speed = speedMps?.let { "${region.speedFromMps(it.toDouble()).toInt()} ${region.speedUnitLabel}" } ?: "Speed unavailable"
                val limit = nav.speedLimitKmh?.let { " · limit ${region.speedFromKmh(it.toDouble()).toInt()} ${region.speedUnitLabel} (OpenStreetMap)" } ?: ""
                Text(speed + limit, style = MaterialTheme.typography.bodyMedium)
                if (p?.positionEstimated == true) Text("Position estimated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
    }
}

fun formatDuration(seconds: Double): String {
    val minutes = Math.round(seconds / 60.0).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
