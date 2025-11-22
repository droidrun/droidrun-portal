package com.droidrun.portal.ui

import com.droidrun.portal.R
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.service.DroidrunAccessibilityService
import com.droidrun.portal.service.DroidrunNotificationListener

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
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.provider.Settings
import android.widget.ImageView
import android.view.View
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.database.Cursor
import android.graphics.Color
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import com.droidrun.portal.ui.settings.SettingsBottomSheet

class MainActivity : AppCompatActivity() {
    
    private lateinit var accessibilityBanner: com.google.android.material.card.MaterialCardView
    private lateinit var accessibilityStatusEnabled: com.google.android.material.card.MaterialCardView
    private lateinit var enableAccessibilityButton: MaterialButton
    
    // Notification UI elements
    private lateinit var notificationBanner: com.google.android.material.card.MaterialCardView
    private lateinit var notificationStatusEnabled: com.google.android.material.card.MaterialCardView
    private lateinit var enableNotificationButton: MaterialButton
    private lateinit var settingsButton: android.widget.ImageButton
    
    private var responseText: String = ""
    private lateinit var versionText: TextView
    private lateinit var logsLink: TextView
    private lateinit var toggleOverlay: SwitchMaterial
    private lateinit var fetchButton: MaterialButton
    private lateinit var offsetSlider: SeekBar
    private lateinit var offsetValueDisplay: TextInputEditText
    private lateinit var offsetValueInputLayout: TextInputLayout
    private lateinit var btnResetOffset: TextView
    
    // Socket server UI elements
    private lateinit var socketPortInput: TextInputEditText
    private lateinit var socketPortInputLayout: TextInputLayout
    private lateinit var socketServerStatus: TextView
    private lateinit var adbForwardCommand: TextView
    
    // Endpoints collapsible section
    private lateinit var endpointsHeader: View
    private lateinit var endpointsContent: View
    private lateinit var endpointsArrow: ImageView
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
        setContentView(R.layout.activity_main)
        
        // Initialize UI elements
        accessibilityBanner = findViewById(R.id.accessibility_banner)
        accessibilityStatusEnabled = findViewById(R.id.accessibility_status_enabled)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        
        // Notification UI init (try-catch in case XML isn't updated yet to prevent crash)
        try {
            notificationBanner = findViewById(R.id.notification_banner)
            notificationStatusEnabled = findViewById(R.id.notification_status_enabled)
            enableNotificationButton = findViewById(R.id.enable_notification_button)
            
            enableNotificationButton.setOnClickListener {
                openNotificationSettings()
            }
            
            settingsButton = findViewById(R.id.settings_button)
            settingsButton.setOnClickListener {
                SettingsBottomSheet().show(supportFragmentManager, SettingsBottomSheet.TAG)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Notification UI elements not found in layout yet")
        }
        
        versionText = findViewById(R.id.version_text)
        logsLink = findViewById(R.id.logs_link)
        fetchButton = findViewById(R.id.fetch_button)
        toggleOverlay = findViewById(R.id.toggle_overlay)
        offsetSlider = findViewById(R.id.offset_slider)
        offsetValueDisplay = findViewById(R.id.offset_value_display)
        offsetValueInputLayout = findViewById(R.id.offset_value_input_layout)
        btnResetOffset = findViewById(R.id.btn_reset_offset)
        
        // Initialize socket server UI elements
        socketPortInput = findViewById(R.id.socket_port_input)
        socketPortInputLayout = findViewById(R.id.socket_port_input_layout)
        socketServerStatus = findViewById(R.id.socket_server_status)
        adbForwardCommand = findViewById(R.id.adb_forward_command)
        
        // Initialize endpoints collapsible section
        endpointsHeader = findViewById(R.id.endpoints_header)
        endpointsContent = findViewById(R.id.endpoints_content)
        endpointsArrow = findViewById(R.id.endpoints_arrow)
        
        // Set app version
        setAppVersion()
        
        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()
        
        // Configure socket server controls
        setupSocketServerControls()
        
        // Configure endpoints collapsible section
        setupEndpointsCollapsible()
        
        fetchButton.setOnClickListener {
            fetchElementData()
        }

        toggleOverlay.setOnCheckedChangeListener { _, isChecked ->
            toggleOverlayVisibility(isChecked)
        }

        btnResetOffset.setOnClickListener {
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
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // Setup logs link to show dialog
        logsLink.setOnClickListener {
            showLogsDialog()
        }
        
        // Check initial accessibility status and sync UI
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
    }
    
    override fun onResume() {
        super.onResume()
        // Update the status indicators when app resumes
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
    }

    private fun updateStatusIndicators() {
        updateAccessibilityStatusIndicator()
        updateNotificationStatusIndicator()
    }
    
    private fun syncUIWithAccessibilityService() {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // Sync overlay toggle
            toggleOverlay.isChecked = accessibilityService.isOverlayVisible()

            // Sync offset controls - show actual applied offset
            val displayOffset = accessibilityService.getOverlayOffset()
            updateOffsetSlider(displayOffset)
            updateOffsetInputField(displayOffset)
        }
    }
    
