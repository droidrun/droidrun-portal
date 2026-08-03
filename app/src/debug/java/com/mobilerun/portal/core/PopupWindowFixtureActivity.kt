package com.mobilerun.portal.core

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Debug-only host for exercising accessibility behavior across [PopupWindow] roots.
 */
class PopupWindowFixtureActivity : Activity() {

    private lateinit var actionClickedMarkerView: TextView
    private var popupWindow: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(24), dp(24), dp(24))

                    addView(markerView(UNDERLYING_MARKER))
                    actionClickedMarkerView = markerView(ACTION_CLICKED_MARKER).apply {
                        visibility = View.GONE
                    }
                    addView(actionClickedMarkerView)
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Shows a touchable popup whose focus behavior is controlled by [focusable]. */
    fun showPopup(focusable: Boolean) {
        popupWindow?.dismiss()
        actionClickedMarkerView.visibility = View.GONE

        val popupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.DKGRAY)
            }

            addView(markerView(POPUP_MARKER))
            addView(
                Button(context).apply {
                    text = POPUP_ACTION_MARKER
                    contentDescription = POPUP_ACTION_MARKER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setOnClickListener {
                        actionClickedMarkerView.visibility = View.VISIBLE
                    }
                },
            )
        }

        lateinit var popup: PopupWindow
        popup = PopupWindow(
            popupContent,
            dp(320),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            focusable,
        ).apply {
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            elevation = dp(8).toFloat()
            setOnDismissListener {
                if (popupWindow === popup) {
                    popupWindow = null
                }
            }
        }

        popupWindow = popup
        popup.showAtLocation(window.decorView, Gravity.CENTER, 0, 0)
    }

    fun dismissPopup() {
        popupWindow?.dismiss()
    }

    override fun onDestroy() {
        popupWindow?.dismiss()
        popupWindow = null
        super.onDestroy()
    }

    private fun markerView(marker: String): TextView = TextView(this).apply {
        text = marker
        contentDescription = marker
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val UNDERLYING_MARKER = "mobilerun-popup-underlying"
        const val POPUP_MARKER = "mobilerun-popup-window"
        const val POPUP_ACTION_MARKER = "mobilerun-popup-action"
        const val ACTION_CLICKED_MARKER = "mobilerun-popup-action-clicked"
    }
}
