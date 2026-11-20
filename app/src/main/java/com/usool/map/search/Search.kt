package com.usool.map.search

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.usool.map.core.BBox
import com.usool.map.core.CoordinateParser
import com.usool.map.core.Geo
import com.usool.map.core.GeoPoint
import com.usool.map.core.Http
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

data class Place(
    /** Name in the user's language when one is mapped, otherwise the local name. */
    val name: String,
    /** Address, written in the user's language where the map data has it. */
    val description: String,
    val point: GeoPoint,
    val category: String? = null,
    val countryCode: String? = null,
    /** Name as written locally (what the sign says), when different from [name]. */
    val localName: String? = null,
    /** Translation of the local name into the user's language, when one is known. */
    val translation: String? = null,
    /** Kind of place in the user's language, e.g. "مطعم حلال". */
    val categoryLabel: String? = null,
    /** OSM `category=type`, e.g. "amenity=restaurant". */
    val osmType: String? = null,
    val bounds: BBox? = null,
    /** True when found by searching inside the area shown on the map. */
    val inArea: Boolean = false,
    val distanceM: Double? = null,
    /** Short caution shown with the result, e.g. that halal status comes from the map data. */
    val note: String? = null,
)

class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface GeocodingProvider {
    val name: String
    /** [language] is a BCP-47 tag for names and addresses. [within] limits results to that area. */
    suspend fun search(query: String, near: GeoPoint?, language: String, within: BBox? = null, limit: Int = 10): List<Place>
    suspend fun reverse(point: GeoPoint, language: String, zoom: Int = 18): Place?
}

/** Translates short texts on the device. Implementations may be unavailable; callers must cope. */
interface TextTranslator {
    /** True when both languages are installed and translation will not need a download. */
    suspend fun isReady(from: String, to: String): Boolean
    /** Translations in the same order as [texts]; null entries where translation failed. */
    suspend fun translate(texts: List<String>, from: String, to: String): List<String?>
}

/**
 * Worldwide address and place search on OpenStreetMap data. The public Nominatim server allows
 * one request per second and no autocomplete, so requests are spaced out and the app searches
 * only when the user submits. [baseUrl] should point at a hosted instance for production volumes.
 */
class NominatimProvider(
    private val http: Http,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val minIntervalMs: Long = 1100,
) : GeocodingProvider {
    override val name = "Nominatim (OpenStreetMap)"
    private val gate = Mutex()
    private var lastRequestAt = 0L

    private suspend fun get(url: String, language: String): String = gate.withLock {
        val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try { http.getString(url, mapOf("Accept-Language" to language)) } finally { lastRequestAt = System.currentTimeMillis() }
    }

    override suspend fun search(query: String, near: GeoPoint?, language: String, within: BBox?, limit: Int): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val box = when {
            within != null -> "&viewbox=${within.west},${within.north},${within.east},${within.south}&bounded=1"
            // A view box without `bounded` only biases results towards the user.
            near != null -> "&viewbox=${near.lon - 1},${near.lat + 1},${near.lon + 1},${near.lat - 1}&bounded=0"
            else -> ""
        }
        val url = "$baseUrl/search?q=$q&format=jsonv2&limit=${limit.coerceIn(1, 40)}&addressdetails=1&namedetails=1$box"
        val text = try { get(url, language) } catch (e: Exception) {
            throw SearchException("Place search needs an internet connection (${e.message}).", e)
        }
        return try { parseSearch(text, language, inArea = within != null) } catch (e: Exception) {
            throw SearchException("The search service is busy. Try again in a moment.", e)
        }
    }

    override suspend fun reverse(point: GeoPoint, language: String, zoom: Int): Place? {
        val url = "$baseUrl/reverse?lat=${point.lat}&lon=${point.lon}&format=jsonv2&addressdetails=1&namedetails=1&zoom=$zoom"
        val text = try { get(url, language) } catch (e: Exception) { return null }
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        if (o.has("error")) return null
        return parsePlace(o, language, false)
    }

    companion object {
        fun parseSearch(json: String, language: String = "en", inArea: Boolean = false): List<Place> =
            JsonParser.parseString(json).asJsonArray.mapNotNull { parsePlace(it.asJsonObject, language, inArea) }

        private fun parsePlace(o: JsonObject, language: String, inArea: Boolean): Place? {
            val lat = o.get("lat")?.asString?.toDoubleOrNull() ?: return null
            val lon = o.get("lon")?.asString?.toDoubleOrNull() ?: return null
            val display = o.get("display_name")?.asString ?: return null
            val shown = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: display.substringBefore(',')
            val details = o.get("namedetails")?.takeIf { it.isJsonObject }?.asJsonObject
            val local = details?.get("name")?.asString?.takeIf { it.isNotBlank() } ?: shown
            val lang = language.substringBefore('-').lowercase()
            // A name mapped in the user's language, else the English one as a readable fallback.
            val translated = (details?.get("name:$lang") ?: details?.get("name:en"))?.asString?.trim()?.trim('‎')?.takeIf { it.isNotBlank() && it != local }
            val box = o.getAsJsonArray("boundingbox")?.mapNotNull { it.asString.toDoubleOrNull() }?.takeIf { it.size == 4 }
            val type = o.get("type")?.asString
            // Drop the place's own name from the front of the address line.
            val address = display.split(", ").let { parts -> if (parts.size > 1 && (parts[0] == shown || parts[0] == local)) parts.drop(1) else parts }.joinToString(", ")
            return Place(
                name = shown.trim('‎'),
                description = address,
                point = GeoPoint(lat, lon),
                category = type,
                countryCode = o.getAsJsonObject("address")?.get("country_code")?.asString?.uppercase(),
                localName = local.takeIf { it != shown.trim('‎') },
                translation = translated?.takeIf { it != shown.trim('‎') },
                osmType = o.get("category")?.asString?.let { c -> if (type != null) "$c=$type" else null },
                bounds = box?.let { BBox(it[0], it[2], it[1], it[3]) },
                inArea = inArea,
            )
        }
    }
}

