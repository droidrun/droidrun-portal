package com.mobilerun.portal.service

internal enum class WebSocketDispatchBucket {
    SIGNALING,
    LIGHTWEIGHT,
    COMMAND,
    INSTALL,
}

internal object WebSocketDispatchPolicy {
    fun bucketForNormalizedMethod(normalizedMethod: String): WebSocketDispatchBucket {
        return when {
            normalizedMethod == "install" -> WebSocketDispatchBucket.INSTALL
            isLightweightSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.LIGHTWEIGHT
            isOrderedSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.SIGNALING
            else -> WebSocketDispatchBucket.COMMAND
        }
    }

    internal fun shouldTraceExecutionTiming(normalizedMethod: String): Boolean =
        normalizedMethod == "state" ||
            normalizedMethod == "packages" ||
            normalizedMethod == "screenshot" ||
            normalizedMethod == "webrtc/rtcConfiguration" ||
            normalizedMethod == "webrtc/requestFrame"

    internal fun isOrderedSignalingMethod(normalizedMethod: String): Boolean =
        normalizedMethod.startsWith("stream/") || normalizedMethod.startsWith("webrtc/")

    private fun isLightweightSignalingMethod(normalizedMethod: String): Boolean =
        normalizedMethod == "webrtc/requestFrame" ||
                normalizedMethod == "webrtc/keepAlive"
}
