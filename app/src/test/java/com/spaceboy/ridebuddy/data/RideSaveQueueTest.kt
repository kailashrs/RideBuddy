package com.spaceboy.ridebuddy.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSaveQueueTest {
    @Test
    fun `failed insert retains the exact completed ride and samples for retry`() = runBlocking {
        var diskAvailable = false
        val attempts = mutableListOf<Pair<Ride, List<RideSample>>>()
        val savedIds = mutableListOf<Long>()
        val queue = RideSaveQueue(
            insert = { ride, samples ->
                attempts += ride to samples
                check(diskAvailable) { "disk full" }
                17L
            },
            onSaved = { id, _ -> savedIds += id },
            onFailure = {},
        )
        val ride = ride(1_000L)
        val samples = mutableListOf(sample())
        queue.enqueue(ride, samples)
        samples.clear()

        assertFalse(queue.flush())
        assertTrue(queue.saveFailed.value)
        assertTrue(savedIds.isEmpty())

        diskAvailable = true
        assertTrue(queue.flush())
        assertFalse(queue.saveFailed.value)
        assertEquals(listOf(17L), savedIds)
        assertEquals(listOf(ride to listOf(sample()), ride to listOf(sample())), attempts)
        assertTrue(queue.flush())
        assertEquals(2, attempts.size)
    }

    @Test
    fun `retry preserves order without reinserting the earlier successful ride`() = runBlocking {
        var secondRideAllowed = false
        val inserted = mutableListOf<Long>()
        val queue = RideSaveQueue(
            insert = { ride, _ ->
                check(ride.startedAtMillis != 2_000L || secondRideAllowed)
                inserted += ride.startedAtMillis
                ride.startedAtMillis
            },
            onSaved = { _, _ -> },
            onFailure = {},
        )
        queue.enqueue(ride(1_000L), emptyList())
        queue.enqueue(ride(2_000L), emptyList())

        assertFalse(queue.flush())
        assertEquals(listOf(1_000L), inserted)
        secondRideAllowed = true
        assertTrue(queue.flush())
        assertEquals(listOf(1_000L, 2_000L), inserted)
    }

    @Test
    fun `two shutdown callers wait on one insert instead of duplicating it`() = runBlocking {
        val releaseInsert = CompletableDeferred<Unit>()
        var inserts = 0
        val queue = RideSaveQueue(
            insert = { _, _ -> inserts++; releaseInsert.await(); 1L },
            onSaved = { _, _ -> },
            onFailure = {},
        )
        queue.enqueue(ride(1_000L), emptyList())
        val first = launch(start = CoroutineStart.UNDISPATCHED) { assertTrue(queue.flush()) }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { assertTrue(queue.flush()) }

        assertEquals(1, inserts)
        releaseInsert.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, inserts)
    }

    @Test
    fun `cancelling the waiting service cannot abandon an insert and duplicate it later`() = runBlocking {
        val releaseInsert = CompletableDeferred<Unit>()
        var inserts = 0
        val queue = RideSaveQueue(
            insert = { _, _ -> inserts++; releaseInsert.await(); 1L },
            onSaved = { _, _ -> },
            onFailure = {},
        )
        queue.enqueue(ride(1_000L), emptyList())
        val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.flush() }
        first.cancel()
        releaseInsert.complete(Unit)
        first.join()

        assertTrue(queue.flush())
        assertEquals(1, inserts)
    }

    private fun ride(start: Long) = Ride(
        id = 0L,
        startedAtMillis = start,
        endedAtMillis = start + 1_000L,
        distanceKilometres = 0.01,
        averageSpeedKph = 36.0,
        maximumSpeedKph = 36.0,
        averageRpm = 3_000.0,
        maximumRpm = 3_000L,
        averageThrottlePercent = 10.0,
        estimatedFuelLitres = null,
    )

    private fun sample() = RideSample(
        timestampMillis = 1_000L,
        speedKph = 36.0,
        rpm = 3_000L,
        throttlePercent = 10,
        mileageKilometresPerLitre = null,
        accelerationMetresPerSecondSquared = 0.0,
    )
}
