package com.mobilerun.portal.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Represents a UI element detected by the accessibility service
 */
data class ElementNode(
    val nodeInfo: AccessibilityNodeInfo,
    val rect: Rect,
    val text: String,
    val className: String,
    val windowLayer: Int,
    var creationTime: Long,
    val id: String,
    var parent: ElementNode? = null,
    val children: MutableList<ElementNode> = mutableListOf(),
    var clickableIndex: Int = -1,
    var nestingLevel: Int = 0,
    var semanticParentId: String? = null,
    var overlayIndex: Int = -1 // Store the exact index shown in the overlay
) {
    companion object {
        private const val FADE_DURATION_MS = 60000L // Time to fade from weight 1.0 to 0.0 (60 seconds)

        /**
         * Creates a unique ID for an element based on its properties
         */
        fun createId(rect: Rect, className: String, text: String): String {
            return "${rect.toShortString()}_${className}_${text.take(20)}"
        }
    }

    /**
     * Calculates the current weight of this element based on its age
     * Returns a value between 0.0 and 1.0
     */
    fun calculateWeight(): Float {
        val now = System.currentTimeMillis()
        val age = now - creationTime
        return max(0f, min(1f, 1f - (age.toFloat() / FADE_DURATION_MS.toFloat())))
    }

    /**
     * Checks if this element overlaps with another element
     */
    fun overlaps(other: ElementNode): Boolean {
        return Rect.intersects(this.rect, other.rect)
    }

    fun contains(other: ElementNode): Boolean {
        return this.rect.contains(other.rect)
    }

    fun isClickable(): Boolean {
        return nodeInfo.isClickable
    }

    fun isText(): Boolean {
        return text.isNotEmpty() && !nodeInfo.isClickable
    }

    // Calculate nesting level (depth in the hierarchy)
    fun calculateNestingLevel(): Int {
        if (nestingLevel > 0) {
            return nestingLevel
        }

        var current = this
        var level = 0

        while (current.parent != null) {
            level++
            current = current.parent!!
        }

        nestingLevel = level
        return level
    }

    // Get the root ancestor
    fun getRootAncestor(): ElementNode {
        var current = this
        while (current.parent != null) {
            current = current.parent!!
        }
        return current
    }

    // Add a child node
    fun addChild(child: ElementNode) {
        if (!children.contains(child)) {
            children.add(child)
            child.parent = this
        }
    }

    // Remove a child node
    fun removeChild(child: ElementNode) {
        children.remove(child)
        child.parent = null
    }

    // Get all descendants (children, grandchildren, etc.)
    fun getAllDescendants(): List<ElementNode> {
        val descendants = mutableListOf<ElementNode>()
        for (child in children) {
            descendants.add(child)
            descendants.addAll(child.getAllDescendants())
        }
        return descendants
    }

    // Get path from root to this node
    fun getPathFromRoot(): List<ElementNode> {
        val path = mutableListOf<ElementNode>()
        var current: ElementNode? = this
        while (current != null) {
            path.add(0, current)
            current = current.parent
        }
        return path
    }

    override fun toString(): String {
        return "ElementNode(text='$text', className='$className', rect=$rect, id='$id')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ElementNode) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}