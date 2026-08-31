package com.spaceboy.ridebuddy.data

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Converts and formats every quantity the UI shows.
 *
 * Everything is stored and computed in metric — km, km/h, km/L, litres — and converted
 * only at the point of display, so the rider's unit preference never affects recorded
 * data or an export.
 *
 * Imperial is not one system here. Distance and speed are the same everywhere, but a
 * gallon is not: US and imperial gallons differ by about 20%, so fuel and mileage
 * additionally branch on the locale's country. Every conversion takes an explicit [Locale]
 * rather than reading the default, both for that reason and so the decimal separator is
 * the rider's.
 */
object UnitFormatter {
    fun distance(kilometres: Double, units: DistanceUnits, locale: Locale, decimals: Int = 1): String =
        "%.${decimals}f %s".format(locale, distanceValue(kilometres, units), distanceUnit(units))

    fun speed(kph: Double, units: DistanceUnits, locale: Locale, decimals: Int = 0): String =
        "%.${decimals}f %s".format(locale, chartSpeed(kph, units), speedUnit(units))

    /** The numeric distance in the rider's units, for charts that format their own labels. */
    fun distanceValue(kilometres: Double, units: DistanceUnits): Double =
        if (units == DistanceUnits.Metric) kilometres else kilometres * KmToMiles

    /** Formatted mileage, or a dash with the correct unit when there is no usable figure. */
    fun mileage(kilometresPerLitre: Double?, units: DistanceUnits, locale: Locale): String =
        mileageValue(kilometresPerLitre, units, locale)?.let { "%.1f %s".format(locale, it, mileageUnit(units)) }
            ?: "— ${mileageUnit(units)}"

    /** Numeric mileage in the rider's units — US mpg and imperial mpg are different numbers. */
    fun mileageValue(
        kilometresPerLitre: Double?,
        units: DistanceUnits,
        locale: Locale = Locale.getDefault(),
    ): Double? = kilometresPerLitre?.takeIf { it.isFinite() && it > 0.0 }?.let { value ->
        if (units == DistanceUnits.Metric) value
        else if (locale.country.equals("US", ignoreCase = true)) value * KilometresPerLitreToUsMpg
        else value * KilometresPerLitreToImperialMpg
    }

    fun mileageUnit(units: DistanceUnits): String = if (units == DistanceUnits.Metric) "km/L" else "mpg"

    fun chartSpeed(kph: Double, units: DistanceUnits): Double =
        if (units == DistanceUnits.Metric) kph else kph * KmToMiles

    /** Inverse of [chartSpeed], for controls that are driven in display units. */
    fun speedFromChart(value: Double, units: DistanceUnits): Double =
        if (units == DistanceUnits.Metric) value else value / KmToMiles
    fun fuel(litres: Double?, units: DistanceUnits, locale: Locale): String =
        litres?.takeIf { it.isFinite() && it >= 0.0 }?.let { value ->
            if (units == DistanceUnits.Metric) "%.1f L".format(locale, value)
            else "%.1f gal".format(locale, value * gallonsPerLitre(locale))
        } ?: if (units == DistanceUnits.Metric) "— L" else "— gal"

    /**
     * Distance to a turn, in the unit a rider expects at that range: metres below a
     * kilometre and feet below a tenth of a mile, switching to the larger unit above.
     */
    fun maneuverDistance(metres: Int, units: DistanceUnits, locale: Locale): String {
        val safeMetres = metres.coerceAtLeast(0)
        return if (units == DistanceUnits.Metric) {
            if (safeMetres < MetresPerKilometre) "$safeMetres m"
            else "%.1f km".format(locale, safeMetres / MetresPerKilometre.toDouble())
        } else {
            val miles = safeMetres / MetresPerMile
            if (miles < MinimumDisplayedMiles) {
                "%d ft".format(locale, (safeMetres * FeetPerMetre).roundToInt())
            } else "%.1f mi".format(locale, miles)
        }
    }

    fun distanceUnit(units: DistanceUnits) = if (units == DistanceUnits.Metric) "km" else "mi"
    fun speedUnit(units: DistanceUnits) = if (units == DistanceUnits.Metric) "km/h" else "mph"

    private const val KmToMiles = 0.621371192
    private const val LitresToUsGallons = 0.264172052
    private const val LitresToImperialGallons = 0.219969157
    private const val KilometresPerLitreToUsMpg = 2.35214583
    private const val KilometresPerLitreToImperialMpg = 2.82480936
    private const val MetresPerKilometre = 1_000
    private const val MetresPerMile = 1_609.344
    private const val FeetPerMetre = 3.280839895
    private const val MinimumDisplayedMiles = 0.1

    /** US and imperial gallons differ by roughly 20%, so the country decides. */
    private fun gallonsPerLitre(locale: Locale): Double =
        if (locale.country.equals("US", ignoreCase = true)) LitresToUsGallons else LitresToImperialGallons

    // Platform date formatting throughout, so dates follow the phone's locale and
    // 12/24-hour setting rather than a hardcoded pattern.

    fun formatDateTime(millis: Long): String =
        DateFormat.getDateTimeInstance().format(Date(millis))

    fun formatShortDateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun formatTime(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
}
