package com.mobilerun.portal.core

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Minimal debug-only host for accessibility instrumentation fixtures.
 */
class UnboundedRangeFixtureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            UnboundedRangeView(this),
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private class UnboundedRangeView(context: Context) : View(context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = NODE_MARKER
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.SeekBar"
            info.text = NODE_MARKER
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                0f,
            )
        }
    }

    companion object {
        const val NODE_MARKER = "mobilerun-unbounded-range-fixture"
    }
}
