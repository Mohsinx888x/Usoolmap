package com.usool.map.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usool.map.BuildConfig
import com.usool.map.R
import com.usool.map.core.Availability
import com.usool.map.core.DrivingSide
import com.usool.map.imagery.SatelliteCatalog
import com.usool.map.map.OfflineRegionInfo
import com.usool.map.map.OfflineRegions
import com.usool.map.map.RoadDataState
import com.usool.map.voice.VoiceLanguages
import com.usool.map.voice.VoiceMode
import java.util.Locale

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(if (ok) "●" else "○", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun OfflineScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val regions by vm.regions.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val density = LocalContext.current.resources.displayMetrics.density
    var name by remember { mutableStateOf("") }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Offline maps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Section("Download a region") {
                val bounds = ui.visibleBounds
                Text(
                    if (bounds == null) "Open the Map tab and move to the area you want. Any place in the world can be downloaded."
                    else String.format(Locale.US, "The area currently shown on the Map tab will be saved: about %,.0f km².", bounds.areaKm2),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (bounds != null && bounds.areaKm2 > OfflineRegions.MAX_ROAD_AREA_KM2) {
                    Text(
                        String.format(Locale.US, "This area is larger than %,.0f km², so only the map will be saved. Zoom in to a city-sized area to also get offline routing.", OfflineRegions.MAX_ROAD_AREA_KM2),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Region name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.downloadVisibleRegion(name, density); name = "" }, enabled = bounds != null && online) { Text("Download this area") }
                if (!online) Text("Connect to the internet to download.", style = MaterialTheme.typography.bodySmall)
            }

            Section("Downloaded regions") {
                if (regions.isEmpty()) Text("Nothing downloaded yet.", style = MaterialTheme.typography.bodyMedium)
                regions.forEach { RegionRow(it, onDelete = { vm.deleteRegion(it.id) }) }
            }

            Section("What works without internet") {
                StatusRow("Satellite positioning", true, "Always. Uses the device's GNSS receiver only.")
                StatusRow("Professional voice guidance", true, "Uses the phone's built-in speech engine and voices.")
                StatusRow("Map display", regions.any { it.mapComplete }, "Inside downloaded regions, in 2D and 3D.")
                StatusRow("Route calculation", regions.any { it.roadData == RoadDataState.READY }, "Drive, motorcycle, walk and cycle inside regions that include road data.")
                StatusRow("Humorous voice", vm.container.voice.cache.clipCount > 0, "Only lines already saved on this phone (${vm.container.voice.cache.clipCount} saved).")
                StatusRow("Coordinate search", true, "Typing coordinates works anywhere.")
            }
            Section("What needs internet") {
                Text(
                    "Place and address search, live traffic and incidents, speed-camera and speed-limit lookups, public transport, truck routing, " +
                        "building 3D shapes for a new area, and creating new humorous voice lines. These catch up automatically when the connection returns.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RegionRow(info: OfflineRegionInfo, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.name, fontWeight = FontWeight.SemiBold)
                val map = when {
                    info.mapComplete -> String.format(Locale.US, "Map saved (%.0f MB)", info.sizeBytes / 1_048_576.0)
                    info.mapDownloading -> "Map downloading ${info.mapPercent}%"
                    else -> "Map incomplete ${info.mapPercent}%"
                }
                val roads = when (info.roadData) {
                    RoadDataState.READY -> "Offline routing ready"
                    RoadDataState.DOWNLOADING -> "Road data downloading"
                    RoadDataState.TOO_LARGE -> "No offline routing (area too large)"
                    RoadDataState.FAILED -> "Road data failed, will retry when online"
                    RoadDataState.NONE -> "No road data"
                }
                Text("$map · $roads", style = MaterialTheme.typography.bodySmall)
                info.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        if (info.mapDownloading) LinearProgressIndicator(progress = { info.mapPercent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val region = vm.container.region()
    val voice = vm.container.voice

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Section("Voice guidance") {
                VoiceMode.entries.forEach { mode ->
                    val enabled = mode != VoiceMode.HUMOROUS || voice.humourConfigured
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.setVoiceMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.voiceMode == mode, onClick = { vm.setVoiceMode(mode) }, enabled = enabled)
                        Text(mode.label)
                    }
                }
                Text(
                    if (voice.humourConfigured.also { ui.voiceVersion }) "Fun lines are written and spoken by Grok (xAI), then saved on the phone. Arabic lines use Gulf dialect with full vowel marks so they are pronounced correctly. If a line is not ready, the professional voice speaks instead."
                    else "The fun voice needs an xAI (Grok) API key from console.x.ai. Paste it below. The professional voice works without it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                var grokKey by remember { mutableStateOf(vm.container.settings.grokKey) }
                OutlinedTextField(value = grokKey, onValueChange = { grokKey = it }, label = { Text("xAI (Grok) API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveGrokKey(grokKey) }) { Text("Save key") }
                    var voiceOpen by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { voiceOpen = true }) { Text("Grok voice: ${ui.grokVoice}") }
                    DropdownMenu(expanded = voiceOpen, onDismissRequest = { voiceOpen = false }) {
                        com.usool.map.voice.GrokVoiceClient.VOICES.forEach { v -> DropdownMenuItem(text = { Text(v) }, onClick = { vm.setGrokVoice(v); voiceOpen = false }) }
                    }
                }
                var open by remember { mutableStateOf(false) }
                val current = VoiceLanguages.forTag(ui.voiceLanguage)
                OutlinedButton(onClick = { open = true }) { Text("Language: ${current.label}") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    VoiceLanguages.all.forEach { lang ->
                        DropdownMenuItem(text = { Text(lang.label) }, onClick = { vm.setVoiceLanguage(lang); open = false })
                    }
                }
                if (!voice.professionalVoiceAvailable) {
                    Text("This phone has no speech voice installed for ${current.label}; English is used until one is installed in Android's text-to-speech settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(String.format(Locale.US, "Saved humorous clips: %d (%.1f MB)", voice.cache.clipCount, voice.cache.sizeBytes / 1_048_576.0), style = MaterialTheme.typography.bodySmall)
            }

            Section("Search and translation") {
                var langOpen by remember { mutableStateOf(false) }
                val mine = VoiceLanguages.forTag(ui.searchLanguage)
                OutlinedButton(onClick = { langOpen = true }) { Text("My language: ${mine.label}") }
                DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                    VoiceLanguages.all.forEach { lang -> DropdownMenuItem(text = { Text(lang.label) }, onClick = { vm.setSearchLanguage(lang.tag); langOpen = false }) }
                }
                Text(
                    "You can type or speak your search in this language whatever language the app is in. Searching for a kind of place (for example \"مطعم حلال\", \"فندق\", \"hotel\") lists matching places inside the area on the map, " +
                        "with the name as written locally, its translation where one is known, and the address in your language.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = vm::downloadTranslation, enabled = !ui.translationBusy) { Text(if (ui.translationBusy) "Downloading…" else "Download offline translation for the area on the map") }
                Text(
                    "Translates local names that have no translation on the map (about 30 MB per language, from Google). Download it before travelling: Google's servers cannot be reached from mainland China.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Map appearance") {
                Text("Colours", fontWeight = FontWeight.Medium)
                com.usool.map.map.MapThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setMapTheme(mode) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.mapTheme == mode, onClick = { vm.setMapTheme(mode) })
                        Text(mode.label)
                    }
                }
                Text("Automatic switches to the dark map when the phone is in dark mode or after sunset where the map is looking.", style = MaterialTheme.typography.bodySmall)
                var labelsOpen by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { labelsOpen = true }) { Text("Place names: ${ui.mapLabels.label}") }
                DropdownMenu(expanded = labelsOpen, onDismissRequest = { labelsOpen = false }) {
                    com.usool.map.map.MapLabels.entries.forEach { l ->
                        DropdownMenuItem(text = { Text(l.label) }, onClick = { vm.setMapLabels(l); labelsOpen = false })
                    }
                }
                Text("Places with no name mapped in the chosen language keep their local name.", style = MaterialTheme.typography.bodySmall)
            }

            Section("Satellite and street imagery") {
                val source = vm.container.satellite.also { ui.imageryVersion }
                val sources = vm.container.satelliteSources
                var sourceOpen by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { sourceOpen = true }) { Text("Satellite source: ${source.name}") }
                DropdownMenu(expanded = sourceOpen, onDismissRequest = { sourceOpen = false }) {
                    DropdownMenuItem(text = { Text("Automatic (most detailed available)") }, onClick = { vm.setSatelliteSource(SatelliteCatalog.AUTO); sourceOpen = false })
                    sources.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.setSatelliteSource(s.id); sourceOpen = false }) }
                }
                Text(
                    "The free source shows about 10 m per pixel. Imagery sharp enough for roofs, cars and lane markings (around 30 to 50 cm) is commercial: " +
                        "paste a key from a provider below. Detail differs by provider and place, so if one is blurry where you are, try another. " +
                        "Esri World Imagery usually has the sharpest pictures of Gulf cities.",
                    style = MaterialTheme.typography.bodySmall,
                )
                var esri by remember { mutableStateOf(vm.container.settings.esriKey) }
                var mapbox by remember { mutableStateOf(vm.container.settings.mapboxToken) }
                var mapTiler by remember { mutableStateOf(vm.container.settings.mapTilerKey) }
                var mapillary by remember { mutableStateOf(vm.container.settings.mapillaryToken) }
                OutlinedTextField(value = esri, onValueChange = { esri = it }, label = { Text("Esri ArcGIS API key (location.arcgis.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mapbox, onValueChange = { mapbox = it }, label = { Text("Mapbox access token (mapbox.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mapTiler, onValueChange = { mapTiler = it }, label = { Text("MapTiler API key (maptiler.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mapillary, onValueChange = { mapillary = it }, label = { Text("Mapillary token, for street photos") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.saveImageryKeys(esri, mapbox, mapTiler, mapillary) }) { Text("Save keys") }
                Text("Keys stay on this phone. Free plans have monthly limits. If a key is wrong or its limit is used up, the satellite layer stays blank until the key is fixed or another source is chosen.", style = MaterialTheme.typography.bodySmall)
            }

            Section("Region: $country") {
                Text(
                    "Distances in ${if (region.speedUnitLabel == "mph") "miles" else "kilometres"}, speeds in ${region.speedUnitLabel}, traffic drives on the ${if (region.drivingSide == DrivingSide.LEFT) "left" else "right"}. " +
                        "Detected from your mobile network, SIM or device language and updated from the places you search.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Section("Road information in $country") {
                vm.container.roadData.statusReport(country).also { online }.forEach { (service, availability) ->
                    val detail = when (availability) {
                        Availability.Available -> "Available from ${vm.container.roadData.providerFor(service, country)?.name ?: "provider"}"
                        is Availability.Unavailable -> availability.reason
                    }
                    StatusRow(service.label, availability.isAvailable, detail)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed-camera alerts", Modifier.weight(1f))
                    Switch(checked = ui.cameraAlerts && region.speedCameraAlertsAllowed, onCheckedChange = vm::setCameraAlerts, enabled = region.speedCameraAlertsAllowed)
                }
                Text("Camera positions and speed limits come from OpenStreetMap volunteers and can be missing or out of date. Always follow the signs on the road.", style = MaterialTheme.typography.bodySmall)
            }

            Section("About") {
                Text(stringResource(R.string.app_full_name), fontWeight = FontWeight.SemiBold)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Map data © OpenStreetMap contributors (ODbL). Map tiles by OpenFreeMap, OpenMapTiles schema. Rendering by MapLibre. " +
                        "Routing by Valhalla. Public transport by Transitous. Search by Nominatim.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
