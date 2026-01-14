package com.droidrun.portal.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.droidrun.portal.R
import com.droidrun.portal.databinding.DialogPermissionRequiredBinding

/**
 * A transparent activity that displays a styled permission dialog.
 * Used when certain permissions are required before an operation can proceed.
 */
class PermissionDialogActivity : AppCompatActivity() {

    private lateinit var binding: DialogPermissionRequiredBinding

    companion object {
        const val EXTRA_PERMISSION_TYPE = "permission_type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STEPS = "steps"

        const val PERMISSION_INSTALL_UNKNOWN_APPS = "install_unknown_apps"

        /**
         * Creates an intent to show the install unknown apps permission dialog.
         */
        fun createInstallPermissionIntent(context: Context): Intent {
            return Intent(context, PermissionDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_PERMISSION_TYPE, PERMISSION_INSTALL_UNKNOWN_APPS)
                putExtra(EXTRA_TITLE, context.getString(R.string.permission_required_title))
                putExtra(EXTRA_MESSAGE, context.getString(R.string.install_permission_message))
                putExtra(EXTRA_STEPS, context.getString(R.string.install_permission_steps))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPermissionRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Make the activity background semi-transparent
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.6f)

        setupDialog()
        setupClickListeners()
    }

    private fun setupDialog() {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.permission_required_title)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.install_permission_message)
        val steps = intent.getStringExtra(EXTRA_STEPS) ?: getString(R.string.install_permission_steps)

        binding.dialogTitle.text = title
        binding.dialogMessage.text = message
        binding.dialogSteps.text = steps
    }

    private fun setupClickListeners() {
        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnOpenSettings.setOnClickListener {
            openPermissionSettings()
        }

        // Allow closing by tapping outside the dialog area (on the dimmed background)
        binding.dialogRoot.setOnClickListener {
            finish()
        }

        // Prevent clicks on the dialog card from closing
        binding.dialogCard.setOnClickListener {
            // Consume click - do nothing
        }
    }

    private fun openPermissionSettings() {
        val permissionType = intent.getStringExtra(EXTRA_PERMISSION_TYPE)

        when (permissionType) {
            PERMISSION_INSTALL_UNKNOWN_APPS -> {
                try {
                    // Open the specific "Install unknown apps" settings for this app
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general security settings
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (_: Exception) {
                        // Last resort: open app details
                        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(detailsIntent)
                    }
                }
            }
        }

        finish()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}

