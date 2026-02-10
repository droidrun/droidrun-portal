package com.droidrun.portal.ui

import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.service.DroidrunAccessibilityService
import com.droidrun.portal.state.ConnectionState
import com.droidrun.portal.state.ConnectionStateManager
import com.droidrun.portal.service.ReverseConnectionService
import com.droidrun.portal.input.DroidrunKeyboardIME
import android.view.inputmethod.InputMethodManager

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
import android.os.Build
import android.net.Uri
import android.graphics.Color
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import android.content.ClipboardManager
import com.droidrun.portal.databinding.ActivityMainBinding
import com.droidrun.portal.ui.settings.SettingsActivity
import com.droidrun.portal.state.AppVisibilityTracker
import androidx.core.graphics.toColorInt

import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.droidrun.portal.R
import com.droidrun.portal.api.ApiHandler
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var binding: ActivityMainBinding

    private var responseText: String = ""

    // Endpoints collapsible section
    private var isEndpointsExpanded = false

    private var isProgrammaticUpdate = false
    private var isInstallReceiverRegistered = false

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApiHandler.ACTION_INSTALL_RESULT) return
            val success = intent.getBooleanExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, false)
            val message =
                intent.getStringExtra(ApiHandler.EXTRA_INSTALL_MESSAGE)
                    ?: "App installed successfully"
            showInstallSnackbar(message, success)
        }
    }

    // Constants for the position offset slider
    companion object {
        private const val TAG = "MainActivity"
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
            val url = "https://cloud.mobilerun.ai/auth/device?deviceId=$deviceId"

            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnConnectCloud.setOnLongClickListener {
            val currentState = ConnectionStateManager.getState()
            if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING || currentState == ConnectionState.RECONNECTING) {
                return@setOnLongClickListener false
            }
            showCustomConnectionDialog()
            true
        }

        binding.btnDisconnect.setOnClickListener {
            disconnectService()
        }

        binding.btnCancelConnection.setOnClickListener {
            disconnectService()
        }

        binding.btnRetryConnection.setOnClickListener {
            retryConnection()
        }

        binding.btnDismissError.setOnClickListener {
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
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

        // Setup enable keyboard button
        binding.enableKeyboardButton.setOnClickListener {
            openKeyboardSettings()
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

    override fun onStart() {
        super.onStart()
        AppVisibilityTracker.setForeground(true)
        registerInstallResultReceiver()
    }

    override fun onResume() {
        super.onResume()
        // Update the status indicators when app resumes
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
        updateProductionModeUI()
    }

    override fun onStop() {
        super.onStop()
        unregisterInstallResultReceiver()
        AppVisibilityTracker.setForeground(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Update keyboard warning when window regains focus (e.g., after IME picker closes)
        if (hasFocus) {
            updateKeyboardWarningBanner()
        }
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

    private fun showInstallSnackbar(message: String, success: Boolean) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (!success) {
            snackbar.setBackgroundTint("#D32F2F".toColorInt())
        }
        val textView = snackbar.view.findViewById<TextView>(
            com.google.android.material.R.id.snackbar_text
        )
        textView.maxLines = 4
        textView.ellipsize = null
        textView.isSingleLine = false
        snackbar.show()
    }

    private fun registerInstallResultReceiver() {
        if (isInstallReceiverRegistered) return
        val filter = IntentFilter(ApiHandler.ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(installResultReceiver, filter)
        }
        isInstallReceiverRegistered = true
    }

    private fun unregisterInstallResultReceiver() {
        if (!isInstallReceiverRegistered) return
        try {
            unregisterReceiver(installResultReceiver)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to unregister install receiver", e)
        } finally {
            isInstallReceiverRegistered = false
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
        ConfigManager.getInstance(this).reverseConnectionEnabled = false

        val serviceIntent =
            Intent(this, ReverseConnectionService::class.java).apply {
                action = ReverseConnectionService.ACTION_DISCONNECT
            }
        startService(serviceIntent)

        ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
    }

    private fun retryConnection() {
        val configManager = ConfigManager.getInstance(this)
        configManager.reverseConnectionEnabled = true

        val serviceIntent = Intent(this, ReverseConnectionService::class.java)
        stopService(serviceIntent)

        Handler(Looper.getMainLooper()).postDelayed({
            startForegroundService(serviceIntent)
        }, 150)
    }

    private fun showCustomConnectionDialog() {
        Log.d(TAG, "showCustomConnectionDialog: Opening dialog")
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_connection, null)
        val inputUrl = dialogView.findViewById<TextInputEditText>(R.id.input_custom_url)
        val inputToken = dialogView.findViewById<TextInputEditText>(R.id.input_custom_token)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
        val btnConnect = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_connect)

        val configManager = ConfigManager.getInstance(this)

        // Pre-fill with existing values if any
        val existingUrl = configManager.reverseConnectionUrl
        Log.d(TAG, "showCustomConnectionDialog: Existing URL='$existingUrl'")
        if (existingUrl.isNotBlank()) {
            inputUrl.setText(existingUrl)
        }
        val existingToken = configManager.reverseConnectionToken
        Log.d(TAG, "showCustomConnectionDialog: Existing token length=${existingToken.length}")
        if (existingToken.isNotBlank()) {
            inputToken.setText(existingToken)
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_DroidrunPortal_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(R.color.background_card)

        btnCancel.setOnClickListener {
            Log.d(TAG, "showCustomConnectionDialog: Cancel clicked")
            dialog.dismiss()
        }

        btnConnect.setOnClickListener {
            val url = inputUrl.text?.toString()?.trim() ?: ""
            val token = inputToken.text?.toString()?.trim() ?: ""

            Log.d(TAG, "showCustomConnectionDialog: Connect clicked, URL='$url', token length=${token.length}")

            if (url.isBlank()) {
                Log.w(TAG, "showCustomConnectionDialog: URL is blank")
                Toast.makeText(this, "Please enter a WebSocket URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                Log.w(TAG, "showCustomConnectionDialog: Invalid URL scheme")
                Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Log.d(TAG, "showCustomConnectionDialog: Saving config...")
            // Save configuration
            configManager.reverseConnectionUrl = url
            configManager.reverseConnectionToken = token
            configManager.reverseConnectionEnabled = true
            Log.d(TAG, "showCustomConnectionDialog: Config saved, reverseConnectionEnabled=${configManager.reverseConnectionEnabled}")

            // Stop existing service and restart
            Log.d(TAG, "showCustomConnectionDialog: Stopping existing service...")
            val serviceIntent = Intent(this, ReverseConnectionService::class.java)
            stopService(serviceIntent)

            Log.d(TAG, "showCustomConnectionDialog: Scheduling service start in 150ms...")
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "showCustomConnectionDialog: Starting foreground service now")
                startForegroundService(serviceIntent)
                Log.d(TAG, "showCustomConnectionDialog: startForegroundService() called")
            }, 150)

            dialog.dismiss()
            Toast.makeText(this, "Connecting to custom server...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        Log.d(TAG, "showCustomConnectionDialog: Dialog shown")
    }

    private fun setupConnectionStateObserver() {
        ConnectionStateManager.connectionState.observe(this) { state ->
            binding.layoutDisconnected.visibility = View.GONE
            binding.layoutConnecting.visibility = View.GONE
            binding.layoutConnected.visibility = View.GONE
            binding.layoutError.visibility = View.GONE

            when (state) {
                ConnectionState.CONNECTED -> {
                    binding.layoutConnected.visibility = View.VISIBLE
                    binding.textDeviceId.text =
                        "Device ID: ${ConfigManager.getInstance(this).deviceID}"
                }

                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                    binding.layoutConnecting.visibility = View.VISIBLE
                    if (state == ConnectionState.RECONNECTING) {
                        binding.textConnectingStatus.text = "Reconnecting..."
                        binding.textConnectingSubtitle.text =
                            getString(R.string.reconnecting_subtitle)
                    } else {
                        binding.textConnectingStatus.text = "Connecting..."
                        binding.textConnectingSubtitle.text =
                            getString(R.string.connecting_subtitle)
                    }
                }

                ConnectionState.UNAUTHORIZED -> {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.textErrorSubtitle.text = getString(R.string.error_unauthorized)
                }

                ConnectionState.LIMIT_EXCEEDED -> {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.textErrorSubtitle.text = getString(R.string.error_limit_exceeded)
                }

                ConnectionState.BAD_REQUEST -> {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.textErrorSubtitle.text = getString(R.string.error_bad_request)
                }

                ConnectionState.ERROR -> {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.textErrorSubtitle.text = getString(R.string.error_unknown)
                }

                else -> {
                    binding.layoutDisconnected.visibility = View.VISIBLE
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
        updateKeyboardWarningBanner()
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
                        val data = jsonResponse.getString("result")
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

    // Update keyboard warning banner visibility
    private fun updateKeyboardWarningBanner() {
        val isKeyboardSelected = isKeyboardSelected()
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()

        // Only show the keyboard warning if accessibility is enabled (so the app is functional)
        // but the keyboard IME is not selected as active input method (so we're using fallback input)
        if (isAccessibilityEnabled && !isKeyboardSelected) {
            binding.keyboardWarningBanner.visibility = View.VISIBLE
        } else {
            binding.keyboardWarningBanner.visibility = View.GONE
        }
    }

    // Check if DroidrunKeyboardIME is selected as the active input method
    private fun isKeyboardSelected(): Boolean {
        return DroidrunKeyboardIME.isSelected(this)
    }

    // Open keyboard/input method settings
    private fun openKeyboardSettings() {
        // First check if keyboard is enabled but not selected - show input method picker
        val isEnabled = isKeyboardEnabled()

        if (isEnabled) {
            // Keyboard is enabled, just needs to be selected - show the IME picker
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error showing IME picker: ${e.message}")
                // Fallback to settings
                openInputMethodSettings()
            }
        } else {
            // Keyboard is not enabled - go to settings to enable it first
            openInputMethodSettings()
        }
    }

    // Check if DroidrunKeyboardIME is enabled (in the list of available keyboards)
    private fun isKeyboardEnabled(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val enabledInputMethods = imm.enabledInputMethodList

            enabledInputMethods.any {
                it.packageName == packageName && it.serviceName.contains("DroidrunKeyboardIME")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking keyboard enabled status: ${e.message}")
            false
        }
    }

    // Open input method settings
    private fun openInputMethodSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Enable Droidrun Keyboard, then select it as your keyboard",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening keyboard settings: ${e.message}")
            Toast.makeText(
                this,
                "Error opening keyboard settings",
                Toast.LENGTH_SHORT
            ).show()
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

                    // Restart Service with delay to avoid race condition
                    val serviceIntent = Intent(
                        this,
                        com.droidrun.portal.service.ReverseConnectionService::class.java
                    )
                    stopService(serviceIntent)
                    Handler(Looper.getMainLooper()).postDelayed({
                        startForegroundService(serviceIntent)
                    }, 150)
                } else {
                    Toast.makeText(this, "Invalid connection data received", Toast.LENGTH_LONG)
                        .show()
                }
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error handling deep link: ${e.message}")
        }
    }
}
