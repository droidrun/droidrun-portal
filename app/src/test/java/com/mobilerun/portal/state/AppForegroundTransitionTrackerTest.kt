package com.mobilerun.portal.state

import org.junit.Assert.assertEquals
import org.junit.Test

class AppForegroundTransitionTrackerTest {
    @Test
    fun firstActivityStartTriggersForegroundOnlyOnce() {
        val events = mutableListOf<String>()
        val tracker =
            AppForegroundTransitionTracker(
                onForeground = { events += "foreground" },
                onBackground = { events += "background" },
            )

        tracker.onActivityStarted()
        tracker.onActivityStarted()

        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun lastActivityStopTriggersBackground() {
        val events = mutableListOf<String>()
        val tracker =
            AppForegroundTransitionTracker(
                onForeground = { events += "foreground" },
                onBackground = { events += "background" },
            )

        tracker.onActivityStarted()
        tracker.onActivityStarted()
        tracker.onActivityStopped()
        tracker.onActivityStopped()

        assertEquals(listOf("foreground", "background"), events)
    }
}
