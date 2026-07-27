package com.mobilerun.portal.core

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityTreeBuilderInstrumentedTest {

    @Test
    fun buildFullAccessibilityTreeJson_serializesRealUnboundedRangeInfo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation

        ActivityScenario.launch(UnboundedRangeFixtureActivity::class.java).use {
            instrumentation.waitForIdleSync()

            val node = waitForUnboundedRangeNode(uiAutomation)
            val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(node)

            assertNotNull(json)
            val rangeJson = JSONObject(json!!.toString()).getJSONObject("rangeInfo")
            assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, rangeJson.getInt("type"))
            assertTrue(rangeJson.isNull("min"))
            assertTrue(rangeJson.isNull("max"))
            assertEquals(0.0, rangeJson.getDouble("current"), 0.0)
        }
    }

    @Suppress("DEPRECATION")
    private fun waitForUnboundedRangeNode(uiAutomation: UiAutomation): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + NODE_TIMEOUT_MS

        do {
            val root = uiAutomation.rootInActiveWindow
            val candidates = root
                ?.findAccessibilityNodeInfosByText(UnboundedRangeFixtureActivity.NODE_MARKER)
                .orEmpty()
            var match: AccessibilityNodeInfo? = null
            candidates.forEach { candidate ->
                val range = candidate.rangeInfo
                if (
                    match == null &&
                    range?.min == Float.NEGATIVE_INFINITY &&
                    range.max == Float.POSITIVE_INFINITY
                ) {
                    match = candidate
                } else {
                    candidate.recycle()
                }
            }
            root?.recycle()

            match?.let { return it }
            SystemClock.sleep(NODE_POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)

        throw AssertionError("Timed out waiting for a sealed accessibility node with an unbounded range")
    }

    private companion object {
        const val NODE_TIMEOUT_MS = 5_000L
        const val NODE_POLL_INTERVAL_MS = 50L
    }
}
