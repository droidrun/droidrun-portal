package com.mobilerun.portal.core

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityTreeBuilderTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun buildFullAccessibilityTreeJson_skipsSelfRecursiveNode() {
        val root = node("root")
        configureNode(root, children = listOf(root))

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root)

        assertNotNull(json)
        assertEquals(0, json!!.getJSONArray("children").length())
    }

    @Test
    fun buildFullAccessibilityTreeJson_cutsOffOverlyDeepTrees() {
        val nodes = (0..AccessibilityTraversalGuard.MAX_ACCESSIBILITY_TREE_DEPTH + 2)
            .map { node("node-$it") }
        nodes.forEachIndexed { index, node ->
            configureNode(
                node,
                viewId = "node-$index",
                children = nodes.getOrNull(index + 1)?.let { listOf(it) } ?: emptyList(),
            )
        }

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(nodes.first())

        assertNotNull(json)
        assertTrue(treeDepth(json!!) <= AccessibilityTraversalGuard.MAX_ACCESSIBILITY_TREE_DEPTH)
    }

    @Test
    fun buildFullAccessibilityTreeJson_preservesLongRepeatedSyntheticKeyChains() {
        val nodes = (0..12).map { node("repeated-$it") }
        nodes.forEachIndexed { index, node ->
            configureNode(node, viewId = "same", children = nodes.getOrNull(index + 1)?.let { listOf(it) } ?: emptyList())
        }

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(nodes.first())

        assertNotNull(json)
        assertEquals(12, treeDepth(json!!))
    }

    @Test
    fun buildFullAccessibilityTreeJson_preservesRepeatedSiblingKeys() {
        val childOne = node("child")
        val childTwo = node("child")
        val root = node("root")
        configureNode(childOne, viewId = "same", text = "same")
        configureNode(childTwo, viewId = "same", text = "same")
        configureNode(root, children = listOf(childOne, childTwo))

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root)

        assertNotNull(json)
        assertEquals(2, json!!.getJSONArray("children").length())
    }

    @Test
    fun buildFullAccessibilityTreeJson_doesNotRecycleActiveAncestorCycleChild() {
        val root = node("root")
        val child = node("child")
        var rootRecycled = false
        configureNode(root, viewId = "root", children = listOf(child))
        configureNode(child, viewId = "child", children = listOf(root))
        every { root.recycle() } answers {
            rootRecycled = true
        }
        every { root.viewIdResourceName } answers {
            if (rootRecycled) throw RuntimeException("root was recycled early") else "root"
        }

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root)

        assertNotNull(json)
        assertEquals("root", json!!.getString("resourceId"))
        assertEquals(1, json.getJSONArray("children").length())
        assertEquals(0, json.getJSONArray("children").getJSONObject(0).getJSONArray("children").length())
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun buildFullAccessibilityTreeJson_mapsNonFiniteRangeValuesToJsonNull() {
        val root = node("root")
        val range = mockk<AccessibilityNodeInfo.RangeInfo>()
        configureNode(root)
        every { range.type } returns AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT
        every { range.min } returns Float.NEGATIVE_INFINITY
        every { range.max } returns Float.POSITIVE_INFINITY
        every { range.current } returns Float.NaN
        every { root.rangeInfo } returns range

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root)

        assertNotNull(json)
        val rangeJson = JSONObject(json!!.toString()).getJSONObject("rangeInfo")
        assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, rangeJson.getInt("type"))
        assertTrue(rangeJson.isNull("min"))
        assertTrue(rangeJson.isNull("max"))
        assertTrue(rangeJson.isNull("current"))
    }

    @Test
    fun buildFullAccessibilityTreeJson_preservesFiniteRangeValues() {
        val root = node("root")
        val range = mockk<AccessibilityNodeInfo.RangeInfo>()
        configureNode(root)
        every { range.type } returns AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT
        every { range.min } returns -10.5f
        every { range.max } returns 42.25f
        every { range.current } returns 7.5f
        every { root.rangeInfo } returns range

        val json = AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root)

        assertNotNull(json)
        val rangeJson = JSONObject(json!!.toString()).getJSONObject("rangeInfo")
        assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, rangeJson.getInt("type"))
        assertEquals(-10.5, rangeJson.getDouble("min"), 0.0)
        assertEquals(42.25, rangeJson.getDouble("max"), 0.0)
        assertEquals(7.5, rangeJson.getDouble("current"), 0.0)
    }

    private fun node(name: String): AccessibilityNodeInfo {
        return mockk(name = name, relaxed = true)
    }

    private fun configureNode(
        node: AccessibilityNodeInfo,
        viewId: String = "id",
        text: String? = null,
        rect: Rect = Rect(0, 0, 100, 100),
        children: List<AccessibilityNodeInfo> = emptyList()
    ) {
        every { node.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().copyFrom(rect)
        }
        every { node.getBoundsInParent(any()) } answers {
            firstArg<Rect>().copyFrom(rect)
        }
        every { node.packageName } returns "com.example"
        every { node.className } returns "android.widget.TextView"
        every { node.viewIdResourceName } returns viewId
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.hintText } returns null
        every { node.error } returns null
        every { node.childCount } returns children.size
        every { node.getChild(any()) } returns null
        children.forEachIndexed { index, child ->
            every { node.getChild(index) } returns child
        }
        every { node.actionList } returns emptyList()
        every { node.rangeInfo } returns null
        every { node.collectionInfo } returns null
        every { node.collectionItemInfo } returns null
        every { node.extras } returns null as Bundle?
        every { node.labelFor } returns null
        every { node.labeledBy } returns null
        every { node.traversalBefore } returns null
        every { node.traversalAfter } returns null
        every { node.recycle() } just Runs
    }

    private fun Rect.copyFrom(other: Rect) {
        left = other.left
        top = other.top
        right = other.right
        bottom = other.bottom
    }

    private fun treeDepth(json: JSONObject): Int {
        var depth = 0
        var current = json
        while (current.getJSONArray("children").length() > 0) {
            depth++
            current = current.getJSONArray("children").getJSONObject(0)
        }
        return depth
    }
}
