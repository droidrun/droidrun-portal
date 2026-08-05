package com.mobilerun.portal.core

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only target for deterministic deep-link RPC verification. */
class DeepLinkFixtureActivity : Activity() {

    private lateinit var actionView: TextView
    private lateinit var dataView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(Color.WHITE)

                addView(markerView(FIXTURE_MARKER))
                actionView = markerView("")
                addView(actionView)
                dataView = markerView("")
                addView(dataView)
            },
        )
        renderIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderIntent(intent)
    }

    private fun renderIntent(intent: Intent) {
        val action = intent.action.orEmpty()
        val data = intent.dataString.orEmpty()
        actionView.text = "$ACTION_MARKER$action"
        actionView.contentDescription = "$ACTION_MARKER$action"
        dataView.text = "$DATA_MARKER$data"
        dataView.contentDescription = "$DATA_MARKER$data"
    }

    private fun markerView(marker: String): TextView = TextView(this).apply {
        text = marker
        contentDescription = marker
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val FIXTURE_MARKER = "mobilerun-deep-link-fixture"
        const val ACTION_MARKER = "mobilerun-deep-link-action:"
        const val DATA_MARKER = "mobilerun-deep-link-data:"
    }
}
