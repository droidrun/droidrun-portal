package com.droidrun.portal

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Utility class for building comprehensive JSON representations of accessibility trees
 */
object AccessibilityTreeBuilder {

    /**
     * Builds a comprehensive JSON object from an AccessibilityNodeInfo node,
     * extracting all available properties and recursively processing children.
     *
     * @param node The AccessibilityNodeInfo to convert to JSON
     * @return JSONObject containing all extractable node information
     */
    fun buildFullAccessibilityTreeJson(node: AccessibilityNodeInfo): JSONObject {
        val result = JSONObject().apply {
            // Basic identification
            put("resourceId", node.viewIdResourceName ?: "")
            put("className", node.className?.toString() ?: "")
            put("packageName", node.packageName?.toString() ?: "")

            // Text content
            put("text", node.text?.toString() ?: "")
            put("contentDescription", node.contentDescription?.toString() ?: "")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                put("hint", node.hintText?.toString() ?: "")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                put("stateDescription", node.stateDescription?.toString() ?: "")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                put("tooltipText", node.tooltipText?.toString() ?: "")
                put("paneTitle", node.paneTitle?.toString() ?: "")
            }
            put("error", node.error?.toString() ?: "")

            // Bounds (both in screen and in parent)
            val boundsInScreen = Rect()
            node.getBoundsInScreen(boundsInScreen)
            put("boundsInScreen", JSONObject().apply {
                put("left", boundsInScreen.left)
                put("top", boundsInScreen.top)
                put("right", boundsInScreen.right)
                put("bottom", boundsInScreen.bottom)
            })

            val boundsInParent = Rect()
            node.getBoundsInParent(boundsInParent)
            put("boundsInParent", JSONObject().apply {
                put("left", boundsInParent.left)
                put("top", boundsInParent.top)
                put("right", boundsInParent.right)
                put("bottom", boundsInParent.bottom)
            })

            // Boolean states - Clickability
            put("isClickable", node.isClickable)
            put("isLongClickable", node.isLongClickable)
            put("isContextClickable", node.isContextClickable)

            // Boolean states - Focus
            put("isFocusable", node.isFocusable)
            put("isFocused", node.isFocused)
            put("isAccessibilityFocused", node.isAccessibilityFocused)

            // Boolean states - Selection
            put("isSelected", node.isSelected)

            // Boolean states - Checkable
            put("isCheckable", node.isCheckable)
            put("isChecked", node.isChecked)

            // Boolean states - Enabled/Visible
            put("isEnabled", node.isEnabled)
            put("isVisibleToUser", node.isVisibleToUser)

            // Boolean states - Editable/Input
            put("isEditable", node.isEditable)
            put("isPassword", node.isPassword)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                put("isShowingHintText", node.isShowingHintText)
            }

            // Boolean states - Scrollable
            put("isScrollable", node.isScrollable)

            // Boolean states - Dismissable
            put("isDismissable", node.isDismissable)

            // Boolean states - Multi-line
            put("isMultiLine", node.isMultiLine)

            // Boolean states - Importance
            put("isImportantForAccessibility", node.isImportantForAccessibility)

