package com.mobilerun.portal.streaming

import java.util.concurrent.CompletableFuture
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
    fun evaluateSessionLivenessTimeout_keepsHealthyPeerAlive() {
        val decision = WebRtcManager.evaluateSessionLivenessTimeout(peerHealthy = true)

        assertFalse(decision.shouldStop)
    }

    @Test
    fun evaluateSessionLivenessTimeout_stopsImmediatelyWhenPeerIsUnhealthy() {
        val decision = WebRtcManager.evaluateSessionLivenessTimeout(peerHealthy = false)

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
    fun buildCaptureFastState_marksReusableSharedCaptureWithoutSessionLockState() {
        val state =
            WebRtcManager.buildCaptureFastState(
                captureActive = true,
                videoTrackReady = true,
                captureSessionMode = WebRtcManager.CaptureSessionMode.STREAM,
                generation = 42,
            )

        assertTrue(state.captureActive)
        assertTrue(state.reusableFrameSource)
        assertEquals(WebRtcManager.CaptureSessionMode.STREAM, state.captureSessionMode)
        assertEquals(42, state.generation)
    }

    @Test
    fun buildCaptureFastState_clearsReusableStateWhenCaptureIsReset() {
        val state =
            WebRtcManager.buildCaptureFastState(
                captureActive = false,
                videoTrackReady = true,
                captureSessionMode = WebRtcManager.CaptureSessionMode.STREAM,
                generation = 42,
            )

        assertFalse(state.captureActive)
        assertFalse(state.reusableFrameSource)
        assertEquals(WebRtcManager.CaptureSessionMode.NONE, state.captureSessionMode)
        assertEquals(0, state.generation)
    }

    @Test
    fun planCaptureFrameRequest_reusesFastCaptureStateWithoutNeedingStreamLock() {
        val requestPlan =
            WebRtcManager.planCaptureFrameRequest(
                WebRtcManager.buildCaptureFastState(
                    captureActive = true,
                    videoTrackReady = true,
                    captureSessionMode = WebRtcManager.CaptureSessionMode.STREAM,
                    generation = 7,
                ),
            )

        assertTrue(requestPlan.reusableCaptureAvailable)
        assertFalse(requestPlan.shouldCancelIdleStop)
    }

    @Test
    fun planCaptureFrameRequest_captureOnlyReuseCancelsIdleStop() {
        val requestPlan =
            WebRtcManager.planCaptureFrameRequest(
                WebRtcManager.buildCaptureFastState(
                    captureActive = true,
                    videoTrackReady = true,
                    captureSessionMode = WebRtcManager.CaptureSessionMode.CAPTURE_ONLY,
                    generation = 7,
                ),
            )

        assertTrue(requestPlan.reusableCaptureAvailable)
        assertTrue(requestPlan.shouldCancelIdleStop)
    }

    @Test
    fun resolveIncomingSessionRoute_takesOverWhenLivenessIsStale() {
        assertEquals(
            WebRtcManager.IncomingSessionRoute.TAKEOVER_STALE_PRIMARY,
            WebRtcManager.resolveIncomingSessionRoute(
                currentPrimarySessionId = "primary",
                incomingSessionId = "replacement",
                primaryHasPeerResources = true,
                primaryLastLivenessAtMs = 1_000L,
                nowMs = 301_000L,
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
                primaryLastLivenessAtMs = 290_000L,
                nowMs = 300_000L,
                livenessStaleAfterMs = 300_000L,
            ),
        )
    }

    @Test
    fun planKeyframeRequestSchedule_coalescesBurstForSameSessionAndGeneration() {
        assertEquals(
            WebRtcManager.Companion.KeyframeRequestScheduleDisposition.COALESCE,
            WebRtcManager.planKeyframeRequestSchedule(
                streamActive = true,
                pendingGeneration = 7,
                pendingSessionId = "session-a",
                currentGeneration = 7,
                requestedSessionId = "session-a",
                nowMs = 1_000L,
                lastKeyframeRequestStartedAtMs = 900L,
                minIntervalMs = 250L,
            ).disposition,
        )
    }

    @Test
    fun planKeyframeExecution_skipsWorkWhenGenerationChanges() {
        assertEquals(
            WebRtcManager.Companion.KeyframeExecutionDisposition.SKIP_STALE,
            WebRtcManager.planKeyframeExecution(
                pendingGeneration = 7,
                pendingSessionId = "session-a",
                expectedGeneration = 7,
                expectedSessionId = "session-a",
                activeGeneration = 8,
                trackedSession = true,
                streamActive = true,
            ),
        )
    }

    @Test
    fun executeKeyframeTargetActions_continuesPastFailures() {
        val summary =
            WebRtcManager.executeKeyframeTargetActions(
                listOf(
                    WebRtcManager.Companion.KeyframeTargetAction(label = "primary") {},
                    WebRtcManager.Companion.KeyframeTargetAction(label = "secondary") {
                        error("boom")
                    },
                    WebRtcManager.Companion.KeyframeTargetAction(label = "fallback") {},
                ),
            )

        assertEquals(3, summary.attempted)
        assertEquals(2, summary.succeeded)
        assertEquals(1, summary.failed)
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
