package com.droidrun.portal.taskprompt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PortalCloudClientTest {

    @Test
    fun deriveRestBaseUrl_convertsDefaultJoinUrl() {
        val url = "wss://api.mobilerun.ai/v1/providers/personal/join"

        assertEquals("https://api.mobilerun.ai/v1", PortalCloudClient.deriveRestBaseUrl(url))
    }

    @Test
    fun deriveRestBaseUrl_returnsNullForUnsupportedPath() {
        val url = "wss://api.mobilerun.ai/ws"

        assertNull(PortalCloudClient.deriveRestBaseUrl(url))
    }

    @Test
    fun deriveCloudBaseUrl_convertsApiHostToCloudHost() {
        val url = "wss://api.mobilerun.ai/v1/providers/personal/join"

        assertEquals("https://cloud.mobilerun.ai", PortalCloudClient.deriveCloudBaseUrl(url))
    }

    @Test
    fun deriveCloudBaseUrl_acceptsCloudHost() {
        val url = "wss://cloud.mobilerun.ai/v1/providers/personal/join"

        assertEquals("https://cloud.mobilerun.ai", PortalCloudClient.deriveCloudBaseUrl(url))
    }

    @Test
    fun deriveCloudBaseUrl_returnsNullForUnsupportedCustomHost() {
        val url = "wss://portal.example.com/v1/providers/personal/join"

        assertNull(PortalCloudClient.deriveCloudBaseUrl(url))
    }

    @Test
    fun normalizeModelIds_handlesDataArrayObjectsAndStrings() {
        val body = """
            {
              "data": [
                {"id": "google/gemini-2.5-flash"},
                "openai/gpt-5.1",
                {"id": "google/gemini-2.5-flash"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("google/gemini-2.5-flash", "openai/gpt-5.1"),
            PortalCloudClient.normalizeModelIds(body),
        )
    }

    @Test
    fun buildModelOptions_preserves_server_order() {
        val options = PortalCloudClient.buildModelOptions(
            listOf(
                "google/gemini-3-flash",
                "openai/gpt-5.1",
                "anthropic/claude-sonnet-4.5",
            ),
        )

        assertEquals(
            listOf(
                "google/gemini-3-flash",
                "openai/gpt-5.1",
                "anthropic/claude-sonnet-4.5",
            ),
            options.map { it.id },
        )
    }

    @Test
    fun parseLaunchTaskId_reads_top_level_and_nested_ids() {
        assertEquals("task-123", PortalCloudClient.parseLaunchTaskId("""{"id":"task-123"}"""))
        assertEquals("task-456", PortalCloudClient.parseLaunchTaskId("""{"task":{"id":"task-456"}}"""))
        assertEquals("task-789", PortalCloudClient.parseLaunchTaskId("""{"data":{"task":{"task_id":"task-789"}}}"""))
        assertEquals("task-999", PortalCloudClient.parseLaunchTaskId("""{"result":{"taskId":"task-999"}}"""))
        assertEquals("task-555", PortalCloudClient.parseLaunchTaskId("""{"data":{"taskId":"task-555"}}"""))
    }

    @Test
    fun launchRecoveryWindow_helpers_cap_delay_and_expiry() {
        val launchStartedAtMs = 10_000L

        assertTrue(PortalCloudClient.hasLaunchRecoveryTimeRemaining(launchStartedAtMs, 17_999L))
        assertEquals(1_000L, PortalCloudClient.nextLaunchRecoveryDelayMs(launchStartedAtMs, 10_000L))
        assertEquals(250L, PortalCloudClient.nextLaunchRecoveryDelayMs(launchStartedAtMs, 17_750L))
        assertEquals(0L, PortalCloudClient.nextLaunchRecoveryDelayMs(launchStartedAtMs, 18_000L))
    }

    @Test
    fun findRecoverableTaskId_matches_same_device_prompt_and_recent_timestamp() {
        val launchStartedAtMs = Instant.parse("2026-03-16T10:15:00Z").toEpochMilli()
        val page = PortalTaskHistoryPage(
            items = listOf(
                PortalTaskHistoryItem(
                    taskId = "task-new",
                    prompt = "Open settings and enable Wi-Fi",
                    promptPreview = "Open settings and enable Wi-Fi",
                    status = "created",
                    deviceId = "device-123",
                    createdAt = "2026-03-16T10:15:03Z",
                ),
                PortalTaskHistoryItem(
                    taskId = "task-old",
                    prompt = "Open settings and enable Wi-Fi",
                    promptPreview = "Open settings and enable Wi-Fi",
                    status = "created",
                    deviceId = "device-999",
                    createdAt = "2026-03-16T10:10:00Z",
                ),
            ),
            page = 1,
            pageSize = 10,
            total = 2,
            pages = 1,
            hasNext = false,
            hasPrev = false,
        )

        val recoveredTaskId = PortalCloudClient.findRecoverableTaskId(
            page = page,
            deviceId = "device-123",
            prompt = "Open settings and enable Wi-Fi",
            launchStartedAtMs = launchStartedAtMs,
            nowMs = launchStartedAtMs + 10_000L,
        )

        assertEquals("task-new", recoveredTaskId)
    }

    @Test
    fun findRecoverableTaskId_falls_back_to_prompt_preview_when_history_prompt_is_shortened() {
        val launchStartedAtMs = Instant.parse("2026-03-16T10:15:00Z").toEpochMilli()
        val page = PortalTaskHistoryPage(
            items = listOf(
                PortalTaskHistoryItem(
                    taskId = "task-preview",
                    prompt = "",
                    promptPreview = "Open settings and enable Wi-Fi…",
                    status = "created",
                    deviceId = "device-123",
                    createdAt = "2026-03-16T10:15:02Z",
                ),
            ),
            page = 1,
            pageSize = 10,
            total = 1,
            pages = 1,
            hasNext = false,
            hasPrev = false,
        )

        val recoveredTaskId = PortalCloudClient.findRecoverableTaskId(
            page = page,
            deviceId = "device-123",
            prompt = "Open settings and enable Wi-Fi for the office setup flow",
            launchStartedAtMs = launchStartedAtMs,
            nowMs = launchStartedAtMs + 10_000L,
        )

        assertEquals("task-preview", recoveredTaskId)
    }

    @Test
    fun findRecoverableTaskId_uses_single_candidate_when_device_matches() {
        val launchStartedAtMs = Instant.parse("2026-03-16T10:15:00Z").toEpochMilli()
        val page = PortalTaskHistoryPage(
            items = listOf(
                PortalTaskHistoryItem(
                    taskId = "task-only",
                    prompt = "Different prompt",
                    promptPreview = "Different prompt",
                    status = "created",
                    deviceId = "device-123",
                    createdAt = "2026-03-16T10:15:01Z",
                ),
            ),
            page = 1,
            pageSize = 10,
            total = 1,
            pages = 1,
            hasNext = false,
            hasPrev = false,
        )

        val recoveredTaskId = PortalCloudClient.findRecoverableTaskId(
            page = page,
            deviceId = "device-123",
            prompt = "Open settings and enable Wi-Fi",
            launchStartedAtMs = launchStartedAtMs,
            nowMs = launchStartedAtMs + 10_000L,
        )

        assertEquals("task-only", recoveredTaskId)
    }

    @Test
    fun findRecoverableTaskId_accepts_createdAt_without_timezone_suffix() {
        val launchStartedAtMs = Instant.parse("2026-03-18T16:25:37Z").toEpochMilli()
        val page = PortalTaskHistoryPage(
            items = listOf(
                PortalTaskHistoryItem(
                    taskId = "task-no-zone",
                    prompt = "Open slack",
                    promptPreview = "Open slack",
                    status = "completed",
                    deviceId = "device-123",
                    createdAt = "2026-03-18T16:25:37.513640",
                ),
            ),
            page = 1,
            pageSize = 10,
            total = 1,
            pages = 1,
            hasNext = false,
            hasPrev = false,
        )

        val recoveredTaskId = PortalCloudClient.findRecoverableTaskId(
            page = page,
            deviceId = "device-123",
            prompt = "Open slack",
            launchStartedAtMs = launchStartedAtMs,
            nowMs = launchStartedAtMs + 10_000L,
        )

        assertEquals("task-no-zone", recoveredTaskId)
    }

    @Test
    fun buildTaskPayload_includesTaskSettingsAndDisplayId() {
        val payload = PortalCloudClient.buildTaskPayload(
            deviceId = "device-123",
            draft = PortalTaskDraft(
                prompt = "Open settings and enable Wi-Fi",
                settings = PortalTaskSettings(
                    llmModel = "openai/gpt-5.1",
                    reasoning = true,
                    vision = true,
                    maxSteps = 321,
                    temperature = 0.75,
                    executionTimeout = 900,
                ),
            ),
        )

        assertEquals("device-123", payload.getString("deviceId"))
        assertEquals("Open settings and enable Wi-Fi", payload.getString("task"))
        assertEquals("openai/gpt-5.1", payload.getString("llmModel"))
        assertTrue(payload.getBoolean("reasoning"))
        assertTrue(payload.getBoolean("vision"))
        assertEquals(321, payload.getInt("maxSteps"))
        assertEquals(0.75, payload.getDouble("temperature"), 0.0)
        assertEquals(900, payload.getInt("executionTimeout"))
        assertEquals(0, payload.getInt("displayId"))
    }

    @Test
    fun buildLaunchTaskRequest_addsBearerAuthHeader() {
        val request = PortalCloudClient.buildLaunchTaskRequest(
            restBaseUrl = "https://api.mobilerun.ai/v1",
            authToken = "token-abc",
            deviceId = "device-123",
            draft = PortalTaskDraft(
                prompt = "Take a screenshot",
                settings = PortalTaskSettings(),
            ),
        )

        assertEquals("https://api.mobilerun.ai/v1/tasks", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))

        val bodyBuffer = okio.Buffer()
        request.body!!.writeTo(bodyBuffer)
        val payload = JSONObject(bodyBuffer.readUtf8())
        assertEquals("device-123", payload.getString("deviceId"))
        assertEquals("Take a screenshot", payload.getString("task"))
    }

    @Test
    fun buildTaskStatusRequest_addsBearerAuthHeader() {
        val request = PortalCloudClient.buildTaskStatusRequest(
            restBaseUrl = "https://api.mobilerun.ai/v1",
            authToken = "token-abc",
            taskId = "task-123",
        )

        assertEquals("https://api.mobilerun.ai/v1/tasks/task-123/status", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))
    }

    @Test
    fun buildBalanceRequest_addsBearerAuthHeader() {
        val request = PortalCloudClient.buildBalanceRequest(
            cloudBaseUrl = "https://cloud.mobilerun.ai",
            authToken = "token-abc",
        )

        assertEquals("https://cloud.mobilerun.ai/api/billing/balance", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))
    }

    @Test
    fun buildTaskTrajectoryRequest_addsBearerAuthHeader() {
        val request = PortalCloudClient.buildTaskTrajectoryRequest(
            restBaseUrl = "https://api.mobilerun.ai/v1",
            authToken = "token-abc",
            taskId = "task-123",
        )

        assertEquals("https://api.mobilerun.ai/v1/tasks/task-123/trajectory", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))
    }

    @Test
    fun buildListTasksRequest_addsQueryPaginationAndAuthHeader() {
        val request = PortalCloudClient.buildListTasksRequest(
            restBaseUrl = "https://api.mobilerun.ai/v1",
            authToken = "token-abc",
            query = "open settings",
            page = 2,
            pageSize = 20,
        )

        assertEquals("Bearer token-abc", request.header("Authorization"))
        assertEquals("createdAt", request.url.queryParameter("orderBy"))
        assertEquals("desc", request.url.queryParameter("orderByDirection"))
        assertEquals("2", request.url.queryParameter("page"))
        assertEquals("20", request.url.queryParameter("pageSize"))
        assertEquals("open settings", request.url.queryParameter("query"))
    }

    @Test
    fun parseTaskHistoryPage_normalizesPromptPreviewAndPagination() {
        val body = """
            {
              "items": [
                {
                  "id": "task-1",
                  "task": "Open settings and make sure Wi-Fi and Bluetooth are enabled for the office setup flow",
                  "status": "completed",
                  "createdAt": "2026-03-10T12:30:00Z",
                  "steps": 3
                }
              ],
              "pagination": {
                "page": 1,
                "pageSize": 20,
                "total": 1,
                "pages": 1,
                "hasNext": false,
                "hasPrev": false
              }
            }
        """.trimIndent()

        val result = PortalCloudClient.parseTaskHistoryPage(body)

        requireNotNull(result)
        assertEquals(1, result.page)
        assertEquals(1, result.total)
        assertEquals(false, result.hasNext)
        assertEquals(1, result.items.size)
        assertEquals("task-1", result.items.first().taskId)
        assertTrue(result.items.first().promptPreview.endsWith("…"))
    }

    @Test
    fun parseTaskScreenshotSet_readsUrls() {
        val body = """
            {
              "urls": [
                "https://example.com/one.png",
                "https://example.com/two.png"
              ]
            }
        """.trimIndent()

        val result = PortalCloudClient.parseTaskScreenshotSet(body)

        requireNotNull(result)
        assertEquals(listOf("https://example.com/one.png", "https://example.com/two.png"), result.urls)
        assertEquals("https://example.com/two.png", result.latestUrl)
    }

    @Test
    fun parseTaskScreenshotSet_returnsEmptySetWhenUrlsMissing() {
        val body = """{"urls": []}"""

        val result = PortalCloudClient.parseTaskScreenshotSet(body)

        requireNotNull(result)
        assertTrue(result.urls.isEmpty())
        assertEquals(0, result.count)
        assertNull(result.latestUrl)
    }

    @Test
    fun parseTaskTrajectory_filtersHiddenEventsAndPreservesUnknownEvents() {
        val body = """
            {
              "trajectory": [
                {
                  "event": "ManagerPlanDetailsEvent",
                  "data": {
                    "plan": "Open Settings, then enable Wi-Fi."
                  }
                },
                {
                  "event": "ScreenshotEvent",
                  "data": {
                    "url": "https://example.com/screenshot.png"
                  }
                },
                {
                  "event": "CustomEvent",
                  "data": {
                    "custom": "value"
                  }
                }
              ]
            }
        """.trimIndent()

        val result = PortalCloudClient.parseTaskTrajectory(body)

        requireNotNull(result)
        assertEquals(2, result.count)
        assertEquals("ManagerPlanDetailsEvent", result.events[0].event)
        assertEquals("CustomEvent", result.events[1].event)
        assertTrue(result.events[1].rawJson.contains("custom"))
    }

    @Test
    fun parseBalanceInfo_readsBalanceUsageAndNextReset() {
        val body = """
            {
              "balance": 440,
              "usage": 60,
              "nextReset": "2026-04-03T19:34:44.000Z"
            }
        """.trimIndent()

        val result = PortalCloudClient.parseBalanceInfo(body)

        requireNotNull(result)
        assertEquals(440, result.balance)
        assertEquals(60, result.usage)
        assertEquals("2026-04-03T19:34:44.000Z", result.nextReset)
    }

    @Test
    fun parseBalanceInfo_keepsNullNextReset() {
        val body = """
            {
              "balance": 0,
              "usage": 0,
              "nextReset": null
            }
        """.trimIndent()

        val result = PortalCloudClient.parseBalanceInfo(body)

        requireNotNull(result)
        assertEquals(0, result.balance)
        assertEquals(0, result.usage)
        assertNull(result.nextReset)
    }

    @Test
    fun parseBalanceInfo_treatsLiteralStringNullAsNull() {
        val body = """
            {
              "balance": 1,
              "usage": 2,
              "nextReset": "null"
            }
        """.trimIndent()

        val result = PortalCloudClient.parseBalanceInfo(body)

        requireNotNull(result)
        assertNull(result.nextReset)
    }

    @Test
    fun buildCancelTaskRequest_addsBearerAuthHeader() {
        val request = PortalCloudClient.buildCancelTaskRequest(
            restBaseUrl = "https://api.mobilerun.ai/v1",
            authToken = "token-abc",
            taskId = "task-123",
        )

        assertEquals("https://api.mobilerun.ai/v1/tasks/task-123/cancel", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))
        assertEquals("POST", request.method)
    }
}
