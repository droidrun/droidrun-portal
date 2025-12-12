package com.droidrun.portal.ui

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
import com.droidrun.portal.ui.settings.SettingsBottomSheet
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt

class MainActivity : AppCompatActivity() {
	
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

        setupNetworkInfo()

        binding.enableNotificationButton.setOnClickListener {
            openNotificationSettings()
        }

        binding.settingsButton.setOnClickListener {
            SettingsBottomSheet().show(supportFragmentManager, SettingsBottomSheet.TAG)
        }
        
        // Set app version
        setAppVersion()
        
        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()
        
        // Configure socket server controls
        setupSocketServerControls()
        
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
    }
    
    override fun onResume() {
        super.onResume()
        // Update the status indicators when app resumes
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
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
        updateNotificationStatusIndicator()
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
                                binding.offsetValueInputLayout.error = "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                            } else {
                                binding.offsetValueInputLayout.error = null
                                // Auto-apply if value is valid and complete
                                if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString().startsWith("-"))) {
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
            // TODO add ext functions, makeVisible, makeInvisible, makeVisibleIf, makeVisibleIfElse etc.
            binding.accessibilityStatusEnabled.visibility = View.VISIBLE
            binding.accessibilityBanner.visibility = View.GONE
        } else {
            // Show banner, hide enabled card
            binding.accessibilityStatusEnabled.visibility = View.GONE
            binding.accessibilityBanner.visibility = View.VISIBLE
        }
    }

    private fun updateNotificationStatusIndicator() {
        try {
            val isEnabled = isNotificationServiceEnabled()
            if (isEnabled) {
                // If enabled, hide everything (clean look)
                binding.notificationStatusEnabled.visibility = View.GONE
                binding.notificationBanner.visibility = View.GONE
            } else {
                // If disabled, show the warning banner
                binding.notificationStatusEnabled.visibility = View.GONE
                binding.notificationBanner.visibility = View.VISIBLE
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
        binding.socketPortInput.setText(configManager.socketServerPort.toString())
        isProgrammaticUpdate = false
        
        // Port input listener
        binding.socketPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticUpdate) return
                
                try {
                    val portText = s.toString()
                    if (portText.isNotEmpty()) {
                        val port = portText.toIntOrNull()
                        if (port != null && port in 1..65535) {
                            binding.socketPortInputLayout.error = null
                            updateSocketServerPort(port)
                        } else {
                            binding.socketPortInputLayout.error = "Port must be between 1-65535"
                        }
                    } else {
                        binding.socketPortInputLayout.error = null
                    }
                } catch (e: Exception) {
                    binding.socketPortInputLayout.error = "Invalid port number"
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
}
