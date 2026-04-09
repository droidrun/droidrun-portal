package com.droidrun.portal.keepalive

import java.util.UUID

object KeepAliveProcessSession {
    val currentSessionId: String = UUID.randomUUID().toString()
}
