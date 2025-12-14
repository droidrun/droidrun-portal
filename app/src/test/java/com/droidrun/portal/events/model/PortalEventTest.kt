package com.droidrun.portal.events.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalEventTest {
    @Test
    fun toJson_includesTypeAndTimestamp() {
        val event = PortalEvent(type = EventType.PING, timestamp = 123L, payload = "hello")

        val json = JSONObject(event.toJson())

        assertEquals("PING", json.getString("type"))
        assertEquals(123L, json.getLong("timestamp"))
        assertEquals("hello", json.getString("payload"))
    }

    @Test
    fun toJson_omitsPayloadWhenNull() {
        val event = PortalEvent(type = EventType.PING, timestamp = 123L, payload = null)

        val json = JSONObject(event.toJson())

        assertFalse(json.has("payload"))
    }

    @Test
    fun roundTrip_preservesStringPayload() {
        val original =
            PortalEvent(type = EventType.NOTIFICATION, timestamp = 456L, payload = "payload")

        val parsed = PortalEvent.fromJson(original.toJson())

        assertEquals(EventType.NOTIFICATION, parsed.type)
        assertEquals(456L, parsed.timestamp)
        assertEquals("payload", parsed.payload)
    }

    @Test
    fun roundTrip_mapPayloadBecomesJsonObject() {
        val original = PortalEvent(
            type = EventType.NOTIFICATION,
            timestamp = 456L,
            payload = mapOf("a" to 1, "b" to "two")
        )

        val parsed = PortalEvent.fromJson(original.toJson())

        assertEquals(EventType.NOTIFICATION, parsed.type)
        assertEquals(456L, parsed.timestamp)
        assertTrue(parsed.payload is JSONObject)

        val payloadJson = parsed.payload as JSONObject
        assertEquals(1, payloadJson.getInt("a"))
        assertEquals("two", payloadJson.getString("b"))
    }

    @Test
    fun fromJson_unknownTypeMapsToUnknown() {
        val parsed = PortalEvent.fromJson("""{"type":"DOES_NOT_EXIST","timestamp":1}""")

        assertEquals(EventType.UNKNOWN, parsed.type)
        assertEquals(1L, parsed.timestamp)
        assertNull(parsed.payload)
    }

    @Test
    fun fromJson_invalidJsonReturnsUnknownWithParseErrorPayload() {
        val parsed = PortalEvent.fromJson("not-jeson")

        assertEquals(EventType.UNKNOWN, parsed.type)
        assertTrue(parsed.payload is String)
        assertTrue((parsed.payload as String).startsWith("Parse Error:"))
    }
}

