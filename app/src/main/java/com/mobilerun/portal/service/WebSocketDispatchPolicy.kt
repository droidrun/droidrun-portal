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
            isFastSignalingMethod(normalizedMethod) -> WebSocketDispatchBucket.SIGNALING
            else -> WebSocketDispatchBucket.COMMAND
        }
    }

    internal fun isFastSignalingMethod(normalizedMethod: String): Boolean {
        return when (normalizedMethod) {
            "stream/stop",
            "webrtc/answer",
            "webrtc/offer",
            "webrtc/ice",
            "webrtc/requestFrame",
            "webrtc/keepAlive" -> true
            else -> false
        }
    }
}
