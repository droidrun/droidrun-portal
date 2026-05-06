package com.mobilerun.portal.keepalive

class KeepAliveStartupException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)
