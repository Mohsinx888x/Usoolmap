package com.usool.map.search

import com.google.gson.JsonParser
import com.usool.map.core.CoordinateParser
import com.usool.map.core.GeoPoint
import com.usool.map.core.Http
import java.net.URLEncoder

data class Place(
    val name: String,
    val description: String,
    val point: GeoPoint,
    val category: String? = null,
    val countryCode: String? = null,
)

class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface GeocodingProvider {
    val name: String
    /** [language] is a BCP-47 tag used to pick the language of place names. */
    suspend fun search(query: String, near: GeoPoint?, language: String): List<Place>
    suspend fun reverse(point: GeoPoint, language: String): Place?
}

/**
 * Worldwide address and place search on OpenStreetMap data. The public Nominatim server allows
 * one request per second and no autocomplete, so the app searches only when the user submits.
 * [baseUrl] should point at a hosted instance for production volumes.
 */
class NominatimProvider(
    private val http: Http,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
) : GeocodingProvider {
    override val name = "Nominatim (OpenStreetMap)"

    override suspend fun search(query: String, near: GeoPoint?, language: String): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        // A view box only biases results towards the user; it never limits them to that area.
        val bias = near?.let { "&viewbox=${it.lon - 1},${it.lat + 1},${it.lon + 1},${it.lat - 1}&bounded=0" } ?: ""
        val url = "$baseUrl/search?q=$q&format=jsonv2&limit=10&addressdetails=1$bias"
        val text = try {
            http.getString(url, mapOf("Accept-Language" to language))
        } catch (e: Exception) {
            throw SearchException("Place search needs an internet connection (${e.message}).", e)
        }
        return parseSearch(text)
    }

    override suspend fun reverse(point: GeoPoint, language: String): Place? {
        val url = "$baseUrl/reverse?lat=${point.lat}&lon=${point.lon}&format=jsonv2&addressdetails=1&zoom=18"
        val text = try {
            http.getString(url, mapOf("Accept-Language" to language))
        } catch (e: Exception) {
            return null
        }
        val o = JsonParser.parseString(text).asJsonObject
        if (o.has("error")) return null
        return parsePlace(o)
    }

    companion object {
        fun parseSearch(json: String): List<Place> =
            JsonParser.parseString(json).asJsonArray.mapNotNull { parsePlace(it.asJsonObject) }

        private fun parsePlace(o: com.google.gson.JsonObject): Place? {
            val lat = o.get("lat")?.asString?.toDoubleOrNull() ?: return null
            val lon = o.get("lon")?.asString?.toDoubleOrNull() ?: return null
            val display = o.get("display_name")?.asString ?: return null
            val name = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: display.substringBefore(',')
            return Place(
                name = name,
                description = display,
                point = GeoPoint(lat, lon),
                category = o.get("type")?.asString,
                countryCode = o.getAsJsonObject("address")?.get("country_code")?.asString?.uppercase(),
            )
        }
    }
}

class SearchRepository(private val provider: GeocodingProvider) {
    /** Coordinates are resolved on the device, so they work offline. Everything else is geocoded. */
    suspend fun search(query: String, near: GeoPoint?, language: String): List<Place> {
        CoordinateParser.parse(query)?.let { p ->
            return listOf(Place(String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon), "Coordinates", p))
        }
        if (query.isBlank()) return emptyList()
        return provider.search(query.trim(), near, language)
    }

    suspend fun reverse(point: GeoPoint, language: String): Place? = provider.reverse(point, language)
}
