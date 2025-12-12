package com.droidrun.portal.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.droidrun.portal.R
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.events.model.EventType
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private lateinit var configManager: ConfigManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configManager = ConfigManager.getInstance(requireContext())

        // Server Settings
        val switchWsEnabled = view.findViewById<SwitchMaterial>(R.id.switch_ws_enabled)
        val inputWsPort = view.findViewById<TextInputEditText>(R.id.input_ws_port)

        switchWsEnabled.isChecked = configManager.websocketEnabled
        switchWsEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.setWebSocketEnabledWithNotification(isChecked)
        }

        inputWsPort.setText(configManager.websocketPort.toString())
        inputWsPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val port = v.text.toString().toIntOrNull()
                if (port != null && port in 1024..65535) {
                    configManager.setWebSocketPortWithNotification(port)
                    inputWsPort.clearFocus()
                } else {
                    inputWsPort.error = "Invalid Port"
                }
                true
            } else {
                false
            }
        }

        // Reverse Connection Settings
        val switchReverseEnabled = view.findViewById<SwitchMaterial>(R.id.switch_reverse_enabled)
        val inputReverseUrl = view.findViewById<TextInputEditText>(R.id.input_reverse_url)

        switchReverseEnabled.isChecked = configManager.reverseConnectionEnabled
        inputReverseUrl.setText(configManager.reverseConnectionUrl)

        // Toggle Service on Switch Change
        switchReverseEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.reverseConnectionEnabled = isChecked
            
            val intent = android.content.Intent(requireContext(), com.droidrun.portal.service.ReverseConnectionService::class.java)
            if (isChecked) {
                // Ensure URL is saved before starting
                val url = inputReverseUrl.text.toString()
                if (url.isNotBlank()) {
                    configManager.reverseConnectionUrl = url
                    requireContext().startService(intent)
                } else {
                    inputReverseUrl.error = "URL required"
                    switchReverseEnabled.isChecked = false
                }
            } else {
                requireContext().stopService(intent)
            }
        }

        inputReverseUrl.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                configManager.reverseConnectionUrl = v.text.toString()
                inputReverseUrl.clearFocus()
                
                // If enabled, restart service to pick up new URL
                if (configManager.reverseConnectionEnabled) {
                    val intent = android.content.Intent(requireContext(), com.droidrun.portal.service.ReverseConnectionService::class.java)
                    requireContext().stopService(intent)
                    requireContext().startService(intent)
                }
                true
            } else {
                false
            }
        }

        // Event Filters
        setupEventToggle(view, R.id.switch_event_notification, EventType.NOTIFICATION)
    }

    private fun setupEventToggle(root: View, switchId: Int, type: EventType) {
        val switch = root.findViewById<SwitchMaterial>(switchId)
        switch.isChecked = configManager.isEventEnabled(type)
        
        switch.setOnCheckedChangeListener { _, isChecked ->
            configManager.setEventEnabled(type, isChecked)
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}