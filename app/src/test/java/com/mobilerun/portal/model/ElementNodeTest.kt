package com.mobilerun.portal.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementNodeTest {

    @Test
    fun addChildIgnoresSelfReference() {
        val element = elementNode("root")

        element.addChild(element)

        assertTrue(element.children.isEmpty())
        assertNull(element.parent)
    }

    @Test
    fun addChildIgnoresAncestorReference() {
        val parent = elementNode("parent")
        val child = elementNode("child")
        parent.addChild(child)

        child.addChild(parent)

        assertEquals(listOf(child), parent.children)
        assertTrue(child.children.isEmpty())
        assertNull(parent.parent)
        assertEquals(parent, child.parent)
    }

    @Test
    fun descendantAndPathHelpersTerminateWhenCycleAlreadyExists() {
        val parent = elementNode("parent")
        val child = elementNode("child")
        parent.children.add(child)
        child.parent = parent
        child.children.add(parent)
        parent.parent = child

        assertEquals(listOf(child), parent.getAllDescendants())
        assertEquals(listOf(child, parent), parent.getPathFromRoot())
        assertEquals(2, parent.calculateNestingLevel())
    }

    private fun elementNode(id: String): ElementNode {
        return ElementNode(
            nodeInfo = mockk<AccessibilityNodeInfo>(relaxed = true),
            rect = Rect(0, 0, 10, 10),
            text = id,
            className = "TextView",
            windowLayer = 0,
            creationTime = 0L,
            id = id
        )
    }
}