    private fun setupOffsetSlider() {
        // Initialize the slider with the new range
        offsetSlider.max = SLIDER_RANGE
        
        // Get initial value from service if available, otherwise use default
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        val initialOffset = accessibilityService?.getOverlayOffset() ?: DEFAULT_OFFSET
        
        // Convert the initial offset to slider position
        val initialSliderPosition = initialOffset - MIN_OFFSET
        offsetSlider.progress = initialSliderPosition
        
        // Set listener for slider changes
        offsetSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
        offsetValueDisplay.setText(initialOffset.toString())
        isProgrammaticUpdate = false
        
        // Apply on enter key
        offsetValueDisplay.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputOffset()
                true
            } else {
                false
            }
        }
        
        // Input validation and auto-apply
        offsetValueDisplay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                // Skip processing if this is a programmatic update
                if (isProgrammaticUpdate) return
                
                try {
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value < MIN_OFFSET || value > MAX_OFFSET) {
                            offsetValueInputLayout.error = "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                        } else {
                            offsetValueInputLayout.error = null
                            // Auto-apply if value is valid and complete
                            if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString().startsWith("-"))) {
                                applyInputOffset()
                            }
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        offsetValueInputLayout.error = "Invalid number"
                    } else {
                        offsetValueInputLayout.error = null
                    }
                } catch (e: Exception) {
                    offsetValueInputLayout.error = "Invalid number"
                }
            }
        })
    }
    
    private fun applyInputOffset() {
        try {
            val inputText = offsetValueDisplay.text.toString()
            val offsetValue = inputText.toIntOrNull()
            
            if (offsetValue != null) {
                // Ensure the value is within bounds
                val boundedValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)
                
                if (boundedValue != offsetValue) {
                    // Update input if we had to bound the value
                    isProgrammaticUpdate = true
                    offsetValueDisplay.setText(boundedValue.toString())
                    isProgrammaticUpdate = false
                    Toast.makeText(this, "Value adjusted to valid range", Toast.LENGTH_SHORT).show()
                }
                
                // Update slider to match and apply the offset
                val sliderPosition = boundedValue - MIN_OFFSET
                offsetSlider.progress = sliderPosition
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
        offsetSlider.progress = sliderPosition
    }
    
    private fun updateOffsetInputField(currentOffset: Int) {
        // Set flag to prevent TextWatcher from triggering
        isProgrammaticUpdate = true
        
        // Update the text input to match the current offset
        offsetValueDisplay.setText(currentOffset.toString())
        
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
                        Toast.makeText(this, "Combined state received successfully!", Toast.LENGTH_SHORT).show()
                        
                        Log.d("DROIDRUN_MAIN", "Combined state data received: ${data.substring(0, Math.min(100, data.length))}...")
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
            val uri = Uri.parse("content://com.droidrun.portal/")
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
                        Toast.makeText(this, "Phone state received successfully!", Toast.LENGTH_SHORT).show()
                        
                        Log.d("DROIDRUN_MAIN", "Phone state received: ${data.substring(0, Math.min(100, data.length))}...")
                    } else {
                        val error = jsonResponse.getString("error")
                        // responseText.text = error
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error fetching phone state: ${e.message}")
            Toast.makeText(this, "Error fetching phone state: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Check if the accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = packageName + "/" + DroidrunAccessibilityService::class.java.canonicalName
        
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
    private fun isNotificationServiceEnabled(): Boolean {
        val componentName = ComponentName(this, DroidrunNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }
    
    // Update the accessibility status indicator based on service status
    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()
        
        if (isEnabled) {
            // Show enabled card, hide banner
            accessibilityStatusEnabled.visibility = View.VISIBLE
            accessibilityBanner.visibility = View.GONE
        } else {
            // Show banner, hide enabled card
            accessibilityStatusEnabled.visibility = View.GONE
            accessibilityBanner.visibility = View.VISIBLE
        }
    }

    private fun updateNotificationStatusIndicator() {
        try {
            if (!::notificationBanner.isInitialized) return

            val isEnabled = isNotificationServiceEnabled()
            if (isEnabled) {
                // If enabled, hide everything (clean look)
                notificationStatusEnabled.visibility = View.GONE
                notificationBanner.visibility = View.GONE
            } else {
                // If disabled, show the warning banner
                notificationStatusEnabled.visibility = View.GONE
                notificationBanner.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Error updating notification status UI: ${e.message}")
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

    private fun setupSocketServerControls() {
        // Initialize with ConfigManager values
        val configManager = ConfigManager.getInstance(this)
        
        // Set default port value
        isProgrammaticUpdate = true
        socketPortInput.setText(configManager.socketServerPort.toString())
        isProgrammaticUpdate = false
        
        // Port input listener
        socketPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticUpdate) return
                
                try {
                    val portText = s.toString()
                    if (portText.isNotEmpty()) {
                        val port = portText.toIntOrNull()
                        if (port != null && port in 1..65535) {
                            socketPortInputLayout.error = null
                            updateSocketServerPort(port)
                        } else {
                            socketPortInputLayout.error = "Port must be between 1-65535"
                        }
                    } else {
                        socketPortInputLayout.error = null
                    }
                } catch (e: Exception) {
                    socketPortInputLayout.error = "Invalid port number"
                }
            }
        })
        
        // Update initial UI state
        updateSocketServerStatus()
        updateAdbForwardCommand()
    }
    

    
    private fun updateSocketServerPort(port: Int) {
        try {
            val configManager = ConfigManager.getInstance(this)
            configManager.setSocketServerPortWithNotification(port)
            
            updateAdbForwardCommand()
            
            // Give the server a moment to restart, then update the status
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
                socketServerStatus.text = status
                socketServerStatus.setTextColor(Color.parseColor("#00FFA6"))
            } else {
                socketServerStatus.text = "Service not available"
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating socket server status: ${e.message}")
            socketServerStatus.text = "Error"
        }
    }
    
    private fun updateAdbForwardCommand() {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val command = accessibilityService.getAdbForwardCommand()
                adbForwardCommand.text = command
            } else {
                val configManager = ConfigManager.getInstance(this)
                val port = configManager.socketServerPort
                adbForwardCommand.text = "adb forward tcp:$port tcp:$port"
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating ADB forward command: ${e.message}")
            adbForwardCommand.text = "Error"
        }
    }

    private fun setupEndpointsCollapsible() {
        endpointsHeader.setOnClickListener {
            isEndpointsExpanded = !isEndpointsExpanded
            
            if (isEndpointsExpanded) {
                endpointsContent.visibility = View.VISIBLE
                endpointsArrow.rotation = 90f
            } else {
                endpointsContent.visibility = View.GONE
                endpointsArrow.rotation = 0f
            }
        }
    }

    private fun setAppVersion() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val version = packageInfo.versionName
            versionText.text = "Version: $version"
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error getting app version: ${e.message}")
            versionText.text = "Version: N/A"
        }
    }
    
    private fun showLogsDialog() {
        try {
            val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_item, null)
            
            // Create a scrollable TextView for the logs
            val scrollView = androidx.core.widget.NestedScrollView(this)
            val textView = TextView(this).apply {
                text = if (responseText.isNotEmpty()) responseText else "No logs available. Fetch data first."
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
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
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
}