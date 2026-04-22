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
    fun bucketForNormalizedMethod_routesAllStreamAndWebRtcMethodsToOrderedSignalingExecutor() {
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("stream/start"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("stream/stop"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/connect"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/rtcConfiguration"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/offer"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/answer"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/ice"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/requestFrame"),
        )
        assertEquals(
            WebSocketDispatchBucket.SIGNALING,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("webrtc/keepAlive"),
        )
    }

    @Test
    fun bucketForNormalizedMethod_keepsHeavyCommandsOnCommandExecutor() {
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("screenshot"),
        )
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("task/start"),
        )
        assertEquals(
            WebSocketDispatchBucket.COMMAND,
            WebSocketDispatchPolicy.bucketForNormalizedMethod("files/list"),
        )
    }
}
