package com.mobilerun.portal.service

import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeadlessActionSupportTest {
    @Test
    fun allows_streaming_global_and_trigger_methods() {
        assertTrue(HeadlessActionSupport.isAllowed("stream/start"))
        assertTrue(HeadlessActionSupport.isAllowed("stream/stop"))
        assertTrue(HeadlessActionSupport.isAllowed("global"))
        assertTrue(HeadlessActionSupport.isAllowed("webrtc/connect"))
        assertTrue(HeadlessActionSupport.isAllowed("webrtc/offer"))
        assertTrue(HeadlessActionSupport.isAllowed("webrtc/rtcConfiguration"))
        assertTrue(HeadlessActionSupport.isAllowed("webrtc/requestFrame"))
        assertTrue(HeadlessActionSupport.isAllowed("webrtc/keepAlive"))
        assertTrue(HeadlessActionSupport.isAllowed("screen/keepAwake/set"))
        assertTrue(HeadlessActionSupport.isAllowed("screen/keepAwake/status"))
        assertTrue(HeadlessActionSupport.isAllowed("triggers/status"))
    }

    @Test
    fun rejects_non_headless_methods() {
        assertFalse(HeadlessActionSupport.isAllowed("tap"))
        assertFalse(HeadlessActionSupport.isAllowed("packages"))
        assertFalse(HeadlessActionSupport.isAllowed("state"))
    }
}

class ReverseDeviceEventRelayTest {
    @Before
    fun setUp() {
        resetEventHubState()
    }

    @After
    fun tearDown() {
        resetEventHubState()
    }

    @Test
    fun start_forwardsEventsWhenSenderIsAvailable() {
        val sent = mutableListOf<String>()
        val relay = ReverseDeviceEventRelay {
            { text ->
                sent += text
                true
            }
        }

        relay.start()
        EventHub.emit(PortalEvent(EventType.USER_PRESENT, timestamp = 123L))
        relay.stop()

        assertEquals(1, sent.size)
        val json = JSONObject(sent.single())
        assertEquals("events/device", json.getString("method"))
        val params = json.getJSONObject("params")
        assertEquals("USER_PRESENT", params.getString("type"))
        assertEquals(123L, params.getLong("timestamp"))
    }

    @Test
    fun start_doesNothingWhenSenderIsUnavailable() {
        val sent = mutableListOf<String>()
        val relay = ReverseDeviceEventRelay { null }

        relay.start()
        EventHub.emit(
            PortalEvent(
                EventType.APP_ENTERED,
                timestamp = 456L,
                payload = mapOf("package" to "com.example.app"),
            ),
        )
        relay.stop()

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun repeatedStartDoesNotDuplicateEvents() {
        val sent = mutableListOf<String>()
        val relay = ReverseDeviceEventRelay {
            { text ->
                sent += text
                true
            }
        }

        relay.start()
        relay.start()
        EventHub.emit(PortalEvent(EventType.POWER_CONNECTED, timestamp = 1L))
        relay.stop()
        EventHub.emit(PortalEvent(EventType.POWER_CONNECTED, timestamp = 2L))
        relay.start()
        EventHub.emit(PortalEvent(EventType.POWER_CONNECTED, timestamp = 3L))
        relay.stop()

        assertEquals(2, sent.size)
        assertEquals(1L, JSONObject(sent[0]).getJSONObject("params").getLong("timestamp"))
        assertEquals(3L, JSONObject(sent[1]).getJSONObject("params").getLong("timestamp"))
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
