package com.droidrun.portal.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.droidrun.portal.R
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.databinding.ActivitySettingsBinding
import com.droidrun.portal.events.model.EventType
import com.droidrun.portal.keepalive.KeepAliveController
import com.droidrun.portal.service.DroidrunNotificationListener
import com.droidrun.portal.service.ReverseConnectionService
import com.droidrun.portal.state.AppVisibilityTracker
import com.droidrun.portal.state.ConnectionState
import com.droidrun.portal.state.ConnectionStateManager
import com.droidrun.portal.taskprompt.PortalBalanceRepository
import com.droidrun.portal.taskprompt.PortalCloudClient
import com.droidrun.portal.triggers.TriggerRepository
import com.droidrun.portal.ui.addWhitespaceStrippingWatcher
import com.droidrun.portal.ui.triggers.TriggerRulesActivity
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.update.UpdateCheckResult
import com.droidrun.portal.update.UpdateChecker
import com.droidrun.portal.update.UpdateInfo
import com.droidrun.portal.update.UpdateInstallReceiver
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var configManager: ConfigManager
    private lateinit var binding: ActivitySettingsBinding
    private val portalCloudClient = PortalCloudClient()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var suppressSocketServerSwitchCallback = false
    private var suppressWebSocketSwitchCallback = false

    private var isSignatureConflictReceiverRegistered = false
    private val signatureConflictReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action != UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT) return
            runOnUiThread { showSignatureConflictDialog() }
        }
    }

    private var isInstallResultReceiverRegistered = false
    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApiHandler.ACTION_INSTALL_RESULT) return
            val success = intent.getBooleanExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, false)
            val message = intent.getStringExtra(ApiHandler.EXTRA_INSTALL_MESSAGE) ?: ""
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnCheckUpdates.isEnabled = true
                binding.btnCheckUpdates.text = getString(R.string.update_check_for_updates)
                if (message.isNotBlank()) {
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        binding.switchPostNotifications.isChecked = isGranted
        if (isGranted) {
            Toast.makeText(
                this,
                "Notification permission granted",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager.getInstance(this)

        setupToolbar()
        setupCreditsSection()
        setupDevMode()
        setupServerSettings()
        setupWebSocketSettings()
        setupReverseConnectionSettings()
        setupPermissions()
        setupAutomation()
        setupEventFilters()
        setupCheckUpdates()
        setupResetButton()
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionSwitches()
        syncServerSettingsFromConfig()
        refreshCreditsBalance()
    }

    private fun setupCreditsSection() {
        binding.btnRefreshCreditsSettings.setOnClickListener {
            refreshCreditsBalance(force = true)
        }
        renderCreditsUi()
    }

    override fun onStart() {
        super.onStart()
        configManager.addListener(this)
        syncServerSettingsFromConfig()
        AppVisibilityTracker.setForeground(true)
        if (!isSignatureConflictReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                signatureConflictReceiver,
                IntentFilter(UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            isSignatureConflictReceiverRegistered = true
        }
        if (!isInstallResultReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                installResultReceiver,
                IntentFilter(ApiHandler.ACTION_INSTALL_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            isInstallResultReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        configManager.removeListener(this)
        persistReverseConnectionInputs()
        AppVisibilityTracker.setForeground(false)
        if (isSignatureConflictReceiverRegistered) {
            try { unregisterReceiver(signatureConflictReceiver) } catch (_: Exception) {}
            isSignatureConflictReceiverRegistered = false
        }
        if (isInstallResultReceiverRegistered) {
            try { unregisterReceiver(installResultReceiver) } catch (_: Exception) {}
            isInstallResultReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun setupDevMode() {
        binding.switchDevMode.isChecked = configManager.devModeEnabled
        updateDevModeVisibility(configManager.devModeEnabled)

        binding.switchDevMode.setOnCheckedChangeListener { _, isChecked ->
            configManager.devModeEnabled = isChecked
            updateDevModeVisibility(isChecked)
        }
    }

    private fun updateDevModeVisibility(enabled: Boolean) {
        binding.devModeSection.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun setupServerSettings() {
        // HTTP Server
        binding.switchSocketServerEnabled.isChecked = configManager.socketServerEnabled
        binding.switchSocketServerEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSocketServerSwitchCallback) return@setOnCheckedChangeListener
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
            if (suppressWebSocketSwitchCallback) return@setOnCheckedChangeListener
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

        binding.switchReverseEnabled.setOnCheckedChangeListener { _, isChecked ->
            configManager.reverseConnectionEnabled = isChecked

            val intent = Intent(
                this,
                ReverseConnectionService::class.java,
            )
            if (isChecked) {
                val url = binding.inputReverseUrl.text.toString().ifBlank {
                    configManager.reverseConnectionUrlOrDefault
                }
                val token = sanitizeToken(binding.inputReverseToken.text?.toString())
                binding.inputReverseToken.error = null
                configManager.reverseConnectionUrl = url
                configManager.reverseConnectionToken = token
                startForegroundService(intent)
            } else {
                intent.action = ReverseConnectionService.ACTION_DISCONNECT
                startService(intent)
            }
            refreshCreditsBalance(force = true)
        }

        binding.inputReverseUrl.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                configManager.reverseConnectionUrl = v.text.toString().trim()
                if (actionId == EditorInfo.IME_ACTION_DONE) binding.inputReverseUrl.clearFocus()
                restartServiceIfEnabled()
                refreshCreditsBalance(force = true)
                true
            } else {
                false
            }
        }

        binding.inputReverseToken.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val token = sanitizeToken(v.text?.toString())
                binding.inputReverseToken.error = null
                configManager.reverseConnectionToken = token
                binding.inputReverseToken.clearFocus()
                restartServiceIfEnabled()
                refreshCreditsBalance(force = true)
                true
            } else {
                false
            }
        }

        binding.switchScreenShareAutoAccept.isChecked = configManager.screenShareAutoAcceptEnabled
        binding.switchScreenShareAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            configManager.screenShareAutoAcceptEnabled = isChecked
        }

        binding.switchInstallAutoAccept.isChecked = configManager.installAutoAcceptEnabled
        binding.switchInstallAutoAccept.setOnCheckedChangeListener { _, isChecked ->
            configManager.installAutoAcceptEnabled = isChecked
        }

        binding.switchKeepScreenAwake.isChecked = configManager.keepScreenAwakeEnabled
        binding.switchKeepScreenAwake.setOnCheckedChangeListener { _, isChecked ->
            KeepAliveController.setEnabled(this, isChecked)
        }
    }

    private fun setupPermissions() {
        updatePermissionSwitches()

        binding.switchNotificationAccess.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Please grant Notification Access to Droidrun Portal",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Error opening settings",
                    Toast.LENGTH_SHORT
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

    private fun setupAutomation() {
        binding.openTriggersButton.setOnClickListener {
            startActivity(TriggerRulesActivity.createIntent(this))
        }
    }

    private fun setupCheckUpdates() {
        // Show current version
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            binding.textAppVersion.text = "Version $version"
        } catch (e: Exception) {
            binding.textAppVersion.text = "Version ?"
        }

        binding.btnCheckUpdates.setOnClickListener {
            binding.btnCheckUpdates.isEnabled = false
            binding.btnCheckUpdates.text = getString(R.string.update_checking)

            executor.execute {
                val result = UpdateChecker.checkForUpdate(this)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.btnCheckUpdates.isEnabled = true
                    binding.btnCheckUpdates.text = getString(R.string.update_check_for_updates)

                    when (result) {
                        is UpdateCheckResult.Available -> showUpdateAvailableDialog(result.info)
                        is UpdateCheckResult.UpToDate -> {
                            val current = UpdateChecker.getCurrentVersion(this)
                            Toast.makeText(
                                this,
                                getString(R.string.update_up_to_date, current),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        is UpdateCheckResult.Failed -> {
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun showSignatureConflictDialog() {
        binding.btnCheckUpdates.isEnabled = true
        binding.btnCheckUpdates.text = getString(R.string.update_check_for_updates)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_requires_reinstall))
            .setMessage(getString(R.string.update_signature_mismatch_message))
            .setPositiveButton(getString(R.string.update_uninstall)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open App Settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.background_dark)
            }
    }

    private fun showUpdateAvailableDialog(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_version, info.latestVersion))
            .setMessage("A new version is available. Tap Update to download and install it now.")
            .setPositiveButton(getString(R.string.update_now)) { _, _ ->
                startUpdate(info)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.background_dark)
            }
    }

    private fun startUpdate(info: UpdateInfo) {
        binding.btnCheckUpdates.isEnabled = false
        binding.btnCheckUpdates.text = getString(R.string.update_downloading)

        executor.execute {
            UpdateChecker.downloadAndInstall(
                context = this,
                downloadUrl = info.downloadUrl,
                onProgress = { percent ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        binding.btnCheckUpdates.text =
                            getString(R.string.update_downloading_percent, percent)
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        binding.btnCheckUpdates.isEnabled = true
                        binding.btnCheckUpdates.text = getString(R.string.update_check_for_updates)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }

    private fun setupResetButton() {
        binding.btnResetSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to Defaults")
                .setMessage("This will reset all settings to their default values and disconnect any active connections. Continue?")
                .setPositiveButton("Reset") { _, _ ->
                    val serviceIntent = Intent(this, ReverseConnectionService::class.java).apply {
                        action = ReverseConnectionService.ACTION_DISCONNECT
                    }
                    startService(serviceIntent)

                    configManager.resetToDefaults()
                    TriggerRepository.getInstance(this).clearAll()
                    ConnectionStateManager.setState(ConnectionState.DISCONNECTED)

                    Toast.makeText(
                        this,
                        "Settings reset to defaults",
                        Toast.LENGTH_SHORT,
                    ).show()

                    val intent = Intent(this, SettingsActivity::class.java)
                    finish()
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
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

    private fun sanitizeToken(value: String?): String {
        return value?.replace("\\s+".toRegex(), "") ?: ""
    }

    private fun persistReverseConnectionInputs() {
        configManager.reverseConnectionUrl = binding.inputReverseUrl.text?.toString()?.trim() ?: ""
        configManager.reverseConnectionToken =
            sanitizeToken(binding.inputReverseToken.text?.toString())
    }

    private fun currentCreditsToken(): String {
        return sanitizeToken(binding.inputReverseToken.text?.toString()).trim()
    }

    private fun currentCreditsReverseConnectionUrl(): String {
        val rawValue = binding.inputReverseUrl.text?.toString()?.trim().orEmpty()
        return rawValue.ifBlank { configManager.reverseConnectionUrlOrDefault }
    }

    private fun refreshCreditsBalance(force: Boolean = false) {
        val authToken = currentCreditsToken()
        val cloudBaseUrl = PortalCloudClient.deriveCloudBaseUrl(currentCreditsReverseConnectionUrl())
        val fingerprint = currentCreditsFingerprint(authToken, cloudBaseUrl)
        PortalBalanceRepository.observeFingerprint(fingerprint)

        renderCreditsUi(authToken, cloudBaseUrl)

        if (authToken.isBlank() || cloudBaseUrl == null || fingerprint == null) {
            return
        }

        PortalBalanceRepository.loadBalance(
            fingerprint = fingerprint,
            cloudBaseUrl = cloudBaseUrl,
            authToken = authToken,
            force = force,
            loader = portalCloudClient::loadBalance,
        ) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                renderCreditsUi(authToken, cloudBaseUrl)
            }
        }
    }

    private fun renderCreditsUi(
        authToken: String = currentCreditsToken(),
        cloudBaseUrl: String? = PortalCloudClient.deriveCloudBaseUrl(currentCreditsReverseConnectionUrl()),
    ) {
        val showSection = authToken.isNotBlank()
        binding.textCreditsSectionHeader.visibility = if (showSection) View.VISIBLE else View.GONE
        binding.cardCreditsSettings.visibility = if (showSection) View.VISIBLE else View.GONE
        if (!showSection) {
            return
        }

        val creditsState = PortalBalanceRepository.snapshot(currentCreditsFingerprint(authToken, cloudBaseUrl))
        val info = if (cloudBaseUrl != null) creditsState.info else null
        val balanceLine = info?.let {
            getString(
                R.string.credits_balance_line,
                formatCreditsCount(info.balance),
            )
        }?.takeIf { it.isNotBlank() }
        val usageLine = info?.let {
            getString(
                R.string.credits_usage_line,
                formatCreditsCount(info.usage),
            )
        }?.takeIf { it.isNotBlank() }

        val hasMetrics =
            bindCreditsLine(binding.textCreditsBalanceSettings, balanceLine) or
                bindCreditsLine(binding.textCreditsUsageSettings, usageLine)
        binding.cardCreditsMetricsSettings.visibility = if (hasMetrics) View.VISIBLE else View.GONE

        val message = when {
            cloudBaseUrl == null -> getString(R.string.credits_unsupported_host)
            creditsState.isLoading && hasMetrics -> getString(R.string.credits_refreshing)
            creditsState.isLoading -> getString(R.string.credits_loading)
            !creditsState.message.isNullOrBlank() -> creditsState.message
            else -> null
        }
        binding.textCreditsMessageSettings.text = message
        binding.textCreditsMessageSettings.visibility =
            if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.btnRefreshCreditsSettings.isEnabled =
            !creditsState.isLoading && authToken.isNotBlank() && cloudBaseUrl != null
    }

    private fun formatCreditsCount(value: Int): String {
        return NumberFormat.getIntegerInstance().format(value)
    }

    private fun currentCreditsFingerprint(authToken: String, cloudBaseUrl: String?): String? {
        if (authToken.isBlank() || cloudBaseUrl == null) {
            return null
        }
        return PortalBalanceRepository.buildFingerprint(cloudBaseUrl, authToken)
    }

    private fun bindCreditsLine(view: TextView, text: String?): Boolean {
        val normalized = text?.takeIf { it.isNotBlank() }
        view.text = normalized.orEmpty()
        view.visibility = if (normalized == null) View.GONE else View.VISIBLE
        return normalized != null
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

    private fun syncServerSettingsFromConfig() {
        updateSocketServerEnabledUi(configManager.socketServerEnabled)
        updateSocketServerPortUi(configManager.socketServerPort)
        updateWebSocketEnabledUi(configManager.websocketEnabled)
        updateWebSocketPortUi(configManager.websocketPort)
        binding.switchKeepScreenAwake.isChecked = configManager.keepScreenAwakeEnabled
    }

    private fun updateSocketServerEnabledUi(enabled: Boolean) {
        suppressSocketServerSwitchCallback = true
        binding.switchSocketServerEnabled.isChecked = enabled
        suppressSocketServerSwitchCallback = false
    }

    private fun updateSocketServerPortUi(port: Int) {
        val current = binding.inputSocketServerPort.text?.toString()
        val expected = port.toString()
        if (current != expected) {
            binding.inputSocketServerPort.setText(expected)
        }
    }

    private fun updateWebSocketEnabledUi(enabled: Boolean) {
        suppressWebSocketSwitchCallback = true
        binding.switchWsEnabled.isChecked = enabled
        suppressWebSocketSwitchCallback = false
    }

    private fun updateWebSocketPortUi(port: Int) {
        val current = binding.inputWsPort.text?.toString()
        val expected = port.toString()
        if (current != expected) {
            binding.inputWsPort.setText(expected)
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

    override fun onOverlayVisibilityChanged(visible: Boolean) {}

    override fun onOverlayOffsetChanged(offset: Int) {}

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            updateSocketServerEnabledUi(enabled)
        }
    }

    override fun onSocketServerPortChanged(port: Int) {
        runOnUiThread {
            updateSocketServerPortUi(port)
        }
    }

    override fun onWebSocketEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            updateWebSocketEnabledUi(enabled)
        }
    }

    override fun onWebSocketPortChanged(port: Int) {
        runOnUiThread {
            updateWebSocketPortUi(port)
        }
    }

    override fun onKeepScreenAwakeEnabledChanged(enabled: Boolean) {
        runOnUiThread {
            binding.switchKeepScreenAwake.isChecked = enabled
        }
    }

    companion object {
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
    }
}
