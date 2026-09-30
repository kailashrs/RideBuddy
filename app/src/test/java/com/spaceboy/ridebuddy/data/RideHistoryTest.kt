package com.spaceboy.ridebuddy.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class RideHistoryTest {
    private val today = LocalDate.of(2026, 9, 30)

    @Test fun groupsByLocalStartDateWithNewestDaysAndRidesFirst() {
        val rides = listOf(
            ride(1, "2026-09-29T17:00:00Z"),
            ride(3, "2026-09-29T20:00:00Z"), // September 30 in India
            ride(2, "2026-09-29T18:00:00Z", minutes = 90), // Ends after midnight locally
        )
        val result = filterRideHistory(rides, HistoryFilter.All, today, ZoneId.of("Asia/Kolkata"), Locale.UK)
        assertEquals(listOf(today, today.minusDays(1)), result.days.map { it.date })
        assertEquals(listOf(2L, 1L), result.days[1].rides.map { it.id })
        assertEquals(3, result.rideCount)
        assertEquals(30.0, result.distanceKilometres, 0.001)
        assertEquals(150 * 60_000L, result.durationMillis)
    }

    @Test fun customRangeIncludesBothDaysButNotTheNextMidnightAcrossDst() {
        val date = LocalDate.of(2026, 3, 8)
        val rides = listOf(
            ride(1, "2026-03-08T07:59:59Z"),
            ride(2, "2026-03-08T08:00:00Z"),
            ride(3, "2026-03-09T06:59:59Z"),
            ride(4, "2026-03-09T07:00:00Z"),
        )
        val result = filterRideHistory(rides, HistoryFilter.Dates(date, date), date,
            ZoneId.of("America/Los_Angeles"), Locale.US)
        assertEquals(listOf(3L, 2L), result.days.single().rides.map { it.id })
        assertEquals(2, result.rideCount)
        assertEquals(20.0, result.distanceKilometres, 0.001)
    }

    @Test fun calendarWeekUsesLocaleAndMonthIncludesItsFirstDay() {
        val rides = listOf(ride(1, "2026-08-31T23:59:59Z"), ride(2, "2026-09-01T00:00:00Z"),
            ride(3, "2026-09-27T10:00:00Z"), ride(4, "2026-09-28T10:00:00Z"))
        assertEquals(1, filterRideHistory(rides, HistoryFilter.ThisWeek, today, ZoneOffset.UTC, Locale.UK).rideCount)
        assertEquals(2, filterRideHistory(rides, HistoryFilter.ThisWeek, today, ZoneOffset.UTC, Locale.US).rideCount)
        assertEquals(3, filterRideHistory(rides, HistoryFilter.ThisMonth, today, ZoneOffset.UTC, Locale.UK).rideCount)
    }

    @Test fun emptyResultsHaveEmptyGroupsAndZeroTotals() {
        val result = filterRideHistory(listOf(ride(1, "2026-08-01T00:00:00Z")),
            HistoryFilter.ThisMonth, today, ZoneOffset.UTC, Locale.UK)
        assertTrue(result.days.isEmpty())
        assertEquals(0, result.rideCount)
        assertEquals(0.0, result.distanceKilometres, 0.0)
        assertEquals(0L, result.durationMillis)
    }

    @Test fun multipleDayRangeIncludesEndDayAndYearBoundary() {
        val start = LocalDate.of(2025, 12, 31)
        val end = LocalDate.of(2026, 1, 1)
        val rides = listOf(ride(1, "2025-12-30T23:59:59Z"), ride(2, "2025-12-31T00:00:00Z"),
            ride(3, "2026-01-01T23:59:59Z"), ride(4, "2026-01-02T00:00:00Z"))
        assertEquals(listOf(end, start), filterRideHistory(rides, HistoryFilter.Dates(start, end),
            today, ZoneOffset.UTC, Locale.UK).days.map { it.date })
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedRangeIsRejected() { HistoryFilter.Dates(today, today.minusDays(1)) }

    private fun ride(id: Long, start: String, minutes: Int = 30) = Ride(
        id, Instant.parse(start).toEpochMilli(), Instant.parse(start).toEpochMilli() + minutes * 60_000,
        10.0, 20.0, 50.0, 3000.0, 5000, 30.0, 0.5,
    )
}
