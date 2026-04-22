package com.mobilerun.portal.service

internal enum class WebSocketDispatchBucket {
    SIGNALING,
    COMMAND,
    INSTALL,
}

internal object WebSocketDispatchPolicy {
    fun bucketForNormalizedMethod(normalizedMethod: String): WebSocketDispatchBucket {
        return when {
            normalizedMethod == "install" -> WebSocketDispatchBucket.INSTALL
            isOrderedSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.SIGNALING
            else -> WebSocketDispatchBucket.COMMAND
        }
    }

    internal fun isOrderedSignalingMethod(normalizedMethod: String): Boolean =
        normalizedMethod.startsWith("stream/") || normalizedMethod.startsWith("webrtc/")
}
