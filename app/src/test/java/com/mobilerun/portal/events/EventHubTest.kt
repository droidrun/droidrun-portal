package com.mobilerun.portal.events

import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.java_websocket.WebSocket

class EventHubTest {
    @Before
    fun setUp() {
        resetEventHubState()
    }

    @Test
    fun emit_withoutInit_allowsEventsByDefault() {
        val received = mutableListOf<PortalEvent>()
        EventHub.subscribe { received.add(it) }

        val event = PortalEvent(EventType.NOTIFICATION, timestamp = 1L, payload = "x")
        EventHub.emit(event)

        assertEquals(listOf(event), received)
    }

    @Test
    fun emit_withConfigDisabled_doesNotNotifyListener() {
        val config = mockk<ConfigManager>()
        every { config.isEventEnabled(EventType.NOTIFICATION) } returns false
        EventHub.init(config)

        val received = mutableListOf<PortalEvent>()
        EventHub.subscribe { received.add(it) }

        EventHub.emit(PortalEvent(EventType.NOTIFICATION, timestamp = 1L, payload = "x"))

        assertTrue(received.isEmpty())
        verify(exactly = 1) { config.isEventEnabled(EventType.NOTIFICATION) }
    }

    @Test
    fun emit_allowsPongAndUnknownEvenWhenDisabledInConfig() {
        val config = mockk<ConfigManager>()
        every { config.isEventEnabled(any()) } returns false
        EventHub.init(config)

        val received = mutableListOf<PortalEvent>()
        EventHub.subscribe { received.add(it) }

        val pong = PortalEvent(EventType.PONG, timestamp = 1L)
        val unknown = PortalEvent(EventType.UNKNOWN, timestamp = 2L)

        EventHub.emit(pong)
        EventHub.emit(unknown)

        assertEquals(listOf(pong, unknown), received)
        verify(exactly = 0) { config.isEventEnabled(EventType.PONG) }
        verify(exactly = 0) { config.isEventEnabled(EventType.UNKNOWN) }
    }

    @Test
    fun localDeviceEventRelay_defaultsToLegacyEnvelope() {
        val connection = mockk<WebSocket>()
        val sentMessages = mutableListOf<String>()
        every { connection.isOpen } returns true
        every { connection.send(any<String>()) } answers {
            sentMessages += invocation.args[0] as String
            Unit
        }

        val relay = LocalDeviceEventRelay(connectionsProvider = { listOf(connection) })
        val event = PortalEvent(EventType.USER_PRESENT, timestamp = 123L)

        relay.register(connection, "/?token=abc")
        relay.emit(event)

        assertEquals(listOf(event.toJson()), sentMessages)
    }

    @Test
    fun localDeviceEventRelay_usesLegacyEnvelopeWhenRequested() {
        val connection = mockk<WebSocket>()
        val sentMessages = mutableListOf<String>()
        every { connection.isOpen } returns true
        every { connection.send(any<String>()) } answers {
            sentMessages += invocation.args[0] as String
            Unit
        }

        val relay = LocalDeviceEventRelay(connectionsProvider = { listOf(connection) })
        val event = PortalEvent(EventType.NOTIFICATION, timestamp = 123L, payload = "x")

        relay.register(connection, "/?token=abc&eventFormat=legacy")
        relay.emit(event)

        assertEquals(listOf(event.toJson()), sentMessages)
    }

    @Test
    fun localDeviceEventRelay_usesRpcEnvelopeWhenRequested() {
        val connection = mockk<WebSocket>()
        val sentMessages = mutableListOf<String>()
        every { connection.isOpen } returns true
        every { connection.send(any<String>()) } answers {
            sentMessages += invocation.args[0] as String
            Unit
        }

        val relay = LocalDeviceEventRelay(connectionsProvider = { listOf(connection) })
        val event = PortalEvent(EventType.USER_PRESENT, timestamp = 123L)

        relay.register(connection, "/?token=abc&eventFormat=rpc")
        relay.emit(event)

        assertEquals(listOf(event.toReverseNotificationJson()), sentMessages)
    }

    @Test
    fun localDeviceEventRelay_sendsOneMessagePerConnection() {
        val connection = mockk<WebSocket>()
        val sentMessages = mutableListOf<String>()
        every { connection.isOpen } returns true
        every { connection.send(any<String>()) } answers {
            sentMessages += invocation.args[0] as String
            Unit
        }

        val relay = LocalDeviceEventRelay(connectionsProvider = { listOf(connection) })

        relay.register(connection, "/?token=abc&eventFormat=legacy")
        relay.emit(PortalEvent(EventType.APP_ENTERED, timestamp = 1L))

        assertEquals(1, sentMessages.size)
    }

    @Test
    fun localDeviceEventRouting_invalidFormatFallsBackToLegacy() {
        assertEquals(
            LocalDeviceEventFormat.LEGACY,
            LocalDeviceEventRouting.resolveFormat("/?token=abc&eventFormat=unknown"),
        )
    }

    private fun resetEventHubState() {
        val instance = EventHub
        EventHub::class.java.getDeclaredField("listeners").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(instance) as MutableSet<(PortalEvent) -> Unit>).clear()
        }
        EventHub::class.java.getDeclaredField("configManager").apply {
            isAccessible = true
            set(instance, null)
        }
    }
}
