package com.mobilerun.portal.taskprompt

object PortalAuthCallbackValidator {
    const val PENDING_WINDOW_MS = 10 * 60 * 1000L

    sealed class Result {
        data class Accepted(
            val sanitizedToken: String,
            val reverseConnectionUrl: String,
        ) : Result()

        data class Rejected(val message: String) : Result()
    }

    fun validate(
        token: String?,
        reverseConnectionUrl: String?,
        authPending: Boolean,
        defaultReverseConnectionUrl: String,
    ): Result {
        if (!authPending) {
            return Result.Rejected("Unexpected or expired login response")
        }

        val sanitizedToken = token?.replace("\\s+".toRegex(), "").orEmpty()
        if (sanitizedToken.isBlank()) {
            return Result.Rejected("Invalid connection token received")
        }

        val normalizedUrl = reverseConnectionUrl?.trim().orEmpty()
        if (normalizedUrl.isBlank()) {
            return Result.Rejected("Invalid connection URL received")
        }

        if (PortalCloudClient.deriveRestBaseUrl(normalizedUrl) == null) {
            return Result.Rejected("Unsupported connection URL received")
        }

        if (
            !PortalCloudClient.isOfficialMobilerunCloudConnection(
                reverseConnectionUrl = normalizedUrl,
                defaultReverseConnectionUrl = defaultReverseConnectionUrl,
            )
        ) {
            return Result.Rejected("Only official cloud login callbacks are accepted")
        }

        return Result.Accepted(
            sanitizedToken = sanitizedToken,
            reverseConnectionUrl = normalizedUrl,
        )
    }
}
