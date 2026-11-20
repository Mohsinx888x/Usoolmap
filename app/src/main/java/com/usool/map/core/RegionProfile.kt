package com.usool.map.core

import java.util.Locale

enum class DistanceUnit { METRIC, IMPERIAL }
enum class DrivingSide { RIGHT, LEFT }

/**
 * Per-country conventions. Every ISO 3166-1 code resolves to a profile, so no country is excluded;
 * the tables below only list the exceptions to the worldwide defaults (metric, drive on the right).
 */
data class RegionProfile(
    val countryCode: String,
    val distanceUnit: DistanceUnit,
    val drivingSide: DrivingSide,
    /** False where warning drivers about fixed speed cameras is restricted by law. */
    val speedCameraAlertsAllowed: Boolean,
) {
    val speedUnitLabel: String get() = if (distanceUnit == DistanceUnit.IMPERIAL) "mph" else "km/h"

    fun speedFromMps(mps: Double): Double =
        if (distanceUnit == DistanceUnit.IMPERIAL) mps * 2.2369362921 else mps * 3.6

    fun speedFromKmh(kmh: Double): Double =
        if (distanceUnit == DistanceUnit.IMPERIAL) kmh * 0.6213711922 else kmh

    fun formatDistance(meters: Double, locale: Locale = Locale.getDefault()): String =
        if (distanceUnit == DistanceUnit.IMPERIAL) {
            val feet = meters * 3.280839895
            if (feet < 1000) String.format(locale, "%d ft", (feet / 50).toInt().coerceAtLeast(1) * 50)
            else String.format(locale, "%.1f mi", meters / 1609.344)
        } else {
            when {
                meters < 1000 -> String.format(locale, "%d m", (meters / 10).toInt().coerceAtLeast(1) * 10)
                meters < 100_000 -> String.format(locale, "%.1f km", meters / 1000)
                else -> String.format(locale, "%d km", (meters / 1000).toInt())
            }
        }

    companion object {
        /** Qatar is where the map opens on first launch. It is a default, not a limit. */
        const val DEFAULT_COUNTRY = "QA"
        val DEFAULT_CENTER = GeoPoint(25.2854, 51.5310)

        // Countries whose road signs use miles / mph.
        private val IMPERIAL = setOf(
            "US", "GB", "LR", "MM", "PR", "GU", "VI", "AS", "MP", "BS", "BZ", "KY", "DM", "FK",
            "GD", "KN", "LC", "VC", "WS", "TC", "VG", "AG", "AI", "MS", "GG", "JE", "IM",
        )

        // Countries and territories that drive on the left.
        private val LEFT_HAND = setOf(
            "AG", "AI", "AU", "BB", "BD", "BM", "BN", "BS", "BT", "BW", "CC", "CK", "CX", "CY", "DM",
            "FJ", "FK", "GB", "GD", "GG", "GY", "HK", "ID", "IE", "IM", "IN", "JE", "JM", "JP", "KE",
            "KI", "KN", "KY", "LC", "LK", "LS", "MO", "MS", "MT", "MU", "MV", "MW", "MY", "MZ", "NA",
            "NF", "NP", "NR", "NU", "NZ", "PG", "PK", "PN", "SB", "SC", "SG", "SH", "SR", "SZ", "TC",
            "TH", "TK", "TL", "TO", "TT", "TV", "TZ", "UG", "VC", "VG", "VI", "WS", "ZA", "ZM", "ZW",
        )

        /**
         * Conservative list of countries where announcing exact fixed-camera positions to a driver
         * is restricted. This is a product safeguard, not legal advice: it must be reviewed by
         * counsel before release and can be updated without touching any provider code.
         */
        private val CAMERA_ALERTS_RESTRICTED = setOf("CH", "DE", "FR", "AT", "LI", "CY")

        fun forCountry(code: String?): RegionProfile {
            val cc = (code ?: DEFAULT_COUNTRY).uppercase(Locale.ROOT).ifBlank { DEFAULT_COUNTRY }
            return RegionProfile(
                countryCode = cc,
                distanceUnit = if (cc in IMPERIAL) DistanceUnit.IMPERIAL else DistanceUnit.METRIC,
                drivingSide = if (cc in LEFT_HAND) DrivingSide.LEFT else DrivingSide.RIGHT,
                speedCameraAlertsAllowed = cc !in CAMERA_ALERTS_RESTRICTED,
            )
        }
    }
}

/** Whether a feature can be used right now, and if not, the reason shown to the user. */
sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: String) : Availability

    val isAvailable: Boolean get() = this is Available
}
