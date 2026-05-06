package com.mobilerun.portal.taskprompt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTaskTrajectoryUiSupportTest {

    @Test
    fun filterVisibleEvents_hidesInternalEventsAndKeepsUnknownOnes() {
        val result = PortalTaskTrajectoryUiSupport.filterVisibleEvents(
            listOf(
                PortalTaskTrajectoryEvent("ManagerPlanDetailsEvent", JSONObject(), "{}"),
                PortalTaskTrajectoryEvent("ScreenshotEvent", JSONObject(), "{}"),
                PortalTaskTrajectoryEvent("UnknownEvent", JSONObject(), "{}"),
            ),
        )

        assertEquals(listOf("ManagerPlanDetailsEvent", "UnknownEvent"), result.map { it.event })
    }

    @Test
    fun previewSummary_usesPriorityOrder() {
        val event = PortalTaskTrajectoryEvent(
            event = "ManagerPlanDetailsEvent",
            data = JSONObject().apply {
                put("description", "Description should not win")
                put("summary", "Summary should win")
            },
            rawJson = "{}",
        )

        assertEquals("Summary should win", PortalTaskTrajectoryUiSupport.previewSummary(event))
    }

    @Test
    fun previewSummary_formatsXmlToolCalls() {
        val event = PortalTaskTrajectoryEvent(
            event = "FastAgentToolCallEvent",
            data = JSONObject().apply {
                put(
                    "tool_calls_repr",
                    """
                    <invoke name="tap_element_by_index">
                      <parameter name="index">3</parameter>
                    </invoke>
                    """.trimIndent(),
                )
            },
            rawJson = "{}",
        )

        val summary = PortalTaskTrajectoryUiSupport.previewSummary(event)

        requireNotNull(summary)
        assertTrue(summary.isNotBlank())
        assertFalse(summary.contains("<invoke"))
    }

    @Test
    fun parseToolResults_readsStructuredXmlPayload() {
        val results = PortalTaskTrajectoryUiSupport.parseToolResults(
            """
            <result>
              <name>get_text</name>
              <output>Hello world</output>
            </result>
            """.trimIndent(),
        )

        requireNotNull(results)
        assertEquals(1, results.size)
        assertEquals("get_text", results.first().name)
        assertEquals("Hello world", results.first().output)
    }

    @Test
    fun shouldShowRefreshAction_onlyForRunningLikeStatesAfterLoad() {
        assertTrue(
            PortalTaskTrajectoryUiSupport.shouldShowRefreshAction(
                status = PortalTaskTracking.STATUS_RUNNING,
                hasLoadedTrajectory = true,
            ),
        )
        assertFalse(
            PortalTaskTrajectoryUiSupport.shouldShowRefreshAction(
                status = PortalTaskTracking.STATUS_COMPLETED,
                hasLoadedTrajectory = true,
            ),
        )
        assertFalse(
            PortalTaskTrajectoryUiSupport.shouldShowRefreshAction(
                status = PortalTaskTracking.STATUS_RUNNING,
                hasLoadedTrajectory = false,
            ),
        )
    }
}
