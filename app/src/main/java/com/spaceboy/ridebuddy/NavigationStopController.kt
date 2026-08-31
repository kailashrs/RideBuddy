package com.spaceboy.ridebuddy

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import java.util.concurrent.atomic.AtomicBoolean

/** What a stop request did, so a caller with a UI can tell the rider. */
enum class NavigationStopResult {
    /** Guidance was stopped and the cluster cleared. */
    Stopped,

    /** Guidance stopped, but the SDK's own cleanup did not finish. */
    CleanupIncomplete,

    /** Guidance may still be running. */
    Failed,

    /** Another stop was already in flight; that one owns the outcome. */
    AlreadyStopping,
}

/**
 * The one place navigation is stopped.
 *
 * It lives at process scope rather than on an Activity because the handlebar EXIT button has to
 * work while guidance runs in the background — which is the normal riding case, with the phone
 * stowed and no navigation screen in the task. Guidance survives that (the SDK keeps its own
 * foreground service and `NavigationActivity` deliberately detaches rather than stopping), so a
 * stop path that only existed on the Activity was dead exactly when the rider needed it.
 */
class NavigationStopController internal constructor(
    private val application: Application,
    private val guard: NavigationStartStopGuard,
    private val guidanceLifecycle: NavigationGuidanceLifecycle,
    private val clearOutput: () -> Unit,
) {
    private val timeoutHandler = Handler(Looper.getMainLooper())

    /**
     * Stops guidance and clears the cluster.
     *
     * The navigator has to be fetched asynchronously even to stop it, which is why this is
     * callback-shaped. Every path reports exactly one result — including the three failure
     * paths where the SDK never usefully calls back: an immediate throw, an error callback,
     * and no callback at all.
     *
     * That last one is why there is a timeout. `getNavigator` makes no promise to call back,
     * and a request that is never answered leaves `stopInProgress` set forever, so every later
     * press of the handlebar EXIT button returns [NavigationStopResult.AlreadyStopping] and
     * guidance can no longer be stopped from anywhere. The timeout releases the guard and
     * reports [NavigationStopResult.Failed], which is the honest answer: nothing was stopped.
     *
     * It deliberately does **not** clear the cluster. Guidance may well still be running, and
     * a display cleared out from under a live route is worse than one that keeps showing it —
     * the rider would be navigating with no turn prompts and no indication why.
     */
    fun stop(onResult: (NavigationStopResult) -> Unit = {}) {
        val stopRequestId = guard.beginStop()
        if (stopRequestId == null) {
            onResult(NavigationStopResult.AlreadyStopping)
            return
        }
        // The timeout runs on the main thread while the SDK may call back on another, so the
        // two can race. Claiming the outcome once keeps the one-result-per-request contract
        // above true rather than merely likely.
        val settled = AtomicBoolean(false)
        val timeout = Runnable {
            if (!settled.compareAndSet(false, true)) return@Runnable
            if (!guard.isCurrentStop(stopRequestId)) return@Runnable
            guard.finishStop(stopRequestId)
            onResult(NavigationStopResult.Failed)
        }
        timeoutHandler.postDelayed(timeout, NavigatorHandoffTimeoutMillis)
        val started = runCatching {
            NavigationApi.getNavigator(
                application,
                object : NavigationApi.NavigatorListener {
                    override fun onNavigatorReady(navigator: Navigator) {
                        if (!settled.compareAndSet(false, true)) return
                        timeoutHandler.removeCallbacks(timeout)
                        if (!guard.isCurrentStop(stopRequestId)) return
                        onResult(finish(navigator, stopRequestId))
                    }

                    override fun onError(errorCode: Int) {
                        if (!settled.compareAndSet(false, true)) return
                        timeoutHandler.removeCallbacks(timeout)
                        if (!guard.isCurrentStop(stopRequestId)) return
                        guard.finishStop(stopRequestId)
                        onResult(NavigationStopResult.Failed)
                    }
                },
            )
        }.isSuccess
        if (!started && settled.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacks(timeout)
            if (guard.isCurrentStop(stopRequestId)) {
                guard.finishStop(stopRequestId)
                onResult(NavigationStopResult.Failed)
            }
        }
    }

    /**
     * Stops guidance, then cleans up.
     *
     * Ordered deliberately: guidance is stopped first, since that is the part the rider
     * asked for. If it fails, nothing else is attempted and the route may still be running.
     * Once it succeeds, cleanup failures are reported but never allowed to skip clearing the
     * display — a cluster still showing a route that has ended is worse than an untidy
     * teardown.
     */
    private fun finish(navigator: Navigator, stopRequestId: Long): NavigationStopResult {
        if (runCatching(navigator::stopGuidance).isFailure) {
            guard.finishStop(stopRequestId)
            return NavigationStopResult.Failed
        }
        guidanceLifecycle.release(navigator)
        // The route is already stopped by this point, so a cleanup failure is worth reporting but
        // must not leave the cluster showing a route that is no longer running.
        val cleanupFailed = listOf(
            runCatching(navigator::unregisterServiceForNavUpdates),
            runCatching(navigator::cleanup),
        ).any { it.isFailure }
        clearOutput()
        guard.finishStop(stopRequestId)
        return if (cleanupFailed) NavigationStopResult.CleanupIncomplete else NavigationStopResult.Stopped
    }

    private companion object {
        /**
         * How long to wait for the SDK to hand back a navigator before giving up on the stop.
         *
         * Long enough that a slow but working handoff is never cut short, short enough that a
         * rider who presses EXIT and sees nothing happen can press it again and have the second
         * press mean something.
         */
        const val NavigatorHandoffTimeoutMillis = 10_000L
    }
}
