package com.usool.map.core

/**
 * Recognises coordinates typed into the search box so they resolve without any network call:
 * decimal degrees ("25.2854, 51.5310"), hemisphere letters ("25.2854N 51.5310E"),
 * degrees-minutes-seconds ("25°17'07.4\"N 51°31'51.6\"E") and geo: URIs ("geo:25.2854,51.5310").
 */
object CoordinateParser {
    private val DECIMAL = Regex("""^\s*([+-]?\d{1,3}(?:\.\d+)?)\s*°?\s*([NSns])?\s*[,;\s]\s*([+-]?\d{1,3}(?:\.\d+)?)\s*°?\s*([EWew])?\s*$""")
    private val DMS_PART = Regex("""(\d{1,3})\s*[°d\s]\s*(\d{1,2})\s*['′m\s]\s*(?:(\d{1,2}(?:\.\d+)?)\s*(?:"|″|''|s)?)?\s*([NSEWnsew])""")
    private val GEO_URI = Regex("""^geo:([+-]?\d+(?:\.\d+)?),([+-]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): GeoPoint? {
        val input = text.trim()
        if (input.isEmpty()) return null

        // "gcj:23.1291,113.2644": a position copied from a Chinese map app, converted to true coordinates.
        Regex("""^gcj(?:-?02)?\s*[: ]\s*(.+)$""", RegexOption.IGNORE_CASE).matchEntire(input)?.let { m ->
            return parse(m.groupValues[1])?.let { Gcj02.toWgs84(it) }
        }

        GEO_URI.find(input)?.let { m ->
            return valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }

        DECIMAL.matchEntire(input)?.let { m ->
            var lat = m.groupValues[1].toDouble()
            var lon = m.groupValues[3].toDouble()
            if (m.groupValues[2].equals("S", true)) lat = -kotlin.math.abs(lat)
            if (m.groupValues[4].equals("W", true)) lon = -kotlin.math.abs(lon)
            return valid(lat, lon)
        }

        val parts = DMS_PART.findAll(input).toList()
        if (parts.size == 2) {
            var lat: Double? = null
            var lon: Double? = null
            for (p in parts) {
                val deg = p.groupValues[1].toDouble()
                val min = p.groupValues[2].toDouble()
                val sec = p.groupValues[3].toDoubleOrNull() ?: 0.0
                if (min >= 60 || sec >= 60) return null
                val value = deg + min / 60 + sec / 3600
                when (p.groupValues[4].uppercase()) {
                    "N" -> lat = value
                    "S" -> lat = -value
                    "E" -> lon = value
                    "W" -> lon = -value
                }
            }
            if (lat != null && lon != null) return valid(lat, lon)
        }
        return null
    }

    private fun valid(lat: Double, lon: Double): GeoPoint? = GeoPoint(lat, lon).takeIf { it.isValid }
}
