package com.spaceboy.ridebuddy.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps stored history within what the rider asked to keep, and brings back what a restore
 * left behind.
 *
 * Separate from [RideRecorder] because none of this is recording: the recorder's job ends
 * when a ride is saved, and how long that ride's samples then survive is a question about
 * the history as a whole. Separate from [RideRepository] because the repository stores what
 * it is told to and does not read settings.
 */
internal class RideHistoryMaintenance(
    private val repository: RideRepository,
    private val settingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs the restore once, then prunes on every retention change.
     *
     * Collecting the setting rather than pruning once covers both occasions that matter with
     * one path: the flow replays its current value immediately, which is the pass at startup,
     * and emits again when the rider shortens the window, which is when they expect to get
     * the space back rather than at some later launch.
     */
    fun start() {
        scope.launch {
            restore()
            settingsRepository.settings
                .map { it.sampleRetention }
                .distinctUntilChanged()
                .collect(::prune)
        }
    }

    private suspend fun restore() {
        try {
            val restored = repository.restoreFromBackupIfEmpty()
            if (restored > 0) Log.i(LogTag, "Restored $restored ride summaries from backup")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LogTag, "Could not restore ride summaries from backup", error)
        }
    }

    private suspend fun prune(retention: SampleRetention) {
        val days = retention.days ?: return
        try {
            val removed = repository.pruneSamplesStartedBefore(nowMillis() - days * MillisPerDay)
            if (removed > 0) Log.i(LogTag, "Removed $removed telemetry samples past the ${retention.label} window")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LogTag, "Could not apply the telemetry retention window", error)
        }
    }

    private companion object {
        const val LogTag = "RideHistoryMaintenance"
        const val MillisPerDay = 86_400_000L
    }
}
