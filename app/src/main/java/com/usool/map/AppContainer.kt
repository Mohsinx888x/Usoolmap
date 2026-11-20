package com.usool.map

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.usool.map.core.Http
import com.usool.map.core.RegionProfile
import com.usool.map.gnss.GnssMonitor
import com.usool.map.imagery.MapillaryProvider
import com.usool.map.imagery.PanoramaxProvider
import com.usool.map.imagery.SatelliteCatalog
import com.usool.map.imagery.StreetViewRepository
import com.usool.map.map.Buildings3D
import com.usool.map.map.OfflineRegions
import com.usool.map.nav.Navigator
import com.usool.map.routing.OfflineRoutingProvider
import com.usool.map.routing.RoutingRepository
import com.usool.map.routing.TransitRoutingProvider
import com.usool.map.routing.ValhallaRoutingProvider
import com.usool.map.search.NominatimProvider
import com.usool.map.search.SearchRepository
import com.usool.map.traffic.OsmRoadDataProvider
import com.usool.map.traffic.RoadDataRegistry
import com.usool.map.traffic.TomTomTrafficProvider
import com.usool.map.voice.AzureSpeechClient
import com.usool.map.voice.GrokJokeClient
import com.usool.map.voice.VoiceCache
import com.usool.map.voice.VoiceGuide
import com.usool.map.voice.VoiceLanguages
import com.usool.map.voice.VoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import java.io.File
import java.util.Locale

class UsoolMapApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // A larger tile cache keeps recently viewed map and satellite tiles on the phone, so
        // returning to an area shows it immediately instead of downloading it again.
        org.maplibre.android.offline.OfflineManager.getInstance(this).setMaximumAmbientCacheSize(512L * 1024 * 1024, null)
        container = AppContainer(this)
    }
}

/** Tracks whether the device currently has a validated internet connection. */
class ConnectivityMonitor(context: Context, private val onRestored: () -> Unit) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(current())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = set(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            override fun onLost(network: Network) = set(false)
        })
    }

    private fun set(value: Boolean) {
        val was = _online.value
        _online.value = value
        if (value && !was) onRestored()
    }

    private fun current(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** User preferences, persisted across launches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("usoolmap", Context.MODE_PRIVATE)

    var voiceMode: VoiceMode
        get() = runCatching { VoiceMode.valueOf(prefs.getString("voice_mode", null) ?: "") }.getOrDefault(VoiceMode.PROFESSIONAL)
        set(v) = prefs.edit().putString("voice_mode", v.name).apply()

    /** BCP-47 tag. Defaults to the device language so guidance matches the phone. */
    var voiceLanguage: String
        get() = prefs.getString("voice_language", null) ?: VoiceLanguages.forTag(Locale.getDefault().toLanguageTag()).tag
        set(v) = prefs.edit().putString("voice_language", v).apply()

    /** Country override for units and rules; null = detect automatically. */
    var countryOverride: String?
        get() = prefs.getString("country", null)
        set(v) = prefs.edit().putString("country", v).apply()

    var mapTheme: com.usool.map.map.MapThemeMode
        get() = runCatching { com.usool.map.map.MapThemeMode.valueOf(prefs.getString("map_theme", null) ?: "") }.getOrDefault(com.usool.map.map.MapThemeMode.AUTO)
        set(v) = prefs.edit().putString("map_theme", v.name).apply()

    var mapLabels: com.usool.map.map.MapLabels
        get() = runCatching { com.usool.map.map.MapLabels.valueOf(prefs.getString("map_labels", null) ?: "") }.getOrDefault(com.usool.map.map.MapLabels.PHONE)
        set(v) = prefs.edit().putString("map_labels", v.name).apply()

    /**
     * Imagery provider keys the user typed into Settings. They stay in the app's private storage
     * on this phone and are sent only to the provider they belong to.
     */
    var mapTilerKey: String
        get() = prefs.getString("maptiler_key", "") ?: ""
        set(v) = prefs.edit().putString("maptiler_key", v.trim()).apply()

    var mapillaryToken: String
        get() = prefs.getString("mapillary_token", "") ?: ""
        set(v) = prefs.edit().putString("mapillary_token", v.trim()).apply()

    var cameraAlerts: Boolean
        get() = prefs.getBoolean("camera_alerts", true)
        set(v) = prefs.edit().putBoolean("camera_alerts", v).apply()
}