class SearchResults(val places: List<Place>, /** What the search understood, shown above the list. */ val heading: String? = null)

/**
 * Turns what the user typed or said into results:
 *  - coordinates are resolved on the device and work offline;
 *  - a kind of place ("مطعم حلال", "hotel") is searched by category inside the area on the map,
 *    using the local-language terms for it, so it works where names are in another script;
 *  - anything else is searched as a name, in the area first and then worldwide.
 */
class SearchRepository(private val provider: GeocodingProvider, private val translator: TextTranslator? = null) {

    suspend fun search(query: String, near: GeoPoint?, language: String, area: BBox? = null, areaLanguage: String = "en"): SearchResults {
        CoordinateParser.parse(query)?.let { p ->
            return SearchResults(listOf(Place(String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon), "Coordinates", p)))
        }
        val text = query.trim()
        if (text.isEmpty()) return SearchResults(emptyList())
        val lang = language.substringBefore('-').lowercase()
        val searchArea = area?.takeIf { it.areaKm2 <= MAX_AREA_KM2 }
        val centre = searchArea?.let { GeoPoint((it.south + it.north) / 2, (it.west + it.east) / 2) } ?: near

        val concept = PoiConcepts.match(text)
        if (concept != null && searchArea != null) {
            val places = categorySearch(concept, searchArea, language, areaLanguage)
            val labelled = translateNames(places, areaLanguage, lang).map {
                it.copy(categoryLabel = concept.label(lang), distanceM = centre?.let { c -> Geo.distanceM(c, it.point) })
            }.sortedBy { it.distanceM ?: 0.0 }
            val searched = if (concept.halal) " (${PoiConcepts.halalTerms(areaLanguage).joinToString(", ")})" else ""
            return SearchResults(labelled, "${concept.label(lang)}$searched: ${labelled.size} in the area on the map")
        }

        val found = ArrayList<Place>()
        if (searchArea != null) {
            found += runCatching { provider.search(text, centre, language, searchArea, 20) }.getOrDefault(emptyList())
            // Also try the words as they would be written locally.
            if (areaLanguage != lang && translator?.isReady(lang, areaLanguage) == true) {
                val local = translator.translate(listOf(text), lang, areaLanguage).firstOrNull()
                if (!local.isNullOrBlank() && local != text) found += runCatching { provider.search(local, centre, language, searchArea, 20) }.getOrDefault(emptyList())
            }
        }
        found += provider.search(text, near, language)
        val merged = found.distinctBy { Math.round(it.point.lat * 1e5) to Math.round(it.point.lon * 1e5) }
        return SearchResults(translateNames(merged, areaLanguage, lang).map { p -> p.copy(distanceM = if (p.inArea) centre?.let { Geo.distanceM(it, p.point) } else null) })
    }

    private suspend fun categorySearch(concept: PoiConcept, area: BBox, language: String, areaLanguage: String): List<Place> {
        val queries = if (concept.halal) PoiConcepts.halalTerms(areaLanguage) else concept.keywords.map { "[$it]" }
        val found = ArrayList<Place>()
        var failure: SearchException? = null
        for (q in queries) {
            try { found += provider.search(q, null, language, area, 40) } catch (e: SearchException) { failure = e }
        }
        if (found.isEmpty() && failure != null) throw failure
        return found.filter { it.osmType == null || it.osmType in concept.types }
            .distinctBy { Math.round(it.point.lat * 1e5) to Math.round(it.point.lon * 1e5) }
            .map { if (concept.halal) it.copy(note = HALAL_NOTE) else it }
    }

    /** Adds a translation of each local name when the map data has none and the device can translate. */
    private suspend fun translateNames(places: List<Place>, from: String, to: String): List<Place> {
        val t = translator ?: return places
        if (from == to || places.isEmpty() || !runCatching { t.isReady(from, to) }.getOrDefault(false)) return places
        val missing = places.withIndex().filter { it.value.translation == null && (it.value.localName ?: it.value.name).any { ch -> ch.code > 0x24F } }
        if (missing.isEmpty()) return places
        val translated = runCatching { t.translate(missing.map { it.value.localName ?: it.value.name }, from, to) }.getOrNull() ?: return places
        val out = places.toMutableList()
        missing.forEachIndexed { i, entry -> translated.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { out[entry.index] = entry.value.copy(translation = it) } }
        return out
    }

    suspend fun reverse(point: GeoPoint, language: String): Place? = provider.reverse(point, language)

    /** Country (ISO code) at [point], or null when it cannot be looked up. */
    suspend fun countryAt(point: GeoPoint): String? = provider.reverse(point, "en", zoom = 3)?.countryCode

    companion object {
        /** Largest map area searched by category: roughly a metropolitan region. */
        const val MAX_AREA_KM2 = 60_000.0
        const val HALAL_NOTE = "Listed as halal in OpenStreetMap or by its name. Check the sign at the door."
    }
}
