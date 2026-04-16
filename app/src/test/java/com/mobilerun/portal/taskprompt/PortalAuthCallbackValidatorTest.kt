package com.mobilerun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalAuthCallbackValidatorTest {

    private val defaultReverseConnectionUrl =
        "wss://api.mobilerun.ai/v1/providers/personal/join"

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
