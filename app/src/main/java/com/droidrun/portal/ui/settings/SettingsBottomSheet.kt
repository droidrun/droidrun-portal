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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configManager = ConfigManager.getInstance(requireContext())

        // Server Settings
        binding.switchWsEnabled.isChecked = configManager.websocketEnabled
        binding.switchWsEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.setWebSocketEnabledWithNotification(isChecked)
        }

        binding.inputWsPort.setText(configManager.websocketPort.toString())
        binding.inputWsPort.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val port = v.text.toString().toIntOrNull()
                if (port != null && port in 1024..65535) {
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

        // Toggle Service on Switch Change
        binding.switchReverseEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.reverseConnectionEnabled = isChecked
            
            val intent = android.content.Intent(requireContext(), com.droidrun.portal.service.ReverseConnectionService::class.java)
            if (isChecked) {
                // Ensure URL is saved before starting
                val url = binding.inputReverseUrl.text.toString()
                if (url.isNotBlank()) {
                    configManager.reverseConnectionUrl = url
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
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                configManager.reverseConnectionUrl = v.text.toString()
                binding.inputReverseUrl.clearFocus()
                
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
        binding.switchEventNotification.isChecked = configManager.isEventEnabled(EventType.NOTIFICATION)
        binding.switchEventNotification.setOnCheckedChangeListener { _, isChecked ->
            configManager.setEventEnabled(EventType.NOTIFICATION, isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
