package com.droidrun.portal.core

import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import org.json.JSONArray
import org.json.JSONObject

object JsonBuilders {

    fun elementNodeToJson(element: ElementNode): JSONObject {
        return JSONObject().apply {
            put("index", element.overlayIndex)
            put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
            put("className", element.className)
            put("text", element.text)
            put("bounds", "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}")

            val childrenArray = JSONArray()
            element.children.forEach { child ->
                childrenArray.put(elementNodeToJson(child))
            }
            put("children", childrenArray)
        }
    }

    fun phoneStateToJson(state: PhoneState): JSONObject {
        return JSONObject().apply {
            put("currentApp", state.appName)
            put("packageName", state.packageName)
            put("activityName", state.activityName ?: "")
            put("keyboardVisible", state.keyboardVisible)
            put("isEditable", state.isEditable)
            put("focusedElement", JSONObject().apply {
                put("text", state.focusedElement?.text)
                put("className", state.focusedElement?.className)
                put("resourceId", state.focusedElement?.viewIdResourceName ?: "")
            })
        }
    }
}
