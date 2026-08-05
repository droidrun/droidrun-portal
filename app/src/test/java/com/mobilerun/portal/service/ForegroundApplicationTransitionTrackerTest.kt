package com.mobilerun.portal.service

import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ForegroundApplicationTransitionTrackerTest {
    @Test
    fun firstConfirmedApplicationEmitsOnlyEnteredAndSuppressesDuplicates() {
        val events = mutableListOf<PortalEvent>()
        val tracker = ForegroundApplicationTransitionTracker(events::add)

        tracker.advance(null)
        tracker.advance("")
        tracker.advance("  $PORTAL  ")
        tracker.advance(PORTAL)
        tracker.advance(null)
        tracker.advance(PORTAL)

        assertEquals(listOf(EventType.APP_ENTERED), events.map(PortalEvent::type))
        val payload = events.single().payload as JSONObject
        assertEquals(PORTAL, payload.getString("package"))
        assertFalse(payload.has("previous_package"))
        assertFalse(payload.has("next_package"))
    }

    @Test
    fun realApplicationChangeEmitsExitedThenEnteredWithExistingPayloadSchema() {
        val events = mutableListOf<PortalEvent>()
        val tracker = ForegroundApplicationTransitionTracker(events::add)
        tracker.advance(PORTAL)
        events.clear()

        tracker.advance(SETTINGS)

        assertEquals(
            listOf(EventType.APP_EXITED, EventType.APP_ENTERED),
            events.map(PortalEvent::type),
        )
        val exited = events[0].payload as JSONObject
        assertEquals(PORTAL, exited.getString("package"))
        assertEquals(SETTINGS, exited.getString("next_package"))
        assertFalse(exited.has("previous_package"))

        val entered = events[1].payload as JSONObject
        assertEquals(SETTINGS, entered.getString("package"))
        assertEquals(PORTAL, entered.getString("previous_package"))
        assertFalse(entered.has("next_package"))
    }

    @Test
    fun rejectedSystemUiEvidenceDoesNotLoseStateBeforeRealTransition() {
        val events = mutableListOf<PortalEvent>()
        val tracker = ForegroundApplicationTransitionTracker(events::add)

        tracker.advance(PORTAL)
        tracker.advance(null) // Notification shade / SystemUI is not application evidence.
        tracker.advance(PORTAL)

        assertEquals(listOf(EventType.APP_ENTERED), events.map(PortalEvent::type))

        tracker.advance(SETTINGS)

        assertEquals(
            listOf(
                EventType.APP_ENTERED,
                EventType.APP_EXITED,
                EventType.APP_ENTERED,
            ),
            events.map(PortalEvent::type),
        )
        val exited = events[1].payload as JSONObject
        assertEquals(PORTAL, exited.getString("package"))
        assertEquals(SETTINGS, exited.getString("next_package"))
    }

    private companion object {
        const val PORTAL = "com.mobilerun.portal"
        const val SETTINGS = "com.android.settings"
    }
}
