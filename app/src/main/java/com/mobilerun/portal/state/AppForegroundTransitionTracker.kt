package com.mobilerun.portal.state

class AppForegroundTransitionTracker(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) {
    private var startedActivityCount = 0

    fun onActivityStarted() {
        val wasBackground = startedActivityCount == 0
        startedActivityCount += 1
        if (wasBackground) {
            onForeground()
        }
    }

    fun onActivityStopped() {
        if (startedActivityCount == 0) {
            return
        }
        startedActivityCount -= 1
        if (startedActivityCount == 0) {
            onBackground()
        }
    }
}
