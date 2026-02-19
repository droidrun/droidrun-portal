package com.droidrun.portal.ui.settings

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.databinding.ActivitySettingsBinding
import com.droidrun.portal.events.model.EventType
import android.provider.Settings
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.droidrun.portal.service.DroidrunNotificationListener
import com.droidrun.portal.service.ReverseConnectionService

import com.droidrun.portal.ui.addWhitespaceStrippingWatcher
import com.droidrun.portal.state.ConnectionState
import com.droidrun.portal.state.ConnectionStateManager
import com.droidrun.portal.state.AppVisibilityTracker

class SettingsActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var binding: ActivitySettingsBinding

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        binding.switchPostNotifications.isChecked = isGranted
        if (isGranted) {
            android.widget.Toast.makeText(
                this,
                "Notification permission granted",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager.getInstance(this)

        setupToolbar()
        setupServerSettings()
        setupWebSocketSettings()
        setupReverseConnectionSettings()
        setupPermissions()
        setupEventFilters()

        setupConnectionStateObserver()
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionSwitches()
    }

    override fun onStart() {
        super.onStart()
        AppVisibilityTracker.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        AppVisibilityTracker.setForeground(false)
    }

    private fun setupServerSettings() {
        // HTTP Server
        binding.switchSocketServerEnabled.isChecked = configManager.socketServerEnabled
        binding.switchSocketServerEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.setSocketServerEnabledWithNotification(isChecked)
        }

        binding.inputSocketServerPort.setText(configManager.socketServerPort.toString())
        binding.inputSocketServerPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val port = v.text.toString().toIntOrNull()
                if (port != null && port in MIN_PORT..MAX_PORT) {
                    configManager.setSocketServerPortWithNotification(port)
                    binding.inputSocketServerPort.clearFocus()
                } else {
                    binding.inputSocketServerPort.error = "Invalid Port"
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupWebSocketSettings() {
        binding.switchWsEnabled.isChecked = configManager.websocketEnabled
        binding.switchWsEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.setWebSocketEnabledWithNotification(isChecked)
        }

        binding.inputWsPort.setText(configManager.websocketPort.toString())
        binding.inputWsPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val port = v.text.toString().toIntOrNull()
                if (port != null && port in MIN_PORT..MAX_PORT) {
                    configManager.setWebSocketPortWithNotification(port)
                    binding.inputWsPort.clearFocus()
                } else {
                    binding.inputWsPort.error = "Invalid Port"
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupReverseConnectionSettings() {
        binding.switchReverseEnabled.isChecked = configManager.reverseConnectionEnabled
        binding.inputReverseUrl.setText(configManager.reverseConnectionUrl)
        binding.inputReverseToken.setText(configManager.reverseConnectionToken)

        binding.inputReverseToken.addWhitespaceStrippingWatcher()

        // Toggle Service on Switch Change
        binding.switchReverseEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.reverseConnectionEnabled = isChecked

            val intent = Intent(
                this,
                ReverseConnectionService::class.java,
            )
            if (isChecked) {
                // Ensure URL is saved before starting
                val url = binding.inputReverseUrl.text.toString().ifBlank {
                    configManager.reverseConnectionUrlOrDefault
                }

                val apiKey = binding.inputReverseToken.text.toString().replace("\\s+".toRegex(), "")
                if (apiKey.isNotBlank() && !apiKey.startsWith(ConfigManager.API_KEY_PREFIX)) {
                    binding.inputReverseToken.error = "API key must start with ${ConfigManager.API_KEY_PREFIX}"
                    binding.switchReverseEnabled.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (apiKey.isNotBlank() && apiKey.length != ConfigManager.API_KEY_LENGTH) {
                    binding.inputReverseToken.error = "Invalid API key length (expected ${ConfigManager.API_KEY_LENGTH}, got ${apiKey.length})"
                    binding.switchReverseEnabled.isChecked = false
                    return@setOnCheckedChangeListener
                }

                configManager.reverseConnectionUrl = url
                configManager.reverseConnectionToken = apiKey
                startForegroundService(intent)
            } else {
                intent.action = ReverseConnectionService.ACTION_DISCONNECT
                startService(intent)
            }
        }

        binding.inputReverseUrl.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                configManager.reverseConnectionUrl = v.text.toString()
                if (actionId == EditorInfo.IME_ACTION_DONE) binding.inputReverseUrl.clearFocus()
                restartServiceIfEnabled()
                true
            } else {
                false
            }
        }

        binding.inputReverseToken.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val apiKey = v.text.toString().replace("\\s+".toRegex(), "")
                if (apiKey.isNotBlank() && !apiKey.startsWith(ConfigManager.API_KEY_PREFIX)) {
                    binding.inputReverseToken.error = "API key must start with ${ConfigManager.API_KEY_PREFIX}"
                } else if (apiKey.isNotBlank() && apiKey.length != ConfigManager.API_KEY_LENGTH) {
                    binding.inputReverseToken.error = "Invalid API key length (expected ${ConfigManager.API_KEY_LENGTH}, got ${apiKey.length})"
                } else {
                    configManager.reverseConnectionToken = apiKey
                    binding.inputReverseToken.clearFocus()
                    restartServiceIfEnabled()
                }
                true
            } else {
                false
            }
        }

        // Screen Share Auto-Accept
        binding.switchScreenShareAutoAccept.isChecked = configManager.screenShareAutoAcceptEnabled
        binding.switchScreenShareAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            configManager.screenShareAutoAcceptEnabled = isChecked
        }

        // Install Auto-Accept
        binding.switchInstallAutoAccept.isChecked = configManager.installAutoAcceptEnabled
        binding.switchInstallAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            configManager.installAutoAcceptEnabled = isChecked
        }
    }

    private fun setupPermissions() {
        updatePermissionSwitches()

        binding.switchNotificationAccess.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
                android.widget.Toast.makeText(
                    this,
                    "Please grant Notification Access to Droidrun Portal",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "Error opening settings",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            // Revert visual state until onResume confirms change
            binding.switchNotificationAccess.isChecked = !binding.switchNotificationAccess.isChecked
        }

        binding.switchPostNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (binding.switchPostNotifications.isChecked) {
                    // User wants to enable
                    requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // User wants to disable - must go to settings
                    try {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    // Revert visual state
                    binding.switchPostNotifications.isChecked = true
                }
            }
        }

        binding.switchInstallUnknownApps.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to security settings
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
            // Revert visual state until onResume confirms change
            binding.switchInstallUnknownApps.isChecked = !binding.switchInstallUnknownApps.isChecked
        }
    }

    private fun setupEventFilters() {
        setupEventToggle(binding.switchEventNotification, EventType.NOTIFICATION)
    }

    private fun setupConnectionStateObserver() {
        ConnectionStateManager.connectionState.observe(this) { state ->
            // Reflect state in the switch (e.g. if it disconnects due to error, switch should turn off?)
            // Or just keep the switch as "enabled preference" vs "actual state".
            // For now, let's keep the switch reflecting the preference, but we could add a status indicator if needed.
            // If the service disconnects and gives up, we might want to update the switch.

            if (state == ConnectionState.DISCONNECTED && configManager.reverseConnectionEnabled) {
                // Maybe show a toast or update UI to show retry?
                // For now, the toggle listener handles user intent.
            }
        }
    }

    private fun restartServiceIfEnabled() {
        if (configManager.reverseConnectionEnabled) {
            val intent = Intent(
                this,
                ReverseConnectionService::class.java,
            )
            stopService(intent)
            startForegroundService(intent)
        }
    }

    private fun setupEventToggle(
        switch: com.google.android.material.switchmaterial.SwitchMaterial,
        type: EventType,
    ) {
        switch.isChecked = configManager.isEventEnabled(type)

        switch.setOnCheckedChangeListener { _, isChecked ->
            configManager.setEventEnabled(type, isChecked)
        }
    }

    private fun updatePermissionSwitches() {
        // Notification Access
        binding.switchNotificationAccess.isChecked = isNotificationServiceEnabled()

        // Post Notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted =
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            binding.switchPostNotifications.isChecked = isGranted
            binding.switchPostNotifications.isEnabled = true
        } else {
            // Pre-Tiramisu, permission is granted at install time
            binding.switchPostNotifications.isChecked = true
            binding.switchPostNotifications.isEnabled = false
        }

        // Install Unknown Apps
        binding.switchInstallUnknownApps.isChecked = packageManager.canRequestPackageInstalls()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val componentName = ComponentName(this, DroidrunNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }

    companion object {
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}