/** Creates and holds the app's long-lived objects. One instance per process. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http = Http()
    val settings = Settings(app)

    val connectivity: ConnectivityMonitor = ConnectivityMonitor(app) { onConnectionRestored() }
    private val isOnline: () -> Boolean = { connectivity.online.value }

    val gnss = GnssMonitor(app)
    val offlineRegions = OfflineRegions(app, http, scope)
    val buildings = Buildings3D(http)
    val search = SearchRepository(NominatimProvider(http))

    /** Mapillary first (worldwide, needs a token), then Panoramax (open, no key). */
    val streetView = StreetViewRepository(
        listOf(MapillaryProvider(http) { settings.mapillaryToken.ifBlank { BuildConfig.MAPILLARY_TOKEN } }, PanoramaxProvider(http)),
    )

    /** Chosen each time it is read: a key saved in Settings wins over one compiled into the build. */
    val satellite get() = SatelliteCatalog.select(
        customUrl = BuildConfig.SATELLITE_TILES_URL,
        customName = BuildConfig.SATELLITE_NAME,
        customMaxZoom = BuildConfig.SATELLITE_MAX_ZOOM,
        customAttribution = BuildConfig.SATELLITE_ATTRIBUTION,
        mapTilerKey = settings.mapTilerKey.ifBlank { BuildConfig.MAPTILER_API_KEY },
    )

    private val themeJson: String by lazy { app.assets.open("map_themes.json").use { it.readBytes().toString(Charsets.UTF_8) } }
    val dayTheme: com.usool.map.map.MapTheme by lazy { com.usool.map.map.MapTheme.parse(themeJson, dark = false) }
    val nightTheme: com.usool.map.map.MapTheme by lazy { com.usool.map.map.MapTheme.parse(themeJson, dark = true) }

    /** Loads and caches street photos (memory and disk), so revisited photos open instantly. */
    val imageLoader: coil.ImageLoader by lazy { coil.ImageLoader.Builder(app).crossfade(false).build() }

    val routing = RoutingRepository(
        listOf(
            ValhallaRoutingProvider(http, isOnline),
            TransitRoutingProvider(http, isOnline),
            OfflineRoutingProvider { offlineRegions.graphs() },
        ),
    )

    /** Order matters only between providers with the same coverage; country-specific ones win. */
    val roadData = RoadDataRegistry(
        listOf(
            TomTomTrafficProvider(http, BuildConfig.TOMTOM_API_KEY),
            OsmRoadDataProvider(http),
        ),
        isOnline,
    )

    val voice = VoiceGuide(
        app, scope,
        GrokJokeClient(http, BuildConfig.GROK_API_KEY, BuildConfig.GROK_MODEL),
        AzureSpeechClient(http, BuildConfig.AZURE_SPEECH_KEY, BuildConfig.AZURE_SPEECH_REGION),
        VoiceCache(File(app.filesDir, "voice-cache")),
        isOnline,
    ).also {
        it.mode = settings.voiceMode
        it.language = VoiceLanguages.forTag(settings.voiceLanguage)
    }

    val navigator = Navigator(app, scope, gnss, routing, roadData, voice, ::region) { settings.cameraAlerts }

    private val _country = MutableStateFlow(detectCountry())
    /** Country used for units, driving side and provider selection. Updated from search results. */
    val country: StateFlow<String> = _country.asStateFlow()

    fun region(): RegionProfile = RegionProfile.forCountry(settings.countryOverride ?: _country.value)

    fun setDetectedCountry(code: String?) {
        if (!code.isNullOrBlank() && code.length == 2) _country.value = code.uppercase()
    }

    /**
     * Best guess without a network: the mobile network's country, then the SIM's, then the device
     * locale. Falls back to the default launch region when none is known.
     */
    private fun detectCountry(): String {
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase() ?: RegionProfile.DEFAULT_COUNTRY
    }

    /** Catch-up work when the internet comes back. */
    private fun onConnectionRestored() {
        // The callback arrives on a system thread, possibly while this object is still being
        // built, so the work is posted to the main thread.
        scope.launch(Dispatchers.Main) {
            voice.syncPending()
            offlineRegions.resumeIncomplete()
            navigator.refreshRoadInfo()
        }
    }
}
