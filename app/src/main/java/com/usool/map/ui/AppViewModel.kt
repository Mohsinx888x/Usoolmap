package com.usool.map.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usool.map.UsoolMapApp
import com.usool.map.core.Availability
import com.usool.map.core.BBox
import com.usool.map.core.GeoPoint
import com.usool.map.imagery.StreetImage
import com.usool.map.map.BuildingSummary
import com.usool.map.map.Buildings3D
import com.usool.map.routing.Route
import com.usool.map.routing.TravelMode
import com.usool.map.search.Place
import com.usool.map.voice.VoiceLanguage
import com.usool.map.voice.VoiceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    /** What the last search understood, e.g. "مطعم حلال (清真, 穆斯林, halal): 12 in the area". */
    val searchHeading: String? = null,
    val searchLanguage: String = "en-US",
    /** Language of place names where the map is looking, e.g. "zh" over China. */
    val areaLanguage: String = "en",
    val translationBusy: Boolean = false,
    val searching: Boolean = false,
    val destination: Place? = null,
    val mode: TravelMode = TravelMode.DRIVING,
    val modeAvailability: Map<TravelMode, Availability> = emptyMap(),
    val route: Route? = null,
    val routing: Boolean = false,
    val is3D: Boolean = false,
    val builtGeoJson: String? = null,
    val buildingSummary: BuildingSummary? = null,
    val buildingBusy: Boolean = false,
    val visibleBounds: BBox? = null,
    val message: String? = null,
    val voiceMode: VoiceMode = VoiceMode.PROFESSIONAL,
    val voiceLanguage: String = "en-US",
    val cameraAlerts: Boolean = true,
    val zoom: Double = 10.0,
    /** Bumped when imagery keys change, so the map rebuilds its imagery layers. */
    val imageryVersion: Int = 0,
    val mapTheme: com.usool.map.map.MapThemeMode = com.usool.map.map.MapThemeMode.AUTO,
    val mapLabels: com.usool.map.map.MapLabels = com.usool.map.map.MapLabels.PHONE,
    val satellite: Boolean = false,
    /** Street-view mode: coverage lines are shown and a tap on the map opens the nearest photo. */
    val streetMode: Boolean = false,
    val streetLoading: Boolean = false,
    /** Photos near the tapped point, nearest first; non-null while the viewer is open. */
    val streetImages: List<StreetImage>? = null,
    val streetIndex: Int = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as UsoolMapApp).container

    private val _ui = MutableStateFlow(
        MapUiState(
            voiceMode = container.settings.voiceMode,
            voiceLanguage = container.settings.voiceLanguage,
            cameraAlerts = container.settings.cameraAlerts,
            searchLanguage = container.settings.searchLanguage,
            mapTheme = container.settings.mapTheme,
            mapLabels = container.settings.mapLabels,
        ),
    )
    val ui: StateFlow<MapUiState> = _ui.asStateFlow()

    val gnss = container.gnss.snapshot
    val navigation = container.navigator.state
    val online = container.connectivity.online
    val regions = container.offlineRegions.regions
    val country = container.country

    init {
        container.offlineRegions.refresh()
        refreshModes()
        viewModelScope.launch { online.collect { refreshModes() } }
        viewModelScope.launch { regions.collect { refreshModes() } }
    }

    private fun currentPoint(): GeoPoint? = gnss.value.fix?.point

    /** Recomputes which transport modes can be routed with the data available right now. */
    fun refreshModes() {
        val from = currentPoint()
        val map = TravelMode.entries.associateWith { container.routing.availability(it, from) }
        _ui.update { state ->
            val mode = if (map[state.mode]?.isAvailable == true) state.mode else map.entries.firstOrNull { it.value.isAvailable }?.key ?: state.mode
            state.copy(modeAvailability = map, mode = mode)
        }
    }

    fun onQueryChange(text: String) = _ui.update { it.copy(query = text) }

    private var areaCountryKey: Pair<Long, Long>? = null
    private var areaCountry: String? = null

    /** Country under the middle of the map, looked up once per map region. */
    private suspend fun countryOfMap(centre: GeoPoint?): String {
        centre ?: return container.country.value
        val key = Math.round(centre.lat * 2) to Math.round(centre.lon * 2)
        if (key != areaCountryKey) {
            areaCountry = container.search.countryAt(centre)
            areaCountryKey = key
        }
        return areaCountry ?: container.country.value
    }

    fun submitSearch(text: String = _ui.value.query) {
        if (text.isBlank()) return
        val bounds = _ui.value.visibleBounds
        val concept = com.usool.map.search.PoiConcepts.match(text)
        if (concept != null && com.usool.map.core.CoordinateParser.parse(text) == null && (bounds == null || bounds.areaKm2 > com.usool.map.search.SearchRepository.MAX_AREA_KM2)) {
            _ui.update { it.copy(query = text, message = "Move the map to a city and zoom in, then search for \"$text\" again. Places are searched inside the area on screen.") }
            return
        }
        _ui.update { it.copy(query = text, searching = true, results = emptyList(), searchHeading = null) }
        viewModelScope.launch {
            try {
                val centre = bounds?.let { GeoPoint((it.south + it.north) / 2, (it.west + it.east) / 2) }
                val areaLanguage = com.usool.map.search.AreaLanguage.forCountry(countryOfMap(centre))
                val found = container.search.search(text, currentPoint() ?: centre, _ui.value.searchLanguage, bounds, areaLanguage)
                val empty = if (concept != null) "No ${concept.label("en").lowercase()} is mapped in this area. OpenStreetMap coverage here may be incomplete." else "No places found for \"$text\"."
                _ui.update { it.copy(searching = false, results = found.places, searchHeading = found.heading, areaLanguage = areaLanguage, message = if (found.places.isEmpty()) empty else null) }
            } catch (e: Exception) {
                _ui.update { it.copy(searching = false, message = e.message ?: "Search failed.") }
            }
        }
    }

    fun setSearchLanguage(tag: String) {
        container.settings.searchLanguage = tag
        _ui.update { it.copy(searchLanguage = tag) }
    }

    /**
     * Downloads on-device translation for the user's language and the language of the area on
     * the map, so that names found later can be translated without a connection.
     */
    fun downloadTranslation() {
        if (!online.value) { showMessage("Downloading translation needs an internet connection."); return }
        _ui.update { it.copy(translationBusy = true) }
        viewModelScope.launch {
            val mine = _ui.value.searchLanguage
            val bounds = _ui.value.visibleBounds
            val centre = bounds?.let { GeoPoint((it.south + it.north) / 2, (it.west + it.east) / 2) }
            val local = com.usool.map.search.AreaLanguage.forCountry(countryOfMap(centre))
            val text = try {
                container.translator.download(listOf(mine, local))
                "Translation is ready between ${mine.substringBefore('-')} and $local, and works offline."
            } catch (e: Exception) {
                "Translation could not be downloaded (${e.message}). It needs access to Google's servers."
            }
            _ui.update { it.copy(translationBusy = false, areaLanguage = local, message = text) }
        }
    }

    fun selectPlace(place: Place) {
        container.setDetectedCountry(place.countryCode)
        _ui.update { it.copy(destination = place, results = emptyList(), searchHeading = null, query = place.localName ?: place.name, route = null) }
    }

    /** A long press on the map: use the point as the destination and name it when online. */
    fun selectPoint(point: GeoPoint) {
        val label = String.format(java.util.Locale.US, "%.5f, %.5f", point.lat, point.lon)
        _ui.update { it.copy(destination = Place(label, "Dropped pin", point), results = emptyList(), route = null) }
        viewModelScope.launch {
            val named = container.search.reverse(point, _ui.value.searchLanguage) ?: return@launch
            container.setDetectedCountry(named.countryCode)
            _ui.update { if (it.destination?.point == point) it.copy(destination = named.copy(point = point)) else it }
        }
    }

    fun clearDestination() {
        _ui.update { it.copy(destination = null, route = null, results = emptyList(), query = "") }
    }

    fun setMode(mode: TravelMode) {
        _ui.update { it.copy(mode = mode, route = null) }
        if (_ui.value.destination != null) calculateRoute()
    }

    fun calculateRoute() {
        val dest = _ui.value.destination ?: return
        val from = currentPoint()
        if (from == null) {
            _ui.update { it.copy(message = "Waiting for a satellite position before a route can be calculated.") }
            return
        }
        _ui.update { it.copy(routing = true, route = null) }
        viewModelScope.launch {
            try {
                val route = container.routing.route(from, dest.point, _ui.value.mode, container.voice.language.tag)
                _ui.update { it.copy(routing = false, route = route) }
            } catch (e: Exception) {
                _ui.update { it.copy(routing = false, message = e.message ?: "No route found.") }
            }
        }
    }

    fun startNavigation() {
        val route = _ui.value.route ?: return
        val dest = _ui.value.destination ?: return
        container.navigator.start(route, dest.point)
    }

    fun stopNavigation() {
        container.navigator.stop()
        _ui.update { it.copy(route = null) }
    }

    fun set3D(enabled: Boolean) = _ui.update { it.copy(is3D = enabled) }

    fun onCameraIdle(bounds: BBox, zoom: Double) = _ui.update { it.copy(visibleBounds = bounds, zoom = zoom) }

    fun setMapTheme(mode: com.usool.map.map.MapThemeMode) {
        container.settings.mapTheme = mode
        _ui.update { it.copy(mapTheme = mode) }
    }

    fun setMapLabels(labels: com.usool.map.map.MapLabels) {
        container.settings.mapLabels = labels
        _ui.update { it.copy(mapLabels = labels) }
    }

    /** Saves the provider keys typed in Settings and reloads the map's imagery with them. */
    fun saveImageryKeys(esriKey: String, mapboxToken: String, mapTilerKey: String, mapillaryToken: String) {
        container.settings.esriKey = esriKey
        container.settings.mapboxToken = mapboxToken
        container.settings.mapTilerKey = mapTilerKey
        container.settings.mapillaryToken = mapillaryToken
        _ui.update { it.copy(imageryVersion = it.imageryVersion + 1) }
        showMessage("Saved. Satellite imagery: ${container.satellite.name}. Street photos: ${container.streetView.activeProviders.joinToString(" and ") { it.name }}.")
    }

    fun setSatelliteSource(id: String) {
        container.settings.satelliteChoice = id
        _ui.update { it.copy(imageryVersion = it.imageryVersion + 1) }
    }

    fun setSatellite(enabled: Boolean) = _ui.update { it.copy(satellite = enabled) }

    fun setStreetMode(enabled: Boolean) {
        _ui.update { it.copy(streetMode = enabled) }
        if (enabled) {
            val names = container.streetView.activeProviders.joinToString(" and ") { it.name }
            showMessage("Green lines show streets with photos ($names). Tap one to look around.")
        }
    }

    /** Opens the street-level photos nearest to [point]. Says so plainly when there are none. */
    fun openStreetView(point: GeoPoint) {
        if (!online.value) { showMessage("Street photos need an internet connection."); return }
        if (_ui.value.streetLoading) return
        _ui.update { it.copy(streetLoading = true) }
        viewModelScope.launch {
            try {
                val images = container.streetView.near(point)
                if (images.isEmpty()) {
                    _ui.update { it.copy(streetLoading = false, message = "No street-level photos have been taken here yet.") }
                } else {
                    _ui.update { it.copy(streetLoading = false, streetImages = images.take(40), streetIndex = 0) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(streetLoading = false, message = e.message ?: "Street photos could not be loaded.") }
            }
        }
    }

    fun stepStreetImage(delta: Int) = _ui.update { s ->
        val n = s.streetImages?.size ?: return@update s
        s.copy(streetIndex = (s.streetIndex + delta).coerceIn(0, n - 1))
    }

    fun closeStreetView() = _ui.update { it.copy(streetImages = null, streetIndex = 0) }

    fun buildMissing3D() {
        val bounds = _ui.value.visibleBounds ?: return
        if (bounds.areaKm2 > Buildings3D.MAX_AREA_KM2) {
            _ui.update { it.copy(message = "Zoom in closer to build 3D buildings for the area on screen.") }
            return
        }
        if (!online.value) {
            _ui.update { it.copy(message = "Building 3D shapes needs an internet connection to read building outlines.") }
            return
        }
        _ui.update { it.copy(buildingBusy = true) }
        viewModelScope.launch {
            try {
                val result = container.buildings.build(bounds)
                val s = result.summary
                val note = if (s.total == 0) "No building outlines are mapped here, so nothing was generated."
                else "${s.total} outlines: ${s.measured} with mapped height, ${s.estimated} estimated, ${s.footprintOnly} left flat (height unknown)."
                _ui.update { it.copy(buildingBusy = false, is3D = true, builtGeoJson = if (s.total == 0) null else result.geoJson, buildingSummary = if (s.total == 0) null else s, message = note) }
            } catch (e: Exception) {
                _ui.update { it.copy(buildingBusy = false, message = "Building data could not be loaded (${e.message}).") }
            }
        }
    }

    fun clearBuiltBuildings() = _ui.update { it.copy(builtGeoJson = null, buildingSummary = null) }

    fun downloadVisibleRegion(name: String, pixelRatio: Float) {
        val bounds = _ui.value.visibleBounds
        if (bounds == null) {
            _ui.update { it.copy(message = "Open the map and move to the area you want first.") }
            return
        }
        if (!online.value) {
            _ui.update { it.copy(message = "Downloading a region needs an internet connection.") }
            return
        }
        container.offlineRegions.download(name.ifBlank { "Region" }, bounds, pixelRatio)
    }

    fun deleteRegion(id: Long) = container.offlineRegions.delete(id)

    fun setVoiceMode(mode: VoiceMode) {
        container.settings.voiceMode = mode
        container.voice.mode = mode
        _ui.update { it.copy(voiceMode = mode) }
    }

    fun setVoiceLanguage(language: VoiceLanguage) {
        container.settings.voiceLanguage = language.tag
        container.voice.language = language
        _ui.update { it.copy(voiceLanguage = language.tag) }
    }

    fun setCameraAlerts(enabled: Boolean) {
        container.settings.cameraAlerts = enabled
        _ui.update { it.copy(cameraAlerts = enabled) }
        container.navigator.refreshRoadInfo()
    }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun clearMessage() = _ui.update { it.copy(message = null) }
}
