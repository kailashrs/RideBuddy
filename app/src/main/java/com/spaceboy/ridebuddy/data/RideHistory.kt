package com.spaceboy.ridebuddy.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

internal sealed interface HistoryFilter {
    data object All : HistoryFilter
    data object ThisWeek : HistoryFilter
    data object ThisMonth : HistoryFilter
    data class Dates(val start: LocalDate, val endInclusive: LocalDate) : HistoryFilter {
        init { require(!endInclusive.isBefore(start)) }
    }
}

internal data class RideHistoryDay(val date: LocalDate, val rides: List<Ride>)

internal data class RideHistory(
    val days: List<RideHistoryDay>,
    val rideCount: Int,
    val distanceKilometres: Double,
    val durationMillis: Long,
)

/** Calendar dates use the phone's zone; a ride belongs to the day it started. */
internal fun filterRideHistory(
    rides: List<Ride>,
    filter: HistoryFilter,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale,
): RideHistory {
    val range = filter.dateRange(today, locale)
    val groups = sortedMapOf<LocalDate, MutableList<Ride>>(reverseOrder())
    var count = 0
    var distance = 0.0
    var duration = 0L
    rides.forEach { ride ->
        val date = Instant.ofEpochMilli(ride.startedAtMillis).atZone(zone).toLocalDate()
        if (range != null && (date < range.start || date > range.endInclusive)) return@forEach
        groups.getOrPut(date) { mutableListOf() }.add(ride)
        count++
        distance += ride.distanceKilometres
        duration += ride.durationMillis
    }
    return RideHistory(
        days = groups.map { (date, dailyRides) ->
            RideHistoryDay(date, dailyRides.sortedWith(compareByDescending<Ride> { it.startedAtMillis }.thenByDescending { it.id }))
        },
        rideCount = count,
        distanceKilometres = distance,
        durationMillis = duration,
    )
}

internal fun HistoryFilter.dateRange(today: LocalDate, locale: Locale): HistoryFilter.Dates? = when (this) {
    HistoryFilter.All -> null
    HistoryFilter.ThisWeek -> HistoryFilter.Dates(today.startOfWeek(locale), today)
    HistoryFilter.ThisMonth -> HistoryFilter.Dates(today.withDayOfMonth(1), today)
    is HistoryFilter.Dates -> this
}

/** The first day of [this] date's week, on the day [locale] starts its weeks. */
internal fun LocalDate.startOfWeek(locale: Locale): LocalDate = with(WeekFields.of(locale).dayOfWeek(), 1)
