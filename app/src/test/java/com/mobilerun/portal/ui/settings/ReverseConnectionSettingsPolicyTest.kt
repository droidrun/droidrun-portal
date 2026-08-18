package com.mobilerun.portal.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseConnectionSettingsPolicyTest {
    private val defaultUrl = "wss://api.mobilerun.ai/v1/providers/personal/join"

    @Test
    fun unchangedPersistenceDoesNotReconnectOrLiftHttp402Block() {
        assertFalse(
            ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                enabled = true,
                currentEffectiveUrl = defaultUrl,
                currentToken = "token",
                candidateUrl = defaultUrl,
                candidateToken = "token",
                defaultUrl = defaultUrl,
            ),
        )
    }

    @Test
    fun blankAndDefaultUrlsAreTheSameEffectiveEndpoint() {
        assertFalse(
            ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                enabled = true,
                currentEffectiveUrl = defaultUrl,
                currentToken = "token",
                candidateUrl = "",
                candidateToken = "token",
                defaultUrl = defaultUrl,
            ),
        )
    }

    @Test
    fun changedCredentialsOrEndpointReconnectOnlyWhileEnabled() {
        assertTrue(
            ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                enabled = true,
                currentEffectiveUrl = defaultUrl,
                currentToken = "old-token",
                candidateUrl = defaultUrl,
                candidateToken = "new-token",
                defaultUrl = defaultUrl,
            ),
        )
        assertFalse(
            ReverseConnectionSettingsPolicy.shouldReconnectAfterInputPersistence(
                enabled = false,
                currentEffectiveUrl = defaultUrl,
                currentToken = "old-token",
                candidateUrl = "wss://example.test/v1/providers/personal/join",
                candidateToken = "new-token",
                defaultUrl = defaultUrl,
            ),
        )
    }
}
