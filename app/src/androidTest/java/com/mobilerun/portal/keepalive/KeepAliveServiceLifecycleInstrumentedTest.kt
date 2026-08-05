package com.mobilerun.portal.keepalive

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepAliveServiceLifecycleInstrumentedTest {
    @Test(timeout = 90_000L)
    fun rapidEnableStatusDisableDoesNotCrashAndServiceRemainsReusable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        try {
            KeepAliveController.disable(context)
            awaitStatus(context, enabled = false, serviceActive = false)

            repeat(50) {
                KeepAliveController.enable(context)
                assertTrue(KeepAliveController.getStatus(context).enabled)
                KeepAliveController.disable(context)
            }

            awaitStatus(context, enabled = false, serviceActive = false)
            watchDisabledState(context)
            awaitNotification(context, present = false)

            KeepAliveController.enable(context)
            awaitStatus(context, enabled = true, serviceActive = true)
            awaitNotification(context, present = true)

            KeepAliveController.disable(context)
            awaitStatus(context, enabled = false, serviceActive = false)
            awaitNotification(context, present = false)
        } finally {
            KeepAliveController.disable(context)
        }
    }

    private fun watchDisabledState(context: Context) {
        val deadline = SystemClock.elapsedRealtime() + WATCHDOG_DURATION_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = KeepAliveController.getStatus(context)
            assertEquals("keep-awake enabled state during watchdog", false, status.enabled)
            assertEquals("keep-alive service state during watchdog", false, status.serviceActive)
            SystemClock.sleep(WATCHDOG_POLL_INTERVAL_MS)
        }
    }

    private fun awaitStatus(
        context: Context,
        enabled: Boolean,
        serviceActive: Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + STATUS_TIMEOUT_MS
        var status = KeepAliveController.getStatus(context)
        while (
            (status.enabled != enabled || status.serviceActive != serviceActive) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            status = KeepAliveController.getStatus(context)
        }

        assertEquals("keep-awake enabled state", enabled, status.enabled)
        assertEquals("keep-alive service state", serviceActive, status.serviceActive)
    }

    private fun awaitNotification(context: Context, present: Boolean) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val deadline = SystemClock.elapsedRealtime() + STATUS_TIMEOUT_MS
        var notificationPresent =
            notificationManager.activeNotifications.any { it.id == KEEP_ALIVE_NOTIFICATION_ID }
        while (notificationPresent != present && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            notificationPresent =
                notificationManager.activeNotifications.any { it.id == KEEP_ALIVE_NOTIFICATION_ID }
        }

        assertEquals("keep-alive foreground notification", present, notificationPresent)
    }

    private companion object {
        const val WATCHDOG_DURATION_MS = 15_000L
        const val WATCHDOG_POLL_INTERVAL_MS = 100L
        const val STATUS_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 25L
        const val KEEP_ALIVE_NOTIFICATION_ID = 2004
    }
}
