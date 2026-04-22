package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketDispatchPolicyTest {
    @Test
    fun bucketForNormalizedMethod_routesInstallCommandsToInstallExecutor() {
        assertEquals(
            WebSocketDispatchBucket.INSTALL,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("install"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_routesFastSignalingAwayFromHeavyExecutor() {
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/keepAlive"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/ice"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("stream/stop"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_keepsScreenshotAndStreamStartOnHeavyCommandExecutor() {
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("screenshot"),
        )
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("stream/start"),
        )
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/connect"),
        )
    }
}
