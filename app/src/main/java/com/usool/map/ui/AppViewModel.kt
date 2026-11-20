package com.usool.map.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usool.map.UsoolMapApp
import com.usool.map.core.Availability
import com.usool.map.core.BBox
import com.usool.map.core.GeoPoint
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
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as UsoolMapApp).container

    private val _ui = MutableStateFlow(
        MapUiState(
            voiceMode = container.settings.voiceMode,
            voiceLanguage = container.settings.voiceLanguage,
            cameraAlerts = container.settings.cameraAlerts,
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

    fun submitSearch(text: String = _ui.value.query) {
        if (text.isBlank()) return
        _ui.update { it.copy(query = text, searching = true, results = emptyList()) }
        viewModelScope.launch {
            try {
                val results = container.search.search(text, currentPoint() ?: _ui.value.visibleBounds?.let { GeoPoint((it.south + it.north) / 2, (it.west + it.east) / 2) }, container.voice.language.tag)
                _ui.update { it.copy(searching = false, results = results, message = if (results.isEmpty()) "No places found for \"$text\"." else null) }
            } catch (e: Exception) {
                _ui.update { it.copy(searching = false, message = e.message ?: "Search failed.") }
            }
        }
    }

    fun selectPlace(place: Place) {
        container.setDetectedCountry(place.countryCode)
        _ui.update { it.copy(destination = place, results = emptyList(), query = place.name, route = null) }
    }

    /** A long press on the map: use the point as the destination and name it when online. */
    fun selectPoint(point: GeoPoint) {
        val label = String.format(java.util.Locale.US, "%.5f, %.5f", point.lat, point.lon)
        _ui.update { it.copy(destination = Place(label, "Dropped pin", point), results = emptyList(), route = null) }
        viewModelScope.launch {
            val named = container.search.reverse(point, container.voice.language.tag) ?: return@launch
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

    fun onCameraIdle(bounds: BBox) = _ui.update { it.copy(visibleBounds = bounds) }

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
