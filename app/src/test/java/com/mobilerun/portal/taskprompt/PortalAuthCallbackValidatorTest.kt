package com.mobilerun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalAuthCallbackValidatorTest {

    private val defaultReverseConnectionUrl =
        "wss://api.mobilerun.ai/v1/providers/personal/join"

    @Test
    fun buildsCloudLoginUrlWithPreferredDroidrunScheme() {
        val url = PortalAuthDeepLink.buildCloudLoginUrl(
            deviceId = "device-123",
            forceLogin = false,
        )

        assertEquals(
            "https://cloud.mobilerun.ai/auth/device?deviceId=device-123&scheme=droidrun",
            url,
        )
    }

    @Test
    fun buildsCloudLoginUrlWithForceLoginWhenRequested() {
        val url = PortalAuthDeepLink.buildCloudLoginUrl(
            deviceId = "device-123",
            forceLogin = true,
        )

        assertEquals(
            "https://cloud.mobilerun.ai/auth/device?deviceId=device-123&scheme=droidrun&force_login=true",
            url,
        )
    }

    @Test
    fun matchesPreferredAndLegacyAuthCallbackSchemes() {
        assertTrue(PortalAuthDeepLink.isAuthCallback("droidrun", "auth-callback"))
        assertTrue(PortalAuthDeepLink.isAuthCallback("mobilerun", "auth-callback"))
    }

    @Test
    fun rejectsUnknownAuthCallbackSchemeOrHost() {
        assertFalse(PortalAuthDeepLink.isAuthCallback("https", "auth-callback"))
        assertFalse(PortalAuthDeepLink.isAuthCallback("droidrun", "unexpected"))
        assertFalse(PortalAuthDeepLink.isAuthCallback(null, "auth-callback"))
        assertFalse(PortalAuthDeepLink.isAuthCallback("droidrun", null))
    }

    @Test
    fun acceptsPendingOfficialCallbackAndSanitizesToken() {
        val result = PortalAuthCallbackValidator.validate(
            token = "  token 123  ",
            reverseConnectionUrl = "wss://cloud.mobilerun.ai/v1/providers/personal/join",
            authPending = true,
            defaultReverseConnectionUrl = defaultReverseConnectionUrl,
        )

        assertTrue(result is PortalAuthCallbackValidator.Result.Accepted)
        result as PortalAuthCallbackValidator.Result.Accepted
        assertEquals("token123", result.sanitizedToken)
        assertEquals(
            "wss://cloud.mobilerun.ai/v1/providers/personal/join",
            result.reverseConnectionUrl,
        )
    }

    @Test
    fun rejectsCallbackWhenPendingWindowIsMissing() {
        val result = PortalAuthCallbackValidator.validate(
            token = "token-123",
            reverseConnectionUrl = "wss://cloud.mobilerun.ai/v1/providers/personal/join",
            authPending = false,
            defaultReverseConnectionUrl = defaultReverseConnectionUrl,
        )

        assertEquals(
            PortalAuthCallbackValidator.Result.Rejected("Unexpected or expired login response"),
            result,
        )
    }

    @Test
    fun rejectsNonOfficialCallbackUrl() {
        val result = PortalAuthCallbackValidator.validate(
            token = "token-123",
            reverseConnectionUrl = "wss://portal.example.com/v1/providers/personal/join",
            authPending = true,
            defaultReverseConnectionUrl = defaultReverseConnectionUrl,
        )

        assertEquals(
            PortalAuthCallbackValidator.Result.Rejected(
                "Only official cloud login callbacks are accepted",
            ),
            result,
        )
    }
}
