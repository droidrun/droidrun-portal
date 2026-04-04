package com.droidrun.portal.service

import android.util.Base64
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import com.droidrun.portal.triggers.TriggerApi
import com.droidrun.portal.triggers.TriggerApiResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun dispatch_overlayVisible_routesSlashAliases() {
        val apiHandler = mockk<ApiHandler>()
        val response = ApiResponse.RawObject(JSONObject().put("visible", true))
        every { apiHandler.isOverlayVisible() } returns response

        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(response, dispatcher.dispatch("overlay/visible", JSONObject()))
        assertEquals(response, dispatcher.dispatch("overlay/is-visible", JSONObject()))
        verify(exactly = 2) { apiHandler.isOverlayVisible() }
    }

    @Test
    fun dispatch_filesUpload_rejectsOversizedBase64BeforeDecode() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler, maxBase64UploadBytes = 4L)
        val params =
            JSONObject().apply {
                put("path", "downloads/file.bin")
                put("data", "QUJDREU=")
            }

        assertEquals(
            ApiResponse.Error(
                "Data too large for files/upload (max 0MB decoded); use files/fetch for larger files",
            ),
            dispatcher.dispatch("files/upload", params),
        )
        verify(exactly = 0) { apiHandler.uploadFile(any(), any()) }
    }

    @Test
    fun dispatch_filesUpload_decodesAndUploadsSmallPayload() {
        val apiHandler = mockk<ApiHandler>()
        val uploaded = slot<ByteArray>()
        every { apiHandler.uploadFile("downloads/file.bin", capture(uploaded)) } returns ApiResponse.Success("ok")
        mockkStatic(Base64::class)
        every { Base64.decode("QUJDRA==", Base64.DEFAULT) } returns "ABCD".toByteArray()

        try {
            val dispatcher = ActionDispatcher(apiHandler, maxBase64UploadBytes = 4L)
            val params =
                JSONObject().apply {
                    put("path", "downloads/file.bin")
                    put("data", "QUJDRA==")
                }

            assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("files/upload", params))
            assertTrue(uploaded.captured.contentEquals("ABCD".toByteArray()))
            verify(exactly = 1) { apiHandler.uploadFile("downloads/file.bin", any()) }
        } finally {
            unmockkStatic(Base64::class)
        }
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

    @Test
    fun dispatch_install_supportsUrlsList_andHideOverlayFlag() {
        val apiHandler = mockk<ApiHandler>()
        every {
            apiHandler.installFromUrls(
                listOf(
                    "https://example.com/a.apk",
                    "https://example.com/b.apk"
                ), false
            )
        } returns ApiResponse.Success("ok")
        every {
            apiHandler.installFromUrls(
                listOf(
                    "https://example.com/a.apk",
                    "https://example.com/b.apk"
                ), true
            )
        } returns ApiResponse.Success("ok")

        val dispatcher = ActionDispatcher(apiHandler)

        // Default behavior: hideOverlay defaults to false if omitted
        val defaultParams = JSONObject().apply {
            put(
                "urls",
                JSONArray().apply {
                    put("https://example.com/a.apk")
                    put("https://example.com/b.apk")
                },
            )
        }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("install", defaultParams))
        assertEquals(
            ApiResponse.Error("Install is only supported over WebSocket"),
            dispatcher.dispatch("install", defaultParams, ActionDispatcher.Origin.HTTP),
        )

        verify(exactly = 1) {
            apiHandler.installFromUrls(
                listOf(
                    "https://example.com/a.apk",
                    "https://example.com/b.apk"
                ), false
            )
        }

        // Explicitly passing hideOverlay=true should propagate
        val explicitParams = JSONObject(defaultParams.toString()).apply {
            put("hideOverlay", true)
        }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("install", explicitParams))
        verify(exactly = 1) {
            apiHandler.installFromUrls(
                listOf(
                    "https://example.com/a.apk",
                    "https://example.com/b.apk"
                ), true
            )
        }

        // Explicitly passing hideOverlay=false should propagate
        val falseParams = JSONObject(defaultParams.toString()).apply {
            put("hideOverlay", false)
        }

        assertEquals(ApiResponse.Success("ok"), dispatcher.dispatch("install", falseParams))
        verify(exactly = 2) {
            apiHandler.installFromUrls(
                listOf(
                    "https://example.com/a.apk",
                    "https://example.com/b.apk"
                ), false
            )
        }
    }

    @Test
    fun dispatch_streamStop_requiresSessionId() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(
            ApiResponse.Error("Missing required param: 'sessionId'"),
            dispatcher.dispatch(
                "stream/stop",
                JSONObject(),
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
    }

    @Test
    fun dispatch_streamStop_passesSessionId() {
        val apiHandler = mockk<ApiHandler>()
        every {
            apiHandler.stopStream(
                "session-1",
                graceful = true
            )
        } returns ApiResponse.Success("ok")
        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply { put("sessionId", "session-1") }

        assertEquals(
            ApiResponse.Success("ok"),
            dispatcher.dispatch("stream/stop", params, ActionDispatcher.Origin.WEBSOCKET_REVERSE),
        )
        verify(exactly = 1) { apiHandler.stopStream("session-1", graceful = true) }
    }

    @Test
    fun dispatch_webrtcAnswer_requiresSessionId() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply { put("sdp", "answer-sdp") }

        assertEquals(
            ApiResponse.Error("Missing required param: 'sessionId'"),
            dispatcher.dispatch("webrtc/answer", params, ActionDispatcher.Origin.WEBSOCKET_REVERSE),
        )
    }

    @Test
    fun dispatch_webrtcIce_requiresSessionId() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)
        val params =
            JSONObject().apply {
                put("candidate", "candidate:1 1 udp 1 0.0.0.0 9 typ host")
                put("sdpMid", "0")
                put("sdpMLineIndex", 0)
            }

        assertEquals(
            ApiResponse.Error("Missing required param: 'sessionId'"),
            dispatcher.dispatch("webrtc/ice", params, ActionDispatcher.Origin.WEBSOCKET_REVERSE),
        )
    }

    @Test
    fun dispatch_webrtcRtcConfiguration_passesParams() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.handleWebRtcRtcConfiguration(any()) } returns ApiResponse.Success("ok")
        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply {
            put("sessionId", "session-1")
            put("iceServers", JSONArray())
        }

        assertEquals(
            ApiResponse.Success("ok"),
            dispatcher.dispatch(
                "webrtc/rtcConfiguration",
                params,
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
        verify(exactly = 1) { apiHandler.handleWebRtcRtcConfiguration(params) }
    }

    @Test
    fun dispatch_webrtcConnect_requiresSessionId() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(
            ApiResponse.Error("Missing required param: 'sessionId'"),
            dispatcher.dispatch(
                "webrtc/connect",
                JSONObject(),
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
    }

    @Test
    fun dispatch_webrtcConnect_passesParams() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.connectWebRtc(any()) } returns ApiResponse.Success("ok")
        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply { put("sessionId", "session-1") }

        assertEquals(
            ApiResponse.Success("ok"),
            dispatcher.dispatch("webrtc/connect", params, ActionDispatcher.Origin.WEBSOCKET_REVERSE),
        )
        verify(exactly = 1) { apiHandler.connectWebRtc(params) }
    }

    @Test
    fun dispatch_webrtcRequestFrame_requiresSessionId() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(
            ApiResponse.Error("Missing required param: 'sessionId'"),
            dispatcher.dispatch(
                "webrtc/requestFrame",
                JSONObject(),
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
    }

    @Test
    fun dispatch_webrtcRequestFrame_passesSessionId() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.handleWebRtcRequestFrame("session-1") } returns ApiResponse.Success("ok")
        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply { put("sessionId", "session-1") }

        assertEquals(
            ApiResponse.Success("ok"),
            dispatcher.dispatch(
                "webrtc/requestFrame",
                params,
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
        verify(exactly = 1) { apiHandler.handleWebRtcRequestFrame("session-1") }
    }

    @Test
    fun dispatch_webrtcKeepAlive_requiresSessionId() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val dispatcher = ActionDispatcher(apiHandler)

        assertEquals(
            ApiResponse.Error("Missing required param: 'sessionId'"),
            dispatcher.dispatch(
                "webrtc/keepAlive",
                JSONObject(),
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
    }

    @Test
    fun dispatch_webrtcKeepAlive_passesSessionId() {
        val apiHandler = mockk<ApiHandler>()
        every { apiHandler.handleWebRtcKeepAlive("session-1") } returns ApiResponse.Success("ok")
        val dispatcher = ActionDispatcher(apiHandler)
        val params = JSONObject().apply { put("sessionId", "session-1") }

        assertEquals(
            ApiResponse.Success("ok"),
            dispatcher.dispatch(
                "webrtc/keepAlive",
                params,
                ActionDispatcher.Origin.WEBSOCKET_REVERSE,
            ),
        )
        verify(exactly = 1) { apiHandler.handleWebRtcKeepAlive("session-1") }
    }

    @Test
    fun dispatch_triggerCatalogStatusAndLists_returnStructuredResults() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val triggerApi = mockk<TriggerApi>()
        val catalog = JSONObject().apply { put("schemaVersion", 5) }
        val status = JSONObject().apply { put("ruleCount", 2) }
        val rules = JSONArray().put(JSONObject().put("id", "rule-1"))
        val runs = JSONArray().put(JSONObject().put("id", "run-1"))
        every { triggerApi.catalog() } returns catalog
        every { triggerApi.status() } returns status
        every { triggerApi.listRules() } returns rules
        every { triggerApi.listRuns(25) } returns runs

        val dispatcher = ActionDispatcher(apiHandler, triggerApi)

        assertEquals(ApiResponse.RawObject(catalog), dispatcher.dispatch("triggers/catalog", JSONObject()))
        assertEquals(ApiResponse.RawObject(status), dispatcher.dispatch("triggers/status", JSONObject()))
        assertEquals(
            ApiResponse.RawArray(rules),
            dispatcher.dispatch("triggers/rules/list", JSONObject()),
        )
        assertEquals(
            ApiResponse.RawArray(runs),
            dispatcher.dispatch("triggers/runs/list", JSONObject().put("limit", 25)),
        )
    }

    @Test
    fun dispatch_triggerRuleMutations_mapTriggerApiResults() {
        val apiHandler = mockk<ApiHandler>(relaxed = true)
        val triggerApi = mockk<TriggerApi>()
        val savedRule = JSONObject().put("id", "rule-1")
        val updatedRule = JSONObject().put("id", "rule-1").put("enabled", false)
        every { triggerApi.getRule("rule-1") } returns TriggerApiResult.Success(savedRule)
        every { triggerApi.saveRule(any()) } returns TriggerApiResult.Success(savedRule)
        every { triggerApi.setRuleEnabled("rule-1", false) } returns TriggerApiResult.Success(updatedRule)
        every { triggerApi.deleteRule("rule-1") } returns TriggerApiResult.Success("Deleted trigger rule rule-1")
        every { triggerApi.testRule("rule-1") } returns TriggerApiResult.Success("Test run requested for rule-1")
        every { triggerApi.deleteRun("run-1") } returns TriggerApiResult.Success("Deleted trigger run run-1")
        every { triggerApi.clearRuns() } returns TriggerApiResult.Success("Cleared trigger runs")

        val dispatcher = ActionDispatcher(apiHandler, triggerApi)

        assertEquals(
            ApiResponse.RawObject(savedRule),
            dispatcher.dispatch("triggers/rules/get", JSONObject().put("ruleId", "rule-1")),
        )
        assertEquals(
            ApiResponse.RawObject(savedRule),
            dispatcher.dispatch(
                "triggers/rules/save",
                JSONObject().put("rule", JSONObject().put("id", "rule-1")),
            ),
        )
        assertEquals(
            ApiResponse.RawObject(updatedRule),
            dispatcher.dispatch(
                "triggers/rules/setEnabled",
                JSONObject().put("ruleId", "rule-1").put("enabled", false),
            ),
        )
        assertEquals(
            ApiResponse.Success("Deleted trigger rule rule-1"),
            dispatcher.dispatch("triggers/rules/delete", JSONObject().put("ruleId", "rule-1")),
        )
        assertEquals(
            ApiResponse.Success("Test run requested for rule-1"),
            dispatcher.dispatch("triggers/rules/test", JSONObject().put("ruleId", "rule-1")),
        )
        assertEquals(
            ApiResponse.Success("Deleted trigger run run-1"),
            dispatcher.dispatch("triggers/runs/delete", JSONObject().put("runId", "run-1")),
        )
        assertEquals(
            ApiResponse.Success("Cleared trigger runs"),
            dispatcher.dispatch("triggers/runs/clear", JSONObject()),
        )
    }

    @Test
    fun dispatch_triggerMethods_requireExpectedParams() {
        val dispatcher = ActionDispatcher(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(
            ApiResponse.Error("Missing required param: 'ruleId'"),
            dispatcher.dispatch("triggers/rules/get", JSONObject()),
        )
        assertEquals(
            ApiResponse.Error("Missing required param: 'rule'"),
            dispatcher.dispatch("triggers/rules/save", JSONObject()),
        )
        assertEquals(
            ApiResponse.Error("Missing required param: 'enabled'"),
            dispatcher.dispatch("triggers/rules/setEnabled", JSONObject().put("ruleId", "rule-1")),
        )
        assertEquals(
            ApiResponse.Error("Missing required param: 'runId'"),
            dispatcher.dispatch("triggers/runs/delete", JSONObject()),
        )
    }
}
