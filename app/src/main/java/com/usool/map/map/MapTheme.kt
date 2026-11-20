package com.usool.map.map

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

enum class MapThemeMode(val label: String) {
    AUTO("Automatic (dark at night)"),
    DAY("Always light"),
    NIGHT("Always dark"),
}

/** Which language place names are drawn in. */
enum class MapLabels(val label: String, val code: String?) {
    PHONE("Phone language", null),
    ARABIC("العربية", "ar"),
    ENGLISH("English", "en"),
    LOCAL("Local names", "local"),
}

/**
 * Colours for one look of the map (day or night), loaded from `assets/map_themes.json`.
 * [rules] recolour layers of the base style: the first rule whose pattern matches a layer id wins.
 */
class MapTheme(
    val dark: Boolean,
    val routeLine: String,
    val routeCasing: String,
    val positionFill: String,
    val positionStroke: String,
    val destinationFill: String,
    val destinationStroke: String,
    /** Building colour by height in metres, lowest first. */
    val buildingStops: List<Pair<Double, String>>,
    private val rules: List<Rule>,
) {
    class Rule(val pattern: Regex, val hide: Boolean, val paint: Map<String, String>)

    fun ruleFor(layerId: String): Rule? = rules.firstOrNull { it.pattern.containsMatchIn(layerId) }

    /**
     * Paint overrides that apply to a layer of [layerType] ("fill", "line", "symbol",
     * "background"). Symbol layers take the `text-*` properties.
     */
    fun paintFor(layerId: String, layerType: String): Map<String, String> {
        val rule = ruleFor(layerId) ?: return emptyMap()
        val prefix = if (layerType == "symbol") "text" else layerType
        return rule.paint.filterKeys { it.substringBefore('-') == prefix }
    }

    companion object {
        fun parse(json: String, dark: Boolean): MapTheme {
            val t = JsonParser.parseString(json).asJsonObject.getAsJsonObject(if (dark) "night" else "day")
            fun pair(name: String, a: String, b: String): Pair<String, String> {
                val o: JsonObject = t.getAsJsonObject(name)
                return o.get(a).asString to o.get(b).asString
            }
            val route = pair("route", "line", "casing")
            val position = pair("position", "fill", "stroke")
            val destination = pair("destination", "fill", "stroke")
            return MapTheme(
                dark = dark,
                routeLine = route.first, routeCasing = route.second,
                positionFill = position.first, positionStroke = position.second,
                destinationFill = destination.first, destinationStroke = destination.second,
                buildingStops = t.getAsJsonArray("buildings").map { it.asJsonArray[0].asDouble to it.asJsonArray[1].asString },
                rules = t.getAsJsonArray("rules").map { el ->
                    val o = el.asJsonObject
                    Rule(
                        pattern = Regex(o.get("id").asString),
                        hide = o.get("hide")?.asBoolean ?: false,
                        paint = o.getAsJsonObject("paint")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap(),
                    )
                },
            )
        }

        /** Tile properties to try, in order, for a label in [code] ("ar", "en", ..., or "local"). */
        fun labelFields(code: String): List<String> = when (code) {
            "local" -> listOf("name")
            "en" -> listOf("name:en", "name_en", "name:latin", "name")
            else -> listOf("name:$code", "name_$code", "name:nonlatin", "name")
        }
    }
}

/** Rough position of the sun, enough to decide when the map should switch to its night colours. */
object SunClock {
    /** Sun elevation above the horizon in degrees at [epochMs] for the given place. */
    fun elevationDeg(epochMs: Long, lat: Double, lon: Double): Double {
        val t = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC)
        val declination = Math.toRadians(-23.44 * cos(Math.toRadians(360.0 / 365.0 * (t.dayOfYear + 10))))
        val solarHours = t.hour + t.minute / 60.0 + lon / 15.0
        val hourAngle = Math.toRadians(15.0 * (solarHours - 12.0))
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle)))
    }

    /** True from shortly after sunset until shortly before sunrise. */
    fun isNight(epochMs: Long, lat: Double, lon: Double): Boolean = elevationDeg(epochMs, lat, lon) < -3.0
}
