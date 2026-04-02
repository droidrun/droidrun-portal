package com.droidrun.portal.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.DataChannel
import org.webrtc.PeerConnection

class WebRtcManagerLivenessPolicyTest {
    @Test
    fun isPeerHealthyForLiveness_acceptsConnectedIce() {
        assertTrue(
            WebRtcManager.isPeerHealthyForLiveness(
                PeerConnection.IceConnectionState.CONNECTED,
                null,
            ),
        )
    }

    @Test
    fun isPeerHealthyForLiveness_acceptsOpenControlChannel() {
        assertTrue(
            WebRtcManager.isPeerHealthyForLiveness(
                PeerConnection.IceConnectionState.DISCONNECTED,
                DataChannel.State.OPEN,
            ),
        )
    }

    @Test
    fun evaluateSessionLivenessTimeout_startsGraceWindowForHealthyPeer() {
        val decision =
            WebRtcManager.evaluateSessionLivenessTimeout(
                peerHealthy = true,
                nowMs = 30_000L,
                firstSilentAtMs = null,
                healthyGraceMs = 300_000L,
            )

        assertFalse(decision.shouldStop)
        assertEquals(30_000L, decision.firstSilentAtMs)
    }

    @Test
    fun evaluateSessionLivenessTimeout_stopsAfterGraceWindowExpires() {
        val decision =
            WebRtcManager.evaluateSessionLivenessTimeout(
                peerHealthy = true,
                nowMs = 331_000L,
                firstSilentAtMs = 30_000L,
                healthyGraceMs = 300_000L,
            )

        assertTrue(decision.shouldStop)
        assertEquals(30_000L, decision.firstSilentAtMs)
    }

    @Test
    fun evaluateSessionLivenessTimeout_stopsImmediatelyWhenPeerIsUnhealthy() {
        val decision =
            WebRtcManager.evaluateSessionLivenessTimeout(
                peerHealthy = false,
                nowMs = 30_000L,
                firstSilentAtMs = null,
                healthyGraceMs = 300_000L,
            )

        assertTrue(decision.shouldStop)
    }
}
