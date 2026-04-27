package com.mobilerun.portal.taskprompt

import java.net.URLEncoder

object PortalAuthDeepLink {
    const val CALLBACK_HOST = "auth-callback"
    const val PREFERRED_CALLBACK_SCHEME = "droidrun"

    private const val LEGACY_CALLBACK_SCHEME = "mobilerun"
    private const val CLOUD_AUTH_DEVICE_URL = "https://cloud.mobilerun.ai/auth/device"
    private val ACCEPTED_CALLBACK_SCHEMES =
        setOf(PREFERRED_CALLBACK_SCHEME, LEGACY_CALLBACK_SCHEME)

    fun buildCloudLoginUrl(deviceId: String, forceLogin: Boolean): String {
        val encodedDeviceId = URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        return buildString {
            append(CLOUD_AUTH_DEVICE_URL)
            append("?deviceId=")
            append(encodedDeviceId)
            append("&scheme=")
            append(PREFERRED_CALLBACK_SCHEME)
            if (forceLogin) {
                append("&force_login=true")
            }
        }
    }

    fun isAuthCallback(scheme: String?, host: String?): Boolean {
        return scheme in ACCEPTED_CALLBACK_SCHEMES && host == CALLBACK_HOST
    }
}
