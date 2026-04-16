package com.mobilerun.portal.taskprompt

import com.mobilerun.portal.state.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class PortalTaskUiSupportTest {

    @Test
    fun shouldShowTaskSurface_requiresConnectedStateTokenAndRestBase() {
        assertTrue(
            PortalTaskUiSupport.shouldShowTaskSurface(
                connectionState = ConnectionState.CONNECTED,
                authToken = "token-123",
            ),
        )
        assertFalse(
            PortalTaskUiSupport.shouldShowTaskSurface(
                connectionState = ConnectionState.DISCONNECTED,
                authToken = "token-123",
            ),
        )
        assertFalse(
            PortalTaskUiSupport.shouldShowTaskSurface(
                connectionState = ConnectionState.CONNECTED,
                authToken = "",
            ),
        )
        assertTrue(
            PortalTaskUiSupport.shouldShowTaskSurface(
                connectionState = ConnectionState.CONNECTED,
                authToken = "token-123",
            ),
        )
    }

    @Test
    fun formatTimestamp_delegates_to_shared_timestamp_support() {
        val previousTimeZone = TimeZone.getDefault()
        val previousLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Baku"))
            Locale.setDefault(Locale.US)

            val raw = "2026-03-18T16:25:37.513640"
            assertTrue(
                PortalTaskUiSupport.formatTimestamp(raw) ==
                    PortalTaskTimestampSupport.formatForDisplay(raw),
            )
        } finally {
            TimeZone.setDefault(previousTimeZone)
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun formatTimestamp_returns_null_for_blank_values() {
        assertNull(PortalTaskUiSupport.formatTimestamp("   "))
    }
}
