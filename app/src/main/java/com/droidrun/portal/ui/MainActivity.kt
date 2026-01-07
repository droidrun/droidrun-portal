package com.droidrun.portal.ui

import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.service.DroidrunAccessibilityService
import com.droidrun.portal.service.DroidrunNotificationListener
import com.droidrun.portal.state.ConnectionState
import com.droidrun.portal.state.ConnectionStateManager
import com.droidrun.portal.service.ReverseConnectionService

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import android.widget.Toast
import android.provider.Settings
import android.view.View
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.graphics.Color
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import com.droidrun.portal.databinding.ActivityMainBinding
import com.droidrun.portal.ui.settings.SettingsActivity
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt

import android.content.BroadcastReceiver
import android.content.IntentFilter

class MainActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var binding: ActivityMainBinding

    private var responseText: String = ""

    // Endpoints collapsible section
    private var isEndpointsExpanded = false

    // Flag to prevent infinite update loops
    private var isProgrammaticUpdate = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Constants for the position offset slider
    companion object {
        private const val DEFAULT_OFFSET = 0
        private const val MIN_OFFSET = -256
        private const val MAX_OFFSET = 256
        private const val SLIDER_RANGE = MAX_OFFSET - MIN_OFFSET
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register ConfigChangeListener
        ConfigManager.getInstance(this).addListener(this)

        // Handle Deep Link
        handleDeepLink(intent)

        setupNetworkInfo()

        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Set app version
        setAppVersion()

        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()

        // Update initial UI state
        updateSocketServerStatus()
        updateAdbForwardCommand()

        binding.btnConnectCloud.setOnClickListener {
            val currentState = ConnectionStateManager.getState()
            if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING || currentState == ConnectionState.RECONNECTING) {
                // Ignore click if already connected/connecting, handled by disconnect button inside card
                return@setOnClickListener
            }

            val configManager = ConfigManager.getInstance(this)
            val deviceId = configManager.deviceID
            val url = "https://dev-cloud.droidrun.ai/auth/device?deviceId=$deviceId"

            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.btnDisconnect.setOnClickListener {
            disconnectService()
        }

        binding.btnCancelConnection.setOnClickListener {
            disconnectService()
        }

        // Configure endpoints collapsible section
        setupEndpointsCollapsible()

        binding.fetchButton.setOnClickListener {
            fetchElementData()
        }

        binding.toggleOverlay.setOnCheckedChangeListener { _, isChecked ->
            toggleOverlayVisibility(isChecked)
        }

        binding.btnResetOffset.setOnClickListener {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                // Force re-calculation
                accessibilityService.setAutoOffsetEnabled(true)

                // Update UI with the new calculated value
                val newOffset = accessibilityService.getOverlayOffset()
                updateOffsetSlider(newOffset)
                updateOffsetInputField(newOffset)

                Toast.makeText(this, "Auto-offset reset: $newOffset", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Service not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup enable accessibility button
        binding.enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        // Setup logs link to show dialog
        binding.logsLink.setOnClickListener {
            showLogsDialog()
        }

        // Check initial accessibility status and sync UI
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
        setupConnectionStateObserver()
        updateProductionModeUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        ConfigManager.getInstance(this).removeListener(this)
    }

    override fun onResume() {
        super.onResume()
        // Update the status indicators when app resumes
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
        updateProductionModeUI()
    }

    private fun updateProductionModeUI() {
        val configManager = ConfigManager.getInstance(this)
        if (configManager.productionMode) {
            binding.layoutStandardUi.visibility = View.GONE
            binding.layoutProductionMode.visibility = View.VISIBLE
            binding.textProductionDeviceId.text = "Device ID: ${configManager.deviceID}"
        } else {
            binding.layoutStandardUi.visibility = View.VISIBLE
            binding.layoutProductionMode.visibility = View.GONE
        }
    }

    override fun onOverlayVisibilityChanged(visible: Boolean) {
        runOnUiThread {
            binding.toggleOverlay.isChecked = visible
        }
    }

    override fun onOverlayOffsetChanged(offset: Int) {
        runOnUiThread {
            if (!isProgrammaticUpdate) {
                isProgrammaticUpdate = true
                binding.offsetSlider.progress = offset - MIN_OFFSET
                binding.offsetValueDisplay.setText(offset.toString())
                isProgrammaticUpdate = false
            }
        }
    }

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        // No-op or update UI if needed
    }

    override fun onSocketServerPortChanged(port: Int) {
        // No-op or update UI if needed
    }

    override fun onProductionModeChanged(enabled: Boolean) {
        runOnUiThread {
            updateProductionModeUI()
        }
    }

    private fun disconnectService() {
        val configManager = ConfigManager.getInstance(this)
        configManager.reverseConnectionEnabled = false

        val serviceIntent = Intent(this, ReverseConnectionService::class.java)
        stopService(serviceIntent)

        // Explicitly set state to disconnected
        ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
    }

    private fun setupConnectionStateObserver() {
        ConnectionStateManager.connectionState.observe(this) { state ->
            // Hide all layouts first
            binding.layoutDisconnected.visibility = View.GONE
            binding.layoutConnecting.visibility = View.GONE
            binding.layoutConnected.visibility = View.GONE

            when (state) {
                ConnectionState.CONNECTED -> {
                    binding.layoutConnected.visibility = View.VISIBLE
                    val configManager = ConfigManager.getInstance(this)
                    binding.textDeviceId.text = "Device ID: ${configManager.deviceID}"
                }
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                    binding.layoutConnecting.visibility = View.VISIBLE
                    binding.textConnectingStatus.text = if (state == ConnectionState.RECONNECTING) "Reconnecting..." else "Connecting..."
                    binding.btnCancelConnection.visibility = View.VISIBLE
                }
                else -> {
                    binding.layoutDisconnected.visibility = View.VISIBLE
                    binding.btnCancelConnection.visibility = View.GONE
                }
            }
        }
    }

    private fun setupNetworkInfo() {
        val configManager = ConfigManager.getInstance(this)

        binding.authTokenText.text = configManager.authToken

        binding.btnCopyToken.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("Auth Token", configManager.authToken)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Token copied", Toast.LENGTH_SHORT).show()
        }

        binding.deviceIpText.text = getIpAddress() ?: "Unavailable (Check WiFi)"
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address)
                        return address.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting IP: ${e.message}")
        }
        return null
    }

    private fun updateStatusIndicators() {
        updateAccessibilityStatusIndicator()
    }

    private fun syncUIWithAccessibilityService() {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // Sync overlay toggle
            binding.toggleOverlay.isChecked = accessibilityService.isOverlayVisible()

            // Sync offset controls - show actual applied offset
            val displayOffset = accessibilityService.getOverlayOffset()
            updateOffsetSlider(displayOffset)
            updateOffsetInputField(displayOffset)
        }
    }

    private fun setupOffsetSlider() {
        // Initialize the slider with the new range
        binding.offsetSlider.max = SLIDER_RANGE

        // Get initial value from service if available, otherwise use default
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        val initialOffset = accessibilityService?.getOverlayOffset() ?: DEFAULT_OFFSET

        // Convert the initial offset to slider position
        val initialSliderPosition = initialOffset - MIN_OFFSET
        binding.offsetSlider.progress = initialSliderPosition

        // Set listener for slider changes
        binding.offsetSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert slider position back to actual offset value (range -256 to +256)
                val offsetValue = progress + MIN_OFFSET

                // Update input field to match slider (only when user is sliding)
                if (fromUser) {
                    updateOffsetInputField(offsetValue)
                    updateOverlayOffset(offsetValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Final update when user stops sliding
                val offsetValue = seekBar?.progress?.plus(MIN_OFFSET) ?: DEFAULT_OFFSET
                updateOverlayOffset(offsetValue)
            }
        })
    }

    private fun setupOffsetInput() {
        // Get initial value from service if available, otherwise use default
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        val initialOffset = accessibilityService?.getOverlayOffset() ?: DEFAULT_OFFSET

        // Set initial value
        isProgrammaticUpdate = true
        binding.offsetValueDisplay.setText(initialOffset.toString())
        isProgrammaticUpdate = false

        // Apply on enter key
        binding.offsetValueDisplay.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputOffset()
                true
            } else {
                false
            }
        }

        // Input validation and auto-apply
        binding.offsetValueDisplay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // Skip processing if this is a programmatic update
                if (isProgrammaticUpdate) return

                try {
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value !in MIN_OFFSET..MAX_OFFSET) {
                            binding.offsetValueInputLayout.error =
                                "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                        } else {
                            binding.offsetValueInputLayout.error = null
                            // Auto-apply if value is valid and complete
                            if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString()
                                    .startsWith("-"))
                            ) {
                                applyInputOffset()
                            }
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        binding.offsetValueInputLayout.error = "Invalid number"
                    } else {
                        binding.offsetValueInputLayout.error = null
                    }
                } catch (e: Exception) {
                    binding.offsetValueInputLayout.error = "Invalid number"
                }
            }
        })
    }

    private fun applyInputOffset() {
        try {
            val inputText = binding.offsetValueDisplay.text.toString()
            val offsetValue = inputText.toIntOrNull()

            if (offsetValue != null) {
                // Ensure the value is within bounds
                val boundedValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)

                if (boundedValue != offsetValue) {
                    // Update input if we had to bound the value
                    isProgrammaticUpdate = true
                    binding.offsetValueDisplay.setText(boundedValue.toString())
                    isProgrammaticUpdate = false
                    Toast.makeText(this, "Value adjusted to valid range", Toast.LENGTH_SHORT).show()
                }

                // Update slider to match and apply the offset
                val sliderPosition = boundedValue - MIN_OFFSET
                binding.offsetSlider.progress = sliderPosition
                updateOverlayOffset(boundedValue)
            } else {
                // Invalid input
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error applying input offset: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOffsetSlider(currentOffset: Int) {
        // Ensure the offset is within our new bounds
        val boundedOffset = currentOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)

        // Update the slider to match the current offset from the service
        val sliderPosition = boundedOffset - MIN_OFFSET
        binding.offsetSlider.progress = sliderPosition
    }

    private fun updateOffsetInputField(currentOffset: Int) {
        // Set flag to prevent TextWatcher from triggering
        isProgrammaticUpdate = true

        // Update the text input to match the current offset
        binding.offsetValueDisplay.setText(currentOffset.toString())

        // Reset flag
        isProgrammaticUpdate = false
    }

    private fun updateOverlayOffset(offsetValue: Int) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayOffset(offsetValue)
                if (success) {
                    Log.d("DROIDRUN_MAIN", "Offset updated successfully: $offsetValue")
                } else {
                    Log.e("DROIDRUN_MAIN", "Failed to update offset: $offsetValue")
                }
            } else {
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for offset update")
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating offset: ${e.message}")
        }
    }

    private fun fetchElementData() {
        try {
            // Use ContentProvider to get combined state (a11y tree + phone state)
            val uri = Uri.parse("content://com.droidrun.portal/state")

            val cursor = contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val jsonResponse = JSONObject(result)

                    if (jsonResponse.getString("status") == "success") {
                        val data = jsonResponse.getString("data")
                        responseText = data
                        Toast.makeText(
                            this,
                            "Combined state received successfully!",
                            Toast.LENGTH_SHORT
                        ).show()

                        Log.d(
                            "DROIDRUN_MAIN",
                            "Combined state data received: ${
                                data.take(100.coerceAtMost(data.length))
                            }...",
                        )
                    } else {
                        val error = jsonResponse.getString("error")
                        responseText = "Error: $error"
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error fetching combined state data: ${e.message}")
            Toast.makeText(this, "Error fetching data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOverlayVisibility(visible: Boolean) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayVisible(visible)
                if (success) {
                    Log.d("DROIDRUN_MAIN", "Overlay visibility toggled to: $visible")
                } else {
                    Log.e("DROIDRUN_MAIN", "Failed to toggle overlay visibility")
                }
            } else {
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for overlay toggle")
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error toggling overlay: ${e.message}")
        }
    }

    private fun fetchPhoneStateData() {
        try {
            // Use ContentProvider to get phone state
            val uri = "content://com.droidrun.portal/".toUri()
            val command = JSONObject().apply {
                put("action", "phone_state")
            }

            val cursor = contentResolver.query(
                uri,
                null,
                command.toString(),
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val jsonResponse = JSONObject(result)

                    if (jsonResponse.getString("status") == "success") {
                        val data = jsonResponse.getString("data")
                        // responseText.text = data
                        Toast.makeText(
                            this,
                            "Phone state received successfully!",
                            Toast.LENGTH_SHORT
                        ).show()

                        Log.d(
                            "DROIDRUN_MAIN",
                            "Phone state received: ${
                                data.take(100.coerceAtMost(data.length))
                            }...",
                        )
                    } else {
                        val error = jsonResponse.getString("error")
                        // responseText.text = error
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error fetching phone state: ${e.message}")
            Toast.makeText(this, "Error fetching phone state: ${e.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    // Check if the accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName =
            packageName + "/" + DroidrunAccessibilityService::class.java.canonicalName

        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            return enabledServices?.contains(accessibilityServiceName) == true
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error checking accessibility status: ${e.message}")
            return false
        }
    }

    // Check if notification listener permission is enabled
    // Note: Used in settings sheet logic now, but keeping here or moving to shared utility would be better
    // Leaving for now as it was part of previous logic
    private fun isNotificationServiceEnabled(): Boolean {
        val componentName = ComponentName(this, DroidrunNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }

    // Open accessibility settings to enable the service
    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            // Show enabled card, hide banner
            // TODO add ext functions, makeVisible, makeInvisible, makeVisibleIf, makeVisibleIfElse etc.
            binding.accessibilityStatusEnabled.visibility = View.VISIBLE
            binding.accessibilityBanner.visibility = View.GONE
        } else {
            // Show banner, hide enabled card
            binding.accessibilityStatusEnabled.visibility = View.GONE
            binding.accessibilityBanner.visibility = View.VISIBLE
        }
    }

    // Open accessibility settings to enable the service
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please enable Droidrun Portal in Accessibility Services",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error opening accessibility settings: ${e.message}")
            Toast.makeText(
                this,
                "Error opening accessibility settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please grant Notification Access to Droidrun Portal",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error opening notification settings: ${e.message}")
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSocketServerPort(port: Int) {
        try {
            val configManager = ConfigManager.getInstance(this)
            configManager.setSocketServerPortWithNotification(port)

            updateAdbForwardCommand()

            // Give the server a moment to restart, then update the status
            // TODO const
            mainHandler.postDelayed({
                updateSocketServerStatus()
            }, 1000)

            Log.d("DROIDRUN_MAIN", "Socket server port updated: $port")
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating socket server port: ${e.message}")
        }
    }

    private fun updateSocketServerStatus() {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val status = accessibilityService.getSocketServerStatus()
                binding.socketServerStatus.text = status
                binding.socketServerStatus.setTextColor("#00FFA6".toColorInt())
            } else {
                binding.socketServerStatus.text = "Service not available"
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating socket server status: ${e.message}")
            binding.socketServerStatus.text = "Error"
        }
    }

    private fun updateAdbForwardCommand() {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val command = accessibilityService.getAdbForwardCommand()
                binding.adbForwardCommand.text = command
            } else {
                val configManager = ConfigManager.getInstance(this)
                val port = configManager.socketServerPort
                binding.adbForwardCommand.text = "adb forward tcp:$port tcp:$port"
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating ADB forward command: ${e.message}")
            binding.adbForwardCommand.text = "Error"
        }
    }

    private fun setupEndpointsCollapsible() {
        binding.endpointsHeader.setOnClickListener {
            isEndpointsExpanded = !isEndpointsExpanded

            if (isEndpointsExpanded) {
                binding.endpointsContent.visibility = View.VISIBLE
                binding.endpointsArrow.rotation = 90f
            } else {
                binding.endpointsContent.visibility = View.GONE
                binding.endpointsArrow.rotation = 0f
            }
        }
    }

    private fun setAppVersion() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val version = packageInfo.versionName
            binding.versionText.text = "Version: $version"
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error getting app version: ${e.message}")
            binding.versionText.text = "Version: N/A"
        }
    }

    private fun showLogsDialog() {
        try {
            val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_item, null)

            // Create a scrollable TextView for the logs
            val scrollView = androidx.core.widget.NestedScrollView(this)
            val textView = TextView(this).apply {
                text = responseText.ifEmpty { "No logs available. Fetch data first." }
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(40, 40, 40, 40)
                setTextIsSelectable(true)
            }
            scrollView.addView(textView)

            AlertDialog.Builder(this)
                .setTitle("Response Logs")
                .setView(scrollView)
                .setPositiveButton("Close") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Response Logs", responseText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .create()
                .apply {
                    window?.setBackgroundDrawableResource(android.R.color.background_dark)
                }
                .show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error showing logs dialog: ${e.message}")
            Toast.makeText(this, "Error showing logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        try {
            val data: Uri? = intent?.data
            if (data != null && data.scheme == "droidrun" && data.host == "auth-callback") {
                val token = data.getQueryParameter("token")
                val url = data.getQueryParameter("url")

                if (!token.isNullOrEmpty() && !url.isNullOrEmpty()) {
                    val configManager = ConfigManager.getInstance(this)
                    configManager.reverseConnectionToken = token
                    configManager.reverseConnectionUrl = url
                    configManager.reverseConnectionEnabled = true

                    // Start Service
                    val serviceIntent = Intent(this, com.droidrun.portal.service.ReverseConnectionService::class.java)
                    stopService(serviceIntent)
                    startService(serviceIntent)
                } else {
                    Toast.makeText(this, "Invalid connection data received", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error handling deep link: ${e.message}")
        }
    }
}
