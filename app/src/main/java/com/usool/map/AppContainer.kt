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
import com.usool.map.voice.GrokJokeClient
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
class Settings(context: Context) : com.usool.map.voice.humour.CloudCredentials, com.usool.map.voice.humour.HumourPreferences {
    private val prefs = context.getSharedPreferences("usoolmap", Context.MODE_PRIVATE)

    var voiceMode: VoiceMode
        get() = when (val stored = prefs.getString("voice_mode", null)) {
            "HUMOROUS" -> VoiceMode.FUNNY // name used by earlier versions
            else -> runCatching { VoiceMode.valueOf(stored ?: "") }.getOrDefault(VoiceMode.PROFESSIONAL)
        }
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

    /** Language the user searches and reads results in (BCP-47), independent of the app's interface. */
    var searchLanguage: String
        get() = prefs.getString("search_language", null) ?: VoiceLanguages.forTag(Locale.getDefault().toLanguageTag()).tag
        set(v) = prefs.edit().putString("search_language", v).apply()

    // --- Cloud voice service ---
    /** Address of the UsoolMap voice backend on Azure; blank when none is set up. */
    var backendUrl: String
        get() = prefs.getString("backend_url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.VOICE_BACKEND_URL
        set(v) { prefs.edit().putString("backend_url", v.trim()).putString("device_token", "").apply() }
    var backendAppKey: String
        get() = prefs.getString("backend_app_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.VOICE_BACKEND_APP_KEY
        set(v) { prefs.edit().putString("backend_app_key", v.trim()).putString("device_token", "").apply() }
    override val baseUrl: String get() = backendUrl
    override val appKey: String get() = backendAppKey
    /** Random id created on first use; it identifies this installation to the backend, not the person. */
    override val installId: String
        get() = prefs.getString("install_id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }
    override var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(v) = prefs.edit().putString("device_token", v).apply()

    // --- Humour choices ---
    override val languageTag: String get() = voiceLanguage
    override var personality: String
        get() = prefs.getString("personality", "") ?: ""
        set(v) = prefs.edit().putString("personality", v).apply()
    override var intensity: Int
        get() = prefs.getInt("humour_intensity", 2)
        set(v) = prefs.edit().putInt("humour_intensity", v.coerceIn(1, 3)).apply()
    override var frequency: Int
        get() = prefs.getInt("humour_frequency", 2)
        set(v) = prefs.edit().putInt("humour_frequency", v.coerceIn(1, 3)).apply()
    override var pausedUntilWallMs: Long
        get() = prefs.getLong("jokes_paused_until", 0)
        set(v) = prefs.edit().putLong("jokes_paused_until", v).apply()
    override var offlineOnly: Boolean
        get() = prefs.getBoolean("humour_offline_only", false)
        set(v) = prefs.edit().putBoolean("humour_offline_only", v).apply()
    /** Days before a joke may be heard again. */
    var nonRepeatDays: Int
        get() = prefs.getInt("non_repeat_days", 90)
        set(v) = prefs.edit().putInt("non_repeat_days", v.coerceIn(1, 365)).apply()
    override val deviceGrokVoice: String get() = grokVoice
    override var deviceGenerationsToday: Int
        get() = prefs.getInt("device_generations", 0)
        set(v) = prefs.edit().putInt("device_generations", v).apply()
    override var deviceGenerationDay: String
        get() = prefs.getString("device_generation_day", "") ?: ""
        set(v) = prefs.edit().putString("device_generation_day", v).apply()

    /** xAI (Grok) API key for the humorous voice, kept on this phone. */
    var grokKey: String
        get() = prefs.getString("grok_key", "") ?: ""
        set(v) = prefs.edit().putString("grok_key", v.trim()).apply()

    var grokVoice: String
        get() = prefs.getString("grok_voice", null) ?: com.usool.map.voice.GrokVoiceClient.DEFAULT_VOICE
        set(v) = prefs.edit().putString("grok_voice", v).apply()

    var esriKey: String
        get() = prefs.getString("esri_key", "") ?: ""
        set(v) = prefs.edit().putString("esri_key", v.trim()).apply()

    var mapboxToken: String
        get() = prefs.getString("mapbox_token", "") ?: ""
        set(v) = prefs.edit().putString("mapbox_token", v.trim()).apply()

    /** Id of the chosen satellite source, or "auto" for the most detailed one available. */
    var satelliteChoice: String
        get() = prefs.getString("satellite_choice", SatelliteCatalog.AUTO) ?: SatelliteCatalog.AUTO
        set(v) = prefs.edit().putString("satellite_choice", v).apply()

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
    val translator = com.usool.map.search.MlKitTranslator()
    val search = SearchRepository(NominatimProvider(http), translator)

    /** Mapillary first (worldwide, needs a token), then Panoramax (open, no key). */
    val streetView = StreetViewRepository(
        listOf(MapillaryProvider(http) { settings.mapillaryToken.ifBlank { BuildConfig.MAPILLARY_TOKEN } }, PanoramaxProvider(http)),
    )

    /** Chosen each time it is read: a key saved in Settings wins over one compiled into the build. */
    val imageryKeys get() = com.usool.map.imagery.ImageryKeys(
        esri = settings.esriKey,
        mapbox = settings.mapboxToken,
        mapTiler = settings.mapTilerKey.ifBlank { BuildConfig.MAPTILER_API_KEY },
        customUrl = BuildConfig.SATELLITE_TILES_URL,
        customName = BuildConfig.SATELLITE_NAME,
        customMaxZoom = BuildConfig.SATELLITE_MAX_ZOOM,
        customAttribution = BuildConfig.SATELLITE_ATTRIBUTION,
    )
    val satelliteSources get() = SatelliteCatalog.available(imageryKeys)
    val satellite get() = SatelliteCatalog.select(imageryKeys, settings.satelliteChoice)

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

    val voice = VoiceGuide(app).also {
        it.mode = settings.voiceMode
        it.language = VoiceLanguages.forTag(settings.voiceLanguage)
    }

    private val xaiKey: () -> String = { settings.grokKey.ifBlank { BuildConfig.GROK_API_KEY } }

    /** Humorous voice: Azure backend when its address is set, otherwise generated on the phone with an xAI key. */
    val humour = com.usool.map.voice.humour.HumourEngine(
        scope, voice,
        com.usool.map.voice.humour.ClipLibrary(File(app.filesDir, "humour-clips"), nonRepeatDays = settings.nonRepeatDays),
        com.usool.map.voice.humour.CloudVoiceClient(http, settings),
        GrokJokeClient(http, xaiKey, BuildConfig.GROK_MODEL),
        com.usool.map.voice.GrokVoiceClient(http, xaiKey),
        settings, isOnline,
    )

    val navigator = Navigator(app, scope, gnss, routing, roadData, voice, humour, ::region) { settings.cameraAlerts }

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
            humour.syncHistory()
            offlineRegions.resumeIncomplete()
            navigator.refreshRoadInfo()
        }
    }
}