            // Boolean states - Screen reader
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                put("isScreenReaderFocusable", node.isScreenReaderFocusable)
                put("isHeading", node.isHeading)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                put("isTextSelectable", node.isTextSelectable)
            }

            // Text selection info
            if (node.text != null) {
                put("textSelectionStart", node.textSelectionStart)
                put("textSelectionEnd", node.textSelectionEnd)
            }

            // Input type
            put("inputType", node.inputType)

            // Live region
            put("liveRegion", node.liveRegion)

            // Window info
            put("windowId", node.windowId)

            // Drawing order
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                put("drawingOrder", node.drawingOrder)
            }

            // Max text length
            put("maxTextLength", node.maxTextLength)

            // Movement granularities
            put("movementGranularities", node.movementGranularities)

            // Child and action counts
            put("childCount", node.childCount)
            put("actionCount", node.actionList?.size ?: 0)

            // Range info (for progress bars, sliders, etc.)
            node.rangeInfo?.let { range ->
                put("rangeInfo", JSONObject().apply {
                    put("type", range.type)
                    put("min", range.min.toDouble())
                    put("max", range.max.toDouble())
                    put("current", range.current.toDouble())
                })
            }

            // Collection info (for lists, grids, etc.)
            node.collectionInfo?.let { collection ->
                put("collectionInfo", JSONObject().apply {
                    put("rowCount", collection.rowCount)
                    put("columnCount", collection.columnCount)
                    put("isHierarchical", collection.isHierarchical)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        put("selectionMode", collection.selectionMode)
                    }
                })
            }

            // Collection item info (for items in lists/grids)
            node.collectionItemInfo?.let { item ->
                put("collectionItemInfo", JSONObject().apply {
                    put("rowIndex", item.rowIndex)
                    put("rowSpan", item.rowSpan)
                    put("columnIndex", item.columnIndex)
                    put("columnSpan", item.columnSpan)
                    put("isHeading", item.isHeading)
                    put("isSelected", item.isSelected)
                })
            }

            // Touch delegate info
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                node.touchDelegateInfo?.let { touchDelegate ->
                    put("hasTouchDelegate", true)
                    put("touchDelegateRegionCount", touchDelegate.regionCount)
                }
            }

            // Extras bundle (custom data)
            val extras = node.extras
            if (extras != null && !extras.isEmpty) {
                val extrasJson = JSONObject()
                for (key in extras.keySet()) {
                    try {
                        val value = extras.get(key)
                        when (value) {
                            is String -> extrasJson.put(key, value)
                            is Int -> extrasJson.put(key, value)
                            is Long -> extrasJson.put(key, value)
                            is Boolean -> extrasJson.put(key, value)
                            is Double -> extrasJson.put(key, value)
                            is Float -> extrasJson.put(key, value)
                            else -> extrasJson.put(key, value.toString())
                        }
                    } catch (e: Exception) {
                        // Skip if we can't serialize this extra
                    }
                }
                put("extras", extrasJson)
            }

            // Action list (with all available details)
            val actionsArray = JSONArray()
            node.actionList?.forEach { action ->
                val actionObj = JSONObject().apply {
                    put("id", action.id)
                    put("label", action.label?.toString() ?: "")

                    // Map common action IDs to readable names
                    val actionName = when (action.id) {
                        AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
                        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
                        AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
                        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "CLEAR_FOCUS"
                        AccessibilityNodeInfo.ACTION_SELECT -> "SELECT"
                        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "CLEAR_SELECTION"
                        AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
                        AccessibilityNodeInfo.ACTION_COPY -> "COPY"
                        AccessibilityNodeInfo.ACTION_PASTE -> "PASTE"
                        AccessibilityNodeInfo.ACTION_CUT -> "CUT"
                        else -> "UNKNOWN_${action.id}"
                    }
                    put("name", actionName)
                }
                actionsArray.put(actionObj)
            }
            put("actionList", actionsArray)

            // Labeling information
            node.labelFor?.let {
                put("hasLabelFor", true)
            }
            node.labeledBy?.let {
                put("hasLabeledBy", true)
            }
            node.traversalBefore?.let {
                put("hasTraversalBefore", true)
            }
            node.traversalAfter?.let {
                put("hasTraversalAfter", true)
            }

            // Unique ID (API 33+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                node.uniqueId?.let {
                    put("uniqueId", it)
                }
            }

            // Container title (API 34+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                node.containerTitle?.let {
                    put("containerTitle", it.toString())
                }
            }

            // Recursively build children
            val childrenArray = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    childrenArray.put(buildFullAccessibilityTreeJson(child))
                }
            }
            put("children", childrenArray)
        }

        node.recycle()
        return result
    }
}
