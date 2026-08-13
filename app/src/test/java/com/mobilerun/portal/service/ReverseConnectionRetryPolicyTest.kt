package com.mobilerun.portal.service

import com.mobilerun.portal.config.ReverseJoinHttp402Snapshot
import com.mobilerun.portal.state.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseConnectionRetryPolicyTest {
    @Test
    fun javaWebSocketHttp402_stopsAndBlocksAutomaticRetries() {
        val reason =
            "Invalid status code received: 402 Status line: HTTP/1.1 402 Payment Required"

        assertEquals(
            ReverseConnectionRetryDecision.Stop(
                state = ConnectionState.LIMIT_EXCEEDED,
                blockAutomaticRetries = true,
            ),
            ReverseConnectionRetryPolicy.decisionForClose(reason),
        )
    }

    @Test
    fun legacyLeadingHttp402_stopsAndBlocksAutomaticRetries() {
        assertEquals(
            ReverseConnectionRetryDecision.Stop(
                state = ConnectionState.LIMIT_EXCEEDED,
                blockAutomaticRetries = true,
            ),
            ReverseConnectionRetryPolicy.decisionForClose("402 Payment Required"),
        )
    }

    @Test
    fun terminalHandshakeStatuses_preserveExistingStateMappings() {
        assertEquals(
            ReverseConnectionRetryDecision.Stop(ConnectionState.BAD_REQUEST),
            ReverseConnectionRetryPolicy.decisionForClose(
                "Invalid status code received: 400 Bad Request",
            ),
        )
        assertEquals(
            ReverseConnectionRetryDecision.Stop(ConnectionState.UNAUTHORIZED),
            ReverseConnectionRetryPolicy.decisionForClose("401 Unauthorized"),
        )
        assertEquals(
            ReverseConnectionRetryDecision.Stop(ConnectionState.LIMIT_EXCEEDED),
            ReverseConnectionRetryPolicy.decisionForClose("403 Forbidden"),
        )
    }

    @Test
    fun transientAndUnknownFailures_remainRetryable() {
        listOf(
            "409 Conflict",
            "429 Too Many Requests",
            "Invalid status code received: 503 Service Unavailable",
            "connection reset",
            null,
        ).forEach { reason ->
            assertEquals(
                ReverseConnectionRetryDecision.Retry,
                ReverseConnectionRetryPolicy.decisionForClose(reason),
            )
        }
    }

    @Test
    fun explicitReconnectAllowsOneAttemptAndRepeatedHttp402RestoresBlock() {
        var blocked = true
        val controller = ReverseConnectionRetryController(
            readHttp402Snapshot = {
                ReverseJoinHttp402Snapshot(blocked, explicitlyDisconnected = false)
            },
            setHttp402Blocked = { blocked = it },
        )

        assertEquals(
            ReverseConnectionStartDecision.RestoreHttp402Block(
                ConnectionState.LIMIT_EXCEEDED,
            ),
            controller.onStart(explicitReconnect = false),
        )

        assertEquals(
            ReverseConnectionStartDecision.Connect,
            controller.onStart(explicitReconnect = true),
        )
        assertFalse(blocked)

        controller.onClose("402 Payment Required") {}

        assertTrue(blocked)
        assertEquals(
            ReverseConnectionStartDecision.RestoreHttp402Block(
                ConnectionState.LIMIT_EXCEEDED,
            ),
            controller.onStart(explicitReconnect = false),
        )
    }

    @Test
    fun http402PersistsBlockBeforeCancelingAlreadyQueuedRetry() {
        var blocked = false
        val transitionOrder = mutableListOf<String>()
        val reconnectQueue = ReverseWebSocketGenerationGate().apply {
            markReconnectScheduled(current())
        }
        val controller = ReverseConnectionRetryController(
            readHttp402Snapshot = {
                ReverseJoinHttp402Snapshot(blocked, explicitlyDisconnected = false)
            },
            setHttp402Blocked = {
                blocked = it
                transitionOrder += "persist"
            },
        )

        val decision = controller.onClose(
            "Invalid status code received: 402 Payment Required",
        ) {
            transitionOrder += "cancel"
            reconnectQueue.clearReconnect()
        }

        assertEquals(
            ReverseConnectionRetryDecision.Stop(
                state = ConnectionState.LIMIT_EXCEEDED,
                blockAutomaticRetries = true,
            ),
            decision,
        )
        assertEquals(listOf("persist", "cancel"), transitionOrder)
        assertTrue(blocked)
        assertNull(reconnectQueue.reconnectOwner())
    }

    @Test
    fun successfulConnectionClearsPersistedHttp402Block() {
        var blocked = true
        val controller = ReverseConnectionRetryController(
            readHttp402Snapshot = {
                ReverseJoinHttp402Snapshot(blocked, explicitlyDisconnected = false)
            },
            setHttp402Blocked = { blocked = it },
        )

        controller.onConnected()

        assertFalse(blocked)
    }

    @Test
    fun explicitDisconnectRetainsBlockButPresentsDisconnectedUntilOneShotReconnect() {
        var blocked = true
        var explicitlyDisconnected = false
        val controller = ReverseConnectionRetryController(
            readHttp402Snapshot = {
                ReverseJoinHttp402Snapshot(blocked, explicitlyDisconnected)
            },
            setHttp402Blocked = { value ->
                val wasBlocked = blocked
                blocked = value
                if (!value || !wasBlocked) {
                    explicitlyDisconnected = false
                }
            },
            markExplicitlyDisconnected = { explicitlyDisconnected = true },
        )

        controller.onExplicitDisconnect()

        assertTrue(blocked)
        assertTrue(explicitlyDisconnected)
        assertEquals(ConnectionState.DISCONNECTED, controller.blockedPresentationStateOrNull())
        assertEquals(
            ReverseConnectionStartDecision.RestoreHttp402Block(
                ConnectionState.DISCONNECTED,
            ),
            controller.onStart(explicitReconnect = false),
        )
        assertTrue(blocked)
        assertEquals(ConnectionState.DISCONNECTED, controller.blockedPresentationStateOrNull())

        assertEquals(
            ReverseConnectionStartDecision.Connect,
            controller.onStart(explicitReconnect = true),
        )
        assertFalse(blocked)
        assertFalse(explicitlyDisconnected)

        controller.onClose("402 Payment Required") {}

        assertTrue(blocked)
        assertFalse(explicitlyDisconnected)
        assertEquals(ConnectionState.LIMIT_EXCEEDED, controller.blockedPresentationStateOrNull())
    }

    @Test
    fun explicitDisconnectPersistsAndPublishesBeforeAsyncServiceDispatch() {
        val transitionOrder = mutableListOf<String>()

        performExplicitReverseConnectionDisconnect(
            markExplicitlyDisconnected = { transitionOrder += "persist" },
            publishDisconnected = { state ->
                assertEquals(ConnectionState.DISCONNECTED, state)
                transitionOrder += "publish"
            },
            dispatchDisconnect = { transitionOrder += "dispatch" },
        )

        assertEquals(listOf("persist", "publish", "dispatch"), transitionOrder)
    }

    @Test
    fun http402PresentationStateSupportsProcessAndStatusRestoration() {
        assertNull(
            http402BlockedPresentationState(
                blocked = false,
                explicitlyDisconnected = true,
            ),
        )
        assertEquals(
            ConnectionState.LIMIT_EXCEEDED,
            http402BlockedPresentationState(
                blocked = true,
                explicitlyDisconnected = false,
            ),
        )
        assertEquals(
            ConnectionState.DISCONNECTED,
            http402BlockedPresentationState(
                blocked = true,
                explicitlyDisconnected = true,
            ),
        )
    }
}
