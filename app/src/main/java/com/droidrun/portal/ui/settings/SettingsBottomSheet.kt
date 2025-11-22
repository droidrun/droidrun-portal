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