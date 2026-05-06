package com.mobilerun.portal.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class WebRtcManagerPrimarySignalingStateTest {
    @Test
    fun maybeSnapshotPendingPrimarySignalingForReset_preservesSameSessionPrePeerState() {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "v=0")
        val ice = listOf(IceCandidate("video", 0, "candidate:1 1 udp 1 127.0.0.1 9999 typ host"))

        val snapshot =
            WebRtcManager.maybeSnapshotPendingPrimarySignalingForReset(
                sessionId = "session-1",
                pendingSessionId = "session-1",
                offer = offer,
                ice = ice,
                hasLiveNegotiatedPeer = false,
            )

        assertNotNull(snapshot)
        assertEquals("session-1", snapshot?.sessionId)
        assertEquals(offer, snapshot?.offer)
        assertEquals(1, snapshot?.ice?.size)
    }

    @Test
    fun maybeSnapshotPendingPrimarySignalingForReset_rejectsLiveNegotiatedPeerState() {
        val snapshot =
            WebRtcManager.maybeSnapshotPendingPrimarySignalingForReset(
                sessionId = "session-1",
                pendingSessionId = "session-1",
                offer = SessionDescription(SessionDescription.Type.OFFER, "v=0"),
                ice = emptyList(),
                hasLiveNegotiatedPeer = true,
            )

        assertNull(snapshot)
    }

    @Test
    fun hasQueuedPrimaryOfferForSession_requiresMatchingSessionAndOffer() {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "v=0")

        assertTrue(
            WebRtcManager.hasQueuedPrimaryOfferForSession(
                sessionId = "session-1",
                pendingSessionId = "session-1",
                offer = offer,
            ),
        )
        assertFalse(
            WebRtcManager.hasQueuedPrimaryOfferForSession(
                sessionId = "session-1",
                pendingSessionId = "session-2",
                offer = offer,
            ),
        )
        assertFalse(
            WebRtcManager.hasQueuedPrimaryOfferForSession(
                sessionId = "session-1",
                pendingSessionId = "session-1",
                offer = null,
            ),
        )
    }
}
