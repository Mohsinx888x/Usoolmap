package com.usool.map.gnss

import com.usool.map.core.GeoPoint

/**
 * Satellite systems Android can report. [androidType] matches the `GnssStatus.CONSTELLATION_*`
 * constants, kept as plain ints so this file has no Android dependency.
 *
 * The app only reads what the receiver reports. It cannot switch constellations on or off:
 * which systems are tracked is decided by the device's chipset, antenna and firmware.
 */
enum class Constellation(val androidType: Int, val label: String, val operator: String) {
    GPS(1, "GPS", "United States"),
    SBAS(2, "SBAS", "Regional augmentation"),
    GLONASS(3, "GLONASS", "Russia"),
    QZSS(4, "QZSS", "Japan"),
    BEIDOU(5, "BeiDou", "China"),
    GALILEO(6, "Galileo", "European Union"),
    NAVIC(7, "NavIC", "India"),
    UNKNOWN(0, "Unknown", "");

    companion object {
        fun fromAndroid(type: Int): Constellation = entries.firstOrNull { it.androidType == type } ?: UNKNOWN
    }
}

data class SatelliteInfo(
    val svid: Int,
    val constellation: Constellation,
    /** Carrier-to-noise density in dB-Hz; 0 when the receiver is not tracking the signal. */
    val cn0DbHz: Float,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float? = null,
)

enum class SignalQuality { NONE, POOR, FAIR, GOOD, EXCELLENT }

enum class FixState {
    /** Location permission missing or the device location switch is off. */
    DISABLED,
    /** Listening, no position yet. */
    SEARCHING,
    /** Receiving satellite positions. */
    FIXED,
    /** Had a fix, satellites lost; position is carried forward by sensors. */
    DEAD_RECKONING,
    /** Had a fix, lost it, and it can no longer be estimated. */
    LOST,
}

data class PositionFix(
    val point: GeoPoint,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val verticalAccuracyM: Float?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    /** Monotonic time of the fix in milliseconds (elapsedRealtime). */
    val elapsedMs: Long,
    /** True when produced by dead reckoning rather than a satellite solution. */
    val estimated: Boolean = false,
)

data class GnssSnapshot(
    val state: FixState = FixState.SEARCHING,
    val fix: PositionFix? = null,
    val satellites: List<SatelliteInfo> = emptyList(),
    /** False when the device does not expose satellite status at all. */
    val satelliteStatusAvailable: Boolean = true,
    val providerEnabled: Boolean = true,
    val hardwareModel: String? = null,
    val lastUpdateElapsedMs: Long? = null,
    val msSinceLastFix: Long? = null,
    val warning: String? = null,
) {
    val visibleCount: Int get() = satellites.size
    val usedCount: Int get() = satellites.count { it.usedInFix }

    /** Constellations seen by the receiver, with (visible, used) counts. */
    val byConstellation: Map<Constellation, Pair<Int, Int>>
        get() = satellites.groupBy { it.constellation }
            .mapValues { (_, list) -> list.size to list.count { it.usedInFix } }
            .toSortedMap(compareBy { it.ordinal })

    /** Mean C/N0 of the satellites used in the fix (or of all tracked ones before a fix). */
    val meanCn0DbHz: Float?
        get() {
            val pool = satellites.filter { it.usedInFix && it.cn0DbHz > 0f }
                .ifEmpty { satellites.filter { it.cn0DbHz > 0f } }
            return if (pool.isEmpty()) null else pool.map { it.cn0DbHz }.average().toFloat()
        }

    val signalQuality: SignalQuality
        get() {
            val cn0 = meanCn0DbHz ?: return SignalQuality.NONE
            return when {
                usedCount < 4 && state != FixState.FIXED -> SignalQuality.POOR
                cn0 >= 38f -> SignalQuality.EXCELLENT
                cn0 >= 30f -> SignalQuality.GOOD
                cn0 >= 22f -> SignalQuality.FAIR
                else -> SignalQuality.POOR
            }
        }
}
