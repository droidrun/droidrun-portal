package com.mobilerun.portal.keepalive

import java.util.UUID

object KeepAliveProcessSession {
    val currentSessionId: String = UUID.randomUUID().toString()
}
