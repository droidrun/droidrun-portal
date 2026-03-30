package com.droidrun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class PortalTaskTimestampSupportTest {

    @Test
    fun parseEpochMillis_supports_iso_with_z() {
        assertEquals(
            Instant.parse("2026-03-18T16:25:37.513640Z").toEpochMilli(),
            PortalTaskTimestampSupport.parseEpochMillis("2026-03-18T16:25:37.513640Z"),
        )
    }

    @Test
    fun parseEpochMillis_supports_iso_with_offset() {
        assertEquals(
            Instant.parse("2026-03-18T16:25:37.513640Z").toEpochMilli(),
            PortalTaskTimestampSupport.parseEpochMillis("2026-03-18T18:25:37.513640+02:00"),
        )
    }

    @Test
    fun parseEpochMillis_treats_timezone_less_iso_as_utc() {
        assertEquals(
            Instant.parse("2026-03-18T16:25:37.513640Z").toEpochMilli(),
            PortalTaskTimestampSupport.parseEpochMillis("2026-03-18T16:25:37.513640"),
        )
    }

    @Test
    fun parseEpochMillis_supports_epoch_seconds_and_millis() {
        assertEquals(1_763_614_737_000L, PortalTaskTimestampSupport.parseEpochMillis("1763614737"))
        assertEquals(1_763_614_737_513L, PortalTaskTimestampSupport.parseEpochMillis("1763614737513"))
    }

    @Test
    fun parseEpochMillis_returns_null_for_invalid_values() {
        assertNull(PortalTaskTimestampSupport.parseEpochMillis("not-a-timestamp"))
        assertNull(PortalTaskTimestampSupport.parseEpochMillis("123"))
        assertNull(PortalTaskTimestampSupport.parseEpochMillis(null))
    }

    @Test
    fun formatForDisplay_formats_timezone_less_iso_from_utc_into_device_zone() {
        assertEquals(
            "Mar 18, 20:25",
            PortalTaskTimestampSupport.formatForDisplay(
                raw = "2026-03-18T16:25:37.513640",
                zoneId = ZoneId.of("Asia/Baku"),
                locale = Locale.US,
                nowMs = Instant.parse("2026-03-18T00:00:00Z").toEpochMilli(),
            ),
        )
    }

    @Test
    fun formatForDisplay_returns_raw_value_when_parsing_fails() {
        assertEquals(
            "not-a-timestamp",
            PortalTaskTimestampSupport.formatForDisplay(
                raw = "not-a-timestamp",
                zoneId = ZoneId.of("Asia/Baku"),
                locale = Locale.US,
                nowMs = Instant.parse("2026-03-18T00:00:00Z").toEpochMilli(),
            ),
        )
    }
}
