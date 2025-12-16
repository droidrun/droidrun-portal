package com.droidrun.portal.service

import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionDispatcherTest {
    @Test
    fun dispatch_tap_normalizesActionPrefixes() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.performTap(10, 20) } returns ApiResponse.Success("ok")

        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply {
            put("x", 10)
            put("y", 20)
        }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("/action/tap", params))
        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("action.tap", params))
        verify(exactly = 2) { apiHandler.performTap(10, 20) }
    }

    @Test
    fun dispatch_swipe_defaultsDurationTo300ms() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.performSwipe(1, 2, 3, 4, 300) } returns ApiResponse.Success("ok")

        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply {
            put("startX", 1)
            put("startY", 2)
            put("endX", 3)
            put("endY", 4)
        }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("swipe", params))
        verify(exactly = 1) { apiHandler.performSwipe(1, 2, 3, 4, 300) }
    }

    @Test
    fun dispatch_app_treatsMissingOrNullOrEmptyActivityAsNull() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.startApp("com.example", null) } returns ApiResponse.Success("ok")

        val dispatcher = ActionDispatcher(apiHandler)

        val missingActivity = JSONObject().apply { put("package", "com.example") }
        val emptyActivity = JSONObject().apply {
            put("package", "com.example")
            put("activity", "")
        }
        val literalNullActivity = JSONObject().apply {
            put("package", "com.example")
            put("activity", "null")
        }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("app", missingActivity))
        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("app", emptyActivity))
        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("app", literalNullActivity))
        verify(exactly = 3) { apiHandler.startApp("com.example", null) }
    }

    @Test
    fun dispatch_input_defaultsClearToTrue_andSupportsAliases() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.keyboardInput("SGVsbG8=", true) } returns ApiResponse.Success("ok")

        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply { put("base64_text", "SGVsbG8=") }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("input", params))
        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("keyboard/input", params))
        verify(exactly = 2) { apiHandler.keyboardInput("SGVsbG8=", true) }
    }

    @Test
    fun dispatch_unknownMethod_returnsError() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(
            ApiResponse.Error("Unknown method: does_not_exist"),
            dispatcher.dispatch("does_not_exist", JSONObject()),
        )
    }
}

