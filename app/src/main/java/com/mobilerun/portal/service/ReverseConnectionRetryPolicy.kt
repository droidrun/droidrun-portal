package com.mobilerun.portal.service

import com.mobilerun.portal.config.ReverseJoinHttp402Snapshot
import com.mobilerun.portal.state.ConnectionState

internal sealed interface ReverseConnectionRetryDecision {
    data object Retry : ReverseConnectionRetryDecision

    data class Stop(
        val state: ConnectionState,
        val blockAutomaticRetries: Boolean = false,
    ) : ReverseConnectionRetryDecision
}

internal sealed interface ReverseConnectionStartDecision {
    data object Connect : ReverseConnectionStartDecision

    data class RestoreHttp402Block(
        val state: ConnectionState,
    ) : ReverseConnectionStartDecision
}

internal fun http402BlockedPresentationState(
    blocked: Boolean,
    explicitlyDisconnected: Boolean,
): ConnectionState? {
    if (!blocked) return null
    return if (explicitlyDisconnected) {
        ConnectionState.DISCONNECTED
    } else {
        ConnectionState.LIMIT_EXCEEDED
    }
}

internal fun performExplicitReverseConnectionDisconnect(
    markExplicitlyDisconnected: () -> Unit,
    publishDisconnected: (ConnectionState) -> Unit,
    dispatchDisconnect: () -> Unit,
) {
    markExplicitlyDisconnected()
    publishDisconnected(ConnectionState.DISCONNECTED)
    dispatchDisconnect()
}

internal class ReverseConnectionRetryController(
    private val readHttp402Snapshot: () -> ReverseJoinHttp402Snapshot,
    private val setHttp402Blocked: (Boolean) -> Unit,
    private val markExplicitlyDisconnected: () -> Unit = {},
) {
    fun onStart(explicitReconnect: Boolean): ReverseConnectionStartDecision {
        if (explicitReconnect) {
            setHttp402Blocked(false)
            return ReverseConnectionStartDecision.Connect
        }
        val blockedState = blockedPresentationStateOrNull()
        return blockedState?.let(ReverseConnectionStartDecision::RestoreHttp402Block)
            ?: ReverseConnectionStartDecision.Connect
    }

    fun onConnected() {
        setHttp402Blocked(false)
    }

    fun onExplicitDisconnect() {
        if (readHttp402Snapshot().blocked) {
            markExplicitlyDisconnected()
        }
    }

    fun blockedPresentationStateOrNull(): ConnectionState? {
        val snapshot = readHttp402Snapshot()
        return http402BlockedPresentationState(
            blocked = snapshot.blocked,
            explicitlyDisconnected = snapshot.explicitlyDisconnected,
        )
    }

    fun onClose(
        reason: String?,
        cancelPendingReconnects: () -> Unit,
    ): ReverseConnectionRetryDecision {
        val decision = ReverseConnectionRetryPolicy.decisionForClose(reason)
        if (decision is ReverseConnectionRetryDecision.Stop) {
            if (decision.blockAutomaticRetries) {
                // Persist before cancellation so any concurrently delivered callback sees the block.
                setHttp402Blocked(true)
            }
            cancelPendingReconnects()
        }
        return decision
    }
}

internal object ReverseConnectionRetryPolicy {
    private val javaWebSocketStatus = Regex(
        pattern = "Invalid status code received:\\s*(\\d{3})(?:\\b|$)",
        option = RegexOption.IGNORE_CASE,
    )
    private val legacyLeadingStatus = Regex("^\\s*(\\d{3})(?:\\b|$)")

    fun decisionForClose(reason: String?): ReverseConnectionRetryDecision {
        if (reason == null) return ReverseConnectionRetryDecision.Retry

        return when (extractHttpStatus(reason)) {
            400 -> stop(ConnectionState.BAD_REQUEST)
            401 -> stop(ConnectionState.UNAUTHORIZED)
            402 -> stop(ConnectionState.LIMIT_EXCEEDED, blockAutomaticRetries = true)
            403 -> stop(ConnectionState.LIMIT_EXCEEDED)
            null -> decisionForLegacyReason(reason)
            else -> ReverseConnectionRetryDecision.Retry
        }
    }

    private fun extractHttpStatus(reason: String): Int? {
        return javaWebSocketStatus.find(reason)?.groupValues?.get(1)?.toIntOrNull()
            ?: legacyLeadingStatus.find(reason)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun decisionForLegacyReason(reason: String): ReverseConnectionRetryDecision {
        return when {
            reason.contains("Unauthorized", ignoreCase = true) ->
                stop(ConnectionState.UNAUTHORIZED)

            reason.contains("Forbidden", ignoreCase = true) ->
                stop(ConnectionState.LIMIT_EXCEEDED)

            reason.contains("Bad Request", ignoreCase = true) ->
                stop(ConnectionState.BAD_REQUEST)

            else -> ReverseConnectionRetryDecision.Retry
        }
    }

    private fun stop(
        state: ConnectionState,
        blockAutomaticRetries: Boolean = false,
    ): ReverseConnectionRetryDecision.Stop {
        return ReverseConnectionRetryDecision.Stop(state, blockAutomaticRetries)
    }
}
