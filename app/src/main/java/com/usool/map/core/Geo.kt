package com.usool.map.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 position in decimal degrees. Valid anywhere on Earth; the app has no regional bounds. */
data class GeoPoint(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0
}

data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Approximate area in km², good enough for download-size limits. */
    val areaKm2: Double
        get() {
            val h = Geo.distanceM(GeoPoint(south, west), GeoPoint(north, west)) / 1000.0
            val midLat = (south + north) / 2
            val w = Geo.distanceM(GeoPoint(midLat, west), GeoPoint(midLat, east)) / 1000.0
            return h * w
        }

    fun contains(p: GeoPoint) = p.lat in south..north && p.lon in west..east
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from true north in [0, 360). */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference `to - from` in degrees, in (-180, 180]. Positive = clockwise (right). */
    fun angleDiffDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Point reached travelling [distanceM] from [start] on [bearingDeg]. */
    fun destination(start: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0)
    }

    data class Projection(val point: GeoPoint, val t: Double, val distanceM: Double)

    /** Closest point to [p] on segment [a]-[b] (local equirectangular approximation). */
    fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k
        val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k
        val by = b.lat - p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-18) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val q = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return Projection(q, t, distanceM(p, q))
    }

    /** Decodes a Google encoded polyline. Valhalla and MOTIS use precision 6, OSRM uses 5. */
    fun decodePolyline(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            if (index >= encoded.length) break
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }

    fun pathLengthM(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distanceM(points[i - 1], points[i])
        return sum
    }

    fun bounds(points: List<GeoPoint>): BBox? {
        if (points.isEmpty()) return null
        return BBox(points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon })
    }

    fun nearlyEqual(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) < eps

    const val TWO_PI = 2 * PI
}
