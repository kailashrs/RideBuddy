package com.spaceboy.ridebuddy.data

import java.util.Locale

object UnitFormatter {
    fun distance(kilometres: Double, units: DistanceUnits, locale: Locale, decimals: Int = 1): String {
        val value = if (units == DistanceUnits.Metric) kilometres else kilometres * KmToMiles
        val suffix = distanceUnit(units)
        return "%.$decimals".plus("f $suffix").format(locale, value)
    }

    fun speed(kph: Double, units: DistanceUnits, locale: Locale, decimals: Int = 0): String {
        val value = if (units == DistanceUnits.Metric) kph else kph * KmToMiles
        val suffix = if (units == DistanceUnits.Metric) "km/h" else "mph"
        return "%.$decimals".plus("f $suffix").format(locale, value)
    }

    /**
     * Telemetry is stored canonically as L/100 km, but motorcycle riders using metric units
     * usually think in km/L. Keeping the source unit unchanged avoids database migrations.
     */
    fun consumption(litresPer100Km: Double, units: DistanceUnits, locale: Locale): String =
        mileageValue(litresPer100Km, units, locale)?.let { "%.1f %s".format(locale, it, mileageUnit(units)) }
            ?: "— ${mileageUnit(units)}"

    fun mileageValue(litresPer100Km: Double, units: DistanceUnits, locale: Locale = Locale.getDefault()): Double? =
        litresPer100Km.takeIf { it > 0.0 }?.let { value ->
            if (units == DistanceUnits.Metric) 100.0 / value
            else if (locale.country.equals("US", ignoreCase = true)) 235.214583 / value
            else 282.480936 / value
        }

    fun mileageUnit(units: DistanceUnits): String = if (units == DistanceUnits.Metric) "km/L" else "mpg"

    fun chartSpeed(kph: Double, units: DistanceUnits): Double = if (units == DistanceUnits.Metric) kph else kph * KmToMiles
    fun fuel(litres: Double, units: DistanceUnits, locale: Locale): String =
        if (units == DistanceUnits.Metric) "%.1f L".format(locale, litres) else "%.1f gal".format(locale, litres * LitresToUsGallons)
    fun distanceUnit(units: DistanceUnits) = if (units == DistanceUnits.Metric) "km" else "mi"
    fun speedUnit(units: DistanceUnits) = if (units == DistanceUnits.Metric) "km/h" else "mph"

    private const val KmToMiles = 0.621371192
    private const val LitresToUsGallons = 0.264172052
}
