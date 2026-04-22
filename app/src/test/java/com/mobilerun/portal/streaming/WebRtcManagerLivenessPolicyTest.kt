package com.mobilerun.portal.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.util.concurrent.CompletableFuture

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
    fun shouldArmCaptureOnlyIdleStop_whenSharedScreenshotBootstrapsCaptureOnlySession() {
        assertTrue(
            WebRtcManager.shouldArmCaptureOnlyIdleStop(
                usedSharedCaptureSession = true,
                captureSessionMode = WebRtcManager.CaptureSessionMode.CAPTURE_ONLY,
                captureActive = true,
                streamActive = false,
            ),
        )
    }

    @Test
    fun shouldArmCaptureOnlyIdleStop_whenScreenshotReusesActiveStreamCapture() {
        assertFalse(
            WebRtcManager.shouldArmCaptureOnlyIdleStop(
                usedSharedCaptureSession = true,
                captureSessionMode = WebRtcManager.CaptureSessionMode.STREAM,
                captureActive = true,
                streamActive = true,
            ),
        )
    }

    @Test
    fun shouldArmCaptureOnlyIdleStop_whenSharedScreenshotFailsAfterBootstrap() {
        assertTrue(
            WebRtcManager.shouldArmCaptureOnlyIdleStop(
                usedSharedCaptureSession = true,
                captureSessionMode = WebRtcManager.CaptureSessionMode.CAPTURE_ONLY,
                captureActive = true,
                streamActive = false,
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

    @Test
    fun completeFrameCaptureWaiters_completesAllConcurrentRequestsWithSameSnapshotResult() {
        val waiterOne = CompletableFuture<String>()
        val waiterTwo = CompletableFuture<String>()

        val completed =
            WebRtcManager.completeFrameCaptureWaiters(
                listOf(waiterOne, waiterTwo),
                "snapshot-base64",
            )

        assertEquals(2, completed)
        assertEquals("snapshot-base64", waiterOne.get())
        assertEquals("snapshot-base64", waiterTwo.get())
    }

    @Test
    fun failFrameCaptureWaiters_failsAllPendingRequestsOnCaptureCleanup() {
        val waiterOne = CompletableFuture<String>()
        val waiterTwo = CompletableFuture<String>()

        val completed =
            WebRtcManager.failFrameCaptureWaiters(
                listOf(waiterOne, waiterTwo),
                "capture_stopped",
            )

        assertEquals(2, completed)
        assertEquals("error: capture_stopped", waiterOne.get())
        assertEquals("error: capture_stopped", waiterTwo.get())
    }

    @Test
    fun nextZeroSentStatsIntervalCount_warnsAfterTwoConnectedZeroFrameIntervals() {
        val first =
            WebRtcManager.nextZeroSentStatsIntervalCount(
                currentCount = 0,
                iceState = PeerConnection.IceConnectionState.CONNECTED,
                framesSent = 0L,
            )
        val second =
            WebRtcManager.nextZeroSentStatsIntervalCount(
                currentCount = first,
                iceState = PeerConnection.IceConnectionState.CONNECTED,
                framesSent = 0L,
            )

        assertEquals(1, first)
        assertEquals(2, second)
        assertTrue(WebRtcManager.shouldWarnForZeroSentStats(second))
    }

    @Test
    fun nextZeroSentStatsIntervalCount_resetsWhenFramesFlowAgain() {
        val connectedSilence =
            WebRtcManager.nextZeroSentStatsIntervalCount(
                currentCount = 1,
                iceState = PeerConnection.IceConnectionState.CONNECTED,
                framesSent = 0L,
            )
        val withFrames =
            WebRtcManager.nextZeroSentStatsIntervalCount(
                currentCount = connectedSilence,
                iceState = PeerConnection.IceConnectionState.CONNECTED,
                framesSent = 3L,
            )

        assertEquals(2, connectedSilence)
        assertEquals(0, withFrames)
        assertFalse(WebRtcManager.shouldWarnForZeroSentStats(withFrames))
    }
}
