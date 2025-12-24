package com.droidrun.portal.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.databinding.SheetSettingsBinding
import com.droidrun.portal.events.model.EventType
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private lateinit var configManager: ConfigManager
    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configManager = ConfigManager.getInstance(requireContext())

        // Server Settings
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

        // WebSocket Settings
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

        // Reverse Connection Settings
        binding.switchReverseEnabled.isChecked = configManager.reverseConnectionEnabled
        binding.inputReverseUrl.setText(configManager.reverseConnectionUrl)
        binding.inputReverseToken.setText(configManager.reverseConnectionToken)

        // Toggle Service on Switch Change
        binding.switchReverseEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.reverseConnectionEnabled = isChecked

            val intent = android.content.Intent(
                requireContext(),
                com.droidrun.portal.service.ReverseConnectionService::class.java,
            )
            if (isChecked) {
                // Ensure URL is saved before starting
                val url = binding.inputReverseUrl.text.toString()
                if (url.isNotBlank()) {
                    configManager.reverseConnectionUrl = url
                    // Also save token if user typed it but didn't hit done
                    configManager.reverseConnectionToken = binding.inputReverseToken.text.toString()

                    requireContext().startService(intent)
                } else {
                    binding.inputReverseUrl.error = "URL required"
                    binding.switchReverseEnabled.isChecked = false
                }
            } else {
                requireContext().stopService(intent)
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
                configManager.reverseConnectionToken = v.text.toString()
                binding.inputReverseToken.clearFocus()
                restartServiceIfEnabled()
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

        // Event Filters
        setupEventToggle(binding.switchEventNotification, EventType.NOTIFICATION)
    }

    private fun restartServiceIfEnabled() {
        if (configManager.reverseConnectionEnabled) {
            val intent = android.content.Intent(
                requireContext(),
                com.droidrun.portal.service.ReverseConnectionService::class.java,
            )
            requireContext().stopService(intent)
            requireContext().startService(intent)
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

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}
