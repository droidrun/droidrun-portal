package com.mobilerun.portal.service

import android.content.Context
import com.mobilerun.portal.api.ApiResponse
import com.mobilerun.portal.keepalive.KeepAliveController
import com.mobilerun.portal.keepalive.KeepAliveStartupException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MobilerunContentProviderTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun handleKeepScreenAwakeInsert_returnsSuccessUriWhenEnableSucceeds() {
        val context = mockk<Context>(relaxed = true)

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, true) } just Runs

        val result = handleKeepScreenAwakeInsert(context, true)

        assertEquals(ApiResponse.Success("Keep screen awake set to true"), result)
        verify(exactly = 1) { KeepAliveController.setEnabled(context, true) }
    }

    @Test
    fun handleKeepScreenAwakeInsert_returnsErrorUriWhenStartupFails() {
        val context = mockk<Context>(relaxed = true)

        mockkObject(KeepAliveController)
        every { KeepAliveController.setEnabled(context, true) } throws
            KeepAliveStartupException("foreground_service_start_not_allowed")

        val result = handleKeepScreenAwakeInsert(context, true)

        assertEquals(
            ApiResponse.Error("foreground_service_start_not_allowed"),
            result,
        )
        verify(exactly = 1) { KeepAliveController.setEnabled(context, true) }
    }
}
