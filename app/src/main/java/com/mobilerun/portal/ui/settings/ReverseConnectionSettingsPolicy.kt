package com.mobilerun.portal.ui.settings

internal object ReverseConnectionSettingsPolicy {
    fun shouldReconnectAfterInputPersistence(
        enabled: Boolean,
        currentEffectiveUrl: String,
        currentToken: String,
        candidateUrl: String,
        candidateToken: String,
        defaultUrl: String,
    ): Boolean {
        if (!enabled) return false
        val candidateEffectiveUrl = candidateUrl.ifBlank { defaultUrl }
        return candidateEffectiveUrl != currentEffectiveUrl || candidateToken != currentToken
    }
}
