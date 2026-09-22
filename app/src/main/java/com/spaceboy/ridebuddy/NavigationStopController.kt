package com.spaceboy.ridebuddy

import android.app.Application
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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
/**
 * How the SDK is asked for the navigator that is about to be stopped.
 *
 * A seam, because `NavigationApi.getNavigator` is a static call — which is what left the
 * trickiest concurrency in the app with no test around it. The real implementation is
 * [SdkNavigatorHandoff]; a test supplies one that calls back, errors, throws, or says nothing.
 *
 * The contract is the SDK's own: call back at most once, or throw.
 */
internal fun interface NavigatorHandoff {
    fun request(onReady: (NavigationGuidanceSession) -> Unit, onError: () -> Unit)
}

class NavigationStopController internal constructor(
    private val guard: NavigationStartStopGuard,
    private val guidanceLifecycle: NavigationGuidanceLifecycle,
    private val clearOutput: () -> Unit,
    private val scope: CoroutineScope,
    private val handoff: NavigatorHandoff,
    private val handoffTimeout: Duration = NavigatorHandoffTimeout,
) {
    internal constructor(
        application: Application,
        guard: NavigationStartStopGuard,
        guidanceLifecycle: NavigationGuidanceLifecycle,
        clearOutput: () -> Unit,
        scope: CoroutineScope,
    ) : this(guard, guidanceLifecycle, clearOutput, scope, SdkNavigatorHandoff(application))

    /**
     * Stops guidance and clears the cluster, reporting the outcome to [onResult].
     *
     * The guard is claimed before anything suspends, so a caller that stops navigation and
     * then tears down the connection still has the stop registered by the time the teardown
     * begins — the ordering the handlebar and the disconnect path both rely on.
     *
     * [onResult] is not called when another request takes the stop over; that one owns the
     * outcome. See [stopAndAwait].
     */
    fun stop(onResult: (NavigationStopResult) -> Unit = {}) {
        val stopRequestId = guard.beginStop()
        if (stopRequestId == null) {
            onResult(NavigationStopResult.AlreadyStopping)
            return
        }
        scope.launch { completeStop(stopRequestId)?.let(onResult) }
    }

    /**
     * The same stop, for a caller that can suspend.
     *
     * Null means a newer request superseded this one before it finished. That is not a
     * failure and not a success: the request simply no longer owns the outcome, and reporting
     * anything would be reporting on a stop that something else is now responsible for.
     */
    suspend fun stopAndAwait(): NavigationStopResult? {
        val stopRequestId = guard.beginStop() ?: return NavigationStopResult.AlreadyStopping
        return completeStop(stopRequestId)
    }

    private suspend fun completeStop(stopRequestId: Long): NavigationStopResult? {
        val session = awaitSession()
        if (!guard.isCurrentStop(stopRequestId)) return null
        if (session == null) {
            guard.finishStop(stopRequestId)
            return NavigationStopResult.Failed
        }
        return finish(session, stopRequestId)
    }

    /**
     * The navigator, or null when the handoff failed or never answered.
     *
     * `getNavigator` makes no promise to call back at all, and a request that is never
     * answered used to leave `stopInProgress` set forever — after which every later press of
     * the handlebar EXIT button returned [NavigationStopResult.AlreadyStopping] and guidance
     * could no longer be stopped from anywhere. Hence the timeout.
     *
     * A late callback after the timeout is a no-op: the continuation is no longer active, so
     * exactly one answer is produced whichever of the two arrives first. That is what used to
     * need an `AtomicBoolean`, a `Handler` and three `removeCallbacks`.
     */
    private suspend fun awaitSession(): NavigationGuidanceSession? =
        withTimeoutOrNull(handoffTimeout) {
            suspendCancellableCoroutine { continuation ->
                fun settle(session: NavigationGuidanceSession?) {
                    if (continuation.isActive) continuation.resume(session)
                }
                try {
                    handoff.request(onReady = { settle(it) }, onError = { settle(null) })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // getNavigator can throw where it would otherwise have called back.
                    settle(null)
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
    private fun finish(session: NavigationGuidanceSession, stopRequestId: Long): NavigationStopResult {
        if (runCatching(session::stopGuidance).isFailure) {
            guard.finishStop(stopRequestId)
            return NavigationStopResult.Failed
        }
        guidanceLifecycle.release(session.identity)
        // The route is already stopped by this point, so a cleanup failure is worth reporting but
        // must not leave the cluster showing a route that is no longer running.
        val cleanupFailed = listOf(
            runCatching(session::unregisterServiceForNavUpdates),
            runCatching(session::cleanup),
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
        val NavigatorHandoffTimeout = 10.seconds
    }
}

/** The real handoff: the SDK's static request, wrapped in the session surface. */
private class SdkNavigatorHandoff(private val application: Application) : NavigatorHandoff {
    override fun request(onReady: (NavigationGuidanceSession) -> Unit, onError: () -> Unit) {
        NavigationApi.getNavigator(
            application,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) =
                    onReady(NavigatorGuidanceSession(navigator))

                override fun onError(errorCode: Int) = onError()
            },
        )
    }
}
