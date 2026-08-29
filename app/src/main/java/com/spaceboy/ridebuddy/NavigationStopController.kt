package com.spaceboy.ridebuddy

import android.app.Application
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator

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
    fun stop(onResult: (NavigationStopResult) -> Unit = {}) {
        val stopRequestId = guard.beginStop()
        if (stopRequestId == null) {
            onResult(NavigationStopResult.AlreadyStopping)
            return
        }
        val started = runCatching {
            NavigationApi.getNavigator(
                application,
                object : NavigationApi.NavigatorListener {
                    override fun onNavigatorReady(navigator: Navigator) {
                        if (!guard.isCurrentStop(stopRequestId)) return
                        onResult(finish(navigator, stopRequestId))
                    }

                    override fun onError(errorCode: Int) {
                        if (!guard.isCurrentStop(stopRequestId)) return
                        guard.finishStop(stopRequestId)
                        onResult(NavigationStopResult.Failed)
                    }
                },
            )
        }.isSuccess
        if (!started && guard.isCurrentStop(stopRequestId)) {
            guard.finishStop(stopRequestId)
            onResult(NavigationStopResult.Failed)
        }
    }

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
}
