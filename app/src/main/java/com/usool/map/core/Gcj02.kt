package com.usool.map.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02 is the shifted coordinate system that maps made in mainland China must use; it differs
 * from true (WGS-84) positions by 100 to 700 m. UsoolMap works in WGS-84 throughout: satellite
 * fixes, OpenStreetMap data and its map tiles all agree, so no shift is applied to the map.
 * This converter exists only for coordinates copied out of Chinese map apps.
 */
object Gcj02 {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun inMainlandChina(p: GeoPoint) = p.lon in 72.004..137.8347 && p.lat in 0.8293..55.8271

    private fun dLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun dLon(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    fun fromWgs84(p: GeoPoint): GeoPoint {
        if (!inMainlandChina(p)) return p
        var dy = dLat(p.lon - 105.0, p.lat - 35.0)
        var dx = dLon(p.lon - 105.0, p.lat - 35.0)
        val rad = p.lat / 180.0 * PI
        var magic = sin(rad)
        magic = 1 - EE * magic * magic
        val s = sqrt(magic)
        dy = dy * 180.0 / (A * (1 - EE) / (magic * s) * PI)
        dx = dx * 180.0 / (A / s * cos(rad) * PI)
        return GeoPoint(p.lat + dy, p.lon + dx)
    }

    /** Inverse by iteration; accurate to well under a metre. */
    fun toWgs84(gcj: GeoPoint): GeoPoint {
        if (!inMainlandChina(gcj)) return gcj
        var guess = gcj
        repeat(8) {
            val shifted = fromWgs84(guess)
            guess = GeoPoint(guess.lat - (shifted.lat - gcj.lat), guess.lon - (shifted.lon - gcj.lon))
        }
        return guess
    }
}
