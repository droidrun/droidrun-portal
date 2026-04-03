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
    fun evaluateSessionLivenessTimeout_keepsHealthyPeerAliveWithinThirtyMinuteGrace() {
        val decision =
            WebRtcManager.evaluateSessionLivenessTimeout(
                peerHealthy = true,
                nowMs = 1_799_000L,
                firstSilentAtMs = 30_000L,
                healthyGraceMs = 1_800_000L,
            )

        assertFalse(decision.shouldStop)
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

    @Test
    fun lastSessionCaptureAction_keepAliveTimeout_defersCaptureStopToIdleTimeout() {
        assertEquals(
            WebRtcManager.LastSessionCaptureAction.SCHEDULE_IDLE_STOP,
            WebRtcManager.lastSessionCaptureAction(
                reason = "keep_alive_timeout",
                captureActive = true,
            ),
        )
    }

    @Test
    fun lastSessionCaptureAction_withoutActiveCapture_doesNothing() {
        assertEquals(
            WebRtcManager.LastSessionCaptureAction.NONE,
            WebRtcManager.lastSessionCaptureAction(
                reason = "keep_alive_timeout",
                captureActive = false,
            ),
        )
    }

    @Test
    fun resolveIncomingSessionRoute_takesOverWhenLivenessIsStale() {
        assertEquals(
            WebRtcManager.IncomingSessionRoute.TAKEOVER_STALE_PRIMARY,
            WebRtcManager.resolveIncomingSessionRoute(
                currentPrimarySessionId = "primary",
                incomingSessionId = "replacement",
                primaryHasPeerResources = true,
                primaryFirstSilentAtMs = null,
                primaryLastLivenessAtMs = 1_000L,
                nowMs = 301_000L,
                livenessStaleAfterMs = 300_000L,
            ),
        )
    }

    @Test
    fun resolveIncomingSessionRoute_takesOverWhenPrimaryIsAlreadySilent() {
        assertEquals(
            WebRtcManager.IncomingSessionRoute.TAKEOVER_STALE_PRIMARY,
            WebRtcManager.resolveIncomingSessionRoute(
                currentPrimarySessionId = "primary",
                incomingSessionId = "replacement",
                primaryHasPeerResources = true,
                primaryFirstSilentAtMs = 123L,
                primaryLastLivenessAtMs = 200_000L,
                nowMs = 250_000L,
                livenessStaleAfterMs = 300_000L,
            ),
        )
    }

    @Test
    fun resolveIncomingSessionRoute_keepsHealthyPrimaryAsSecondaryWithRecentKeepalive() {
        assertEquals(
            WebRtcManager.IncomingSessionRoute.SECONDARY,
            WebRtcManager.resolveIncomingSessionRoute(
                currentPrimarySessionId = "primary",
                incomingSessionId = "secondary",
                primaryHasPeerResources = true,
                primaryFirstSilentAtMs = null,
                primaryLastLivenessAtMs = 290_000L,
                nowMs = 300_000L,
                livenessStaleAfterMs = 300_000L,
            ),
        )
    }

    @Test
    fun resolveIncomingSessionRoute_takesOverWhenPrimaryHasNoPeerResources() {
        assertEquals(
            WebRtcManager.IncomingSessionRoute.TAKEOVER_STALE_PRIMARY,
            WebRtcManager.resolveIncomingSessionRoute(
                currentPrimarySessionId = "primary",
                incomingSessionId = "replacement",
                primaryHasPeerResources = false,
                primaryFirstSilentAtMs = null,
                primaryLastLivenessAtMs = 290_000L,
                nowMs = 300_000L,
                livenessStaleAfterMs = 300_000L,
            ),
        )
    }
}
