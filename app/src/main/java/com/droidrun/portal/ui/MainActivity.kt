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
import android.net.Uri
import android.graphics.Color
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import android.content.ClipboardManager
import com.droidrun.portal.databinding.ActivityMainBinding
import com.droidrun.portal.taskprompt.PortalActiveTaskRecord
import com.droidrun.portal.taskprompt.PortalAuthCallbackValidator
import com.droidrun.portal.taskprompt.PortalBalanceCacheState
import com.droidrun.portal.taskprompt.PortalCloudClient
import com.droidrun.portal.taskprompt.PortalModelOption
import com.droidrun.portal.taskprompt.PortalBalanceRepository
import com.droidrun.portal.taskprompt.PortalTaskCancelResult
import com.droidrun.portal.taskprompt.PortalTaskDetails
import com.droidrun.portal.taskprompt.PortalTaskDetailsResult
import com.droidrun.portal.taskprompt.PortalTaskLaunchCoordinator
import com.droidrun.portal.taskprompt.PortalTaskLaunchMetadata
import com.droidrun.portal.taskprompt.PortalTaskStateMonitor
import com.droidrun.portal.taskprompt.PortalTaskStatusAppearance
import com.droidrun.portal.taskprompt.PortalTaskDraft
import com.droidrun.portal.taskprompt.PortalTaskStatusResult
import com.droidrun.portal.taskprompt.PortalTaskTracking
import com.droidrun.portal.taskprompt.PortalTaskUiSupport
import com.droidrun.portal.taskprompt.TaskPromptNotificationManager
import com.droidrun.portal.ui.taskprompt.TaskPromptCardController
import com.droidrun.portal.ui.taskprompt.TaskDetailsActivity
import com.droidrun.portal.ui.taskprompt.TaskHistoryActivity
import com.droidrun.portal.ui.settings.SettingsActivity
import androidx.core.graphics.toColorInt
import androidx.core.content.ContextCompat

import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.droidrun.portal.R
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.update.UpdateCheckResult
import com.droidrun.portal.update.UpdateChecker
import com.droidrun.portal.update.UpdateInfo
import com.droidrun.portal.update.UpdateInstallReceiver
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.net.toUri

class MainActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var binding: ActivityMainBinding

    private var responseText: String = ""

    // Endpoints collapsible section
    private var isEndpointsExpanded = false

    private var isProgrammaticUpdate = false
    private var pendingUpdateInfo: UpdateInfo? = null
    private var updateCheckDoneThisSession = false
    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isInstallReceiverRegistered = false
    private lateinit var taskPromptCardController: TaskPromptCardController
    private val portalCloudClient = PortalCloudClient()
    private lateinit var taskLaunchCoordinator: PortalTaskLaunchCoordinator
    private var taskPromptModels: List<PortalModelOption> = emptyList()
    private var taskPromptModelsFingerprint: String? = null
    private var isTaskPromptModelsLoading = false
    private var isTaskPromptSubmitting = false
    private var isTaskPromptVisible = false
    private var isTaskPromptStatusRequestInFlight = false
    private var isTaskPromptDetailsRequestInFlight = false
    private var isTaskPromptCancelInFlight = false
    private var currentConnectionState = ConnectionStateManager.getState()
    private var taskPromptStatusMessage: String? = null
    private var taskPromptStatusKind = TaskPromptCardController.StatusKind.INFO
    private val taskPromptPollHandler = Handler(Looper.getMainLooper())
    private var taskPromptPollRunnable: Runnable? = null
    private var isTaskPromptStateReceiverRegistered = false
    private var activeTaskDetails: PortalTaskDetails? = null

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ApiHandler.ACTION_INSTALL_RESULT) return
            val success = intent.getBooleanExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, false)
            val message =
                intent.getStringExtra(ApiHandler.EXTRA_INSTALL_MESSAGE)
                    ?: "App installed successfully"
            runOnUiThread {
                resetUpdateBannerButton()
                showInstallSnackbar(message, success)
            }
        }
    }

    private var isSignatureConflictReceiverRegistered = false

    private val signatureConflictReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT) return
            runOnUiThread { showSignatureConflictDialog() }
        }
    }

    private val taskPromptStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TaskPromptNotificationManager.ACTION_TASK_STATE_CHANGED) return
            runOnUiThread {
                handleTaskPromptStateChanged()
            }
        }
    }

    // Constants for the position offset slider
    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_OFFSET = 0
        private const val MIN_OFFSET = -256
        private const val MAX_OFFSET = 256
        private const val SLIDER_RANGE = MAX_OFFSET - MIN_OFFSET
        private const val TASK_PROMPT_POLL_INTERVAL_MS = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register ConfigChangeListener
        ConfigManager.getInstance(this).addListener(this)

        // Handle Deep Link
        handleDeepLink(intent)

        taskPromptCardController = TaskPromptCardController(
            context = this,
            layoutInflater = layoutInflater,
            onSubmit = { draft -> submitTaskPrompt(draft) },
            onReturnToPortalChanged = { enabled ->
                ConfigManager.getInstance(this).taskPromptReturnToPortal = enabled
            },
            onCancelTask = { cancelActiveTask() },
            onOpenTaskDetails = { taskId -> openTaskDetails(taskId) },
            onOpenTaskHistory = { openTaskHistory() },
        )
        taskLaunchCoordinator = PortalTaskLaunchCoordinator(this, portalCloudClient)
        PortalTaskStateMonitor.initialize(this)
        taskPromptCardController.applySettings(
            ConfigManager.getInstance(this).taskPromptSettings,
            preservePrompt = false,
        )
        taskPromptCardController.setReturnToPortalChecked(
            ConfigManager.getInstance(this).taskPromptReturnToPortal,
        )

        setupNetworkInfo()

        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Set app version
        setAppVersion()

        // Wire up the update banner button
        binding.btnUpdate.setOnClickListener {
            pendingUpdateInfo?.let { info -> triggerUpdate(info) }
        }

        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()

        // Update initial UI state
        updateSocketServerStatus()
        updateAdbForwardCommand()

        binding.btnSignInBrowser.setOnClickListener {
            openBrowserSignIn(forceFreshLogin = false)
        }

        binding.btnUseApiKey.setOnClickListener {
            showApiKeyDialog()
        }

        binding.btnCustomConnection.setOnClickListener {
            showCustomConnectionDialog()
        }

        binding.btnRefreshCreditsStandard.setOnClickListener {
            refreshCreditsBalance(force = true)
        }

        binding.btnRefreshCreditsProduction.setOnClickListener {
            refreshCreditsBalance(force = true)
        }

        binding.btnDisconnect.setOnClickListener {
            disconnectService()
        }

        binding.btnSignOut.setOnClickListener {
            showSignOutConfirmation()
        }

        binding.btnCancelConnection.setOnClickListener {
            disconnectService()
        }

        binding.btnErrorPrimaryAction.setOnClickListener {
            if (shouldOfferBrowserReauth(ConnectionStateManager.getState())) {
                openBrowserSignIn(forceFreshLogin = true)
            } else {
                retryConnection()
            }
        }

        binding.btnErrorUseApiKey.setOnClickListener {
            showApiKeyDialog()
        }

        binding.btnErrorCustomConnection.setOnClickListener {
            showCustomConnectionDialog()
        }

        binding.btnDismissError.setOnClickListener {
            ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
        }

        // Configure endpoints collapsible section
        setupEndpointsCollapsible()
        attachTaskPromptCardToActiveContainer()

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

        binding.enableFileAccessButton.setOnClickListener {
            openFileAccessSettings()
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
        refreshTaskPromptUi()
        refreshCreditsBalance()
    }

    override fun onDestroy() {
        stopTaskPromptPolling()
        unregisterTaskPromptStateReceiver()
        updateExecutor.shutdownNow()
        super.onDestroy()
        ConfigManager.getInstance(this).removeListener(this)
    }

    override fun onStart() {
        super.onStart()
        registerInstallResultReceiver()
        registerSignatureConflictReceiver()
        registerTaskPromptStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        isTaskPromptVisible = true
        // Update the status indicators when app resumes
        updateStatusIndicators()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
        updateProductionModeUI()
        refreshTaskPromptUi()
        refreshCreditsBalance()
        syncTaskPromptPolling(immediate = true)
        checkForUpdates()
    }

    override fun onStop() {
        super.onStop()
        isTaskPromptVisible = false
        stopTaskPromptPolling()
        unregisterTaskPromptStateReceiver()
        unregisterInstallResultReceiver()
        unregisterSignatureConflictReceiver()
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
        attachTaskPromptCardToActiveContainer()
        refreshTaskPromptUi()
        renderCreditsUi()
    }

    private fun refreshCreditsBalance(force: Boolean = false) {
        val configManager = ConfigManager.getInstance(this)
        val authToken = configManager.reverseConnectionToken.trim()
        val cloudBaseUrl = PortalCloudClient.deriveCloudBaseUrl(configManager.reverseConnectionUrlOrDefault)
        val fingerprint = currentCreditsFingerprint(authToken, cloudBaseUrl)
        PortalBalanceRepository.observeFingerprint(fingerprint)

        renderCreditsUi()

        if (!PortalTaskUiSupport.shouldShowTaskSurface(currentConnectionState, authToken) ||
            cloudBaseUrl == null ||
            fingerprint == null
        ) {
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

                renderCreditsUi()
            }
        }
    }

    private fun renderCreditsUi() {
        val configManager = ConfigManager.getInstance(this)
        val authToken = configManager.reverseConnectionToken.trim()
        val cloudBaseUrl = PortalCloudClient.deriveCloudBaseUrl(configManager.reverseConnectionUrlOrDefault)
        val creditsState = PortalBalanceRepository.snapshot(currentCreditsFingerprint(authToken, cloudBaseUrl))
        val shouldShow =
            PortalTaskUiSupport.shouldShowTaskSurface(currentConnectionState, authToken) &&
                cloudBaseUrl != null
        val isProductionMode = configManager.productionMode

        renderCreditsCard(
            show = shouldShow && !isProductionMode,
            state = creditsState,
            metricsCard = binding.cardCreditsMetricsStandard,
            balanceView = binding.textCreditsBalanceStandard,
            usageView = binding.textCreditsUsageStandard,
            messageView = binding.textCreditsMessageStandard,
            refreshButton = binding.btnRefreshCreditsStandard,
            card = binding.cardCreditsStandard,
        )
        renderCreditsCard(
            show = shouldShow && isProductionMode,
            state = creditsState,
            metricsCard = binding.cardCreditsMetricsProduction,
            balanceView = binding.textCreditsBalanceProduction,
            usageView = binding.textCreditsUsageProduction,
            messageView = binding.textCreditsMessageProduction,
            refreshButton = binding.btnRefreshCreditsProduction,
            card = binding.cardCreditsProduction,
        )
    }

    private fun renderCreditsCard(
        show: Boolean,
        state: PortalBalanceCacheState,
        metricsCard: View,
        balanceView: TextView,
        usageView: TextView,
        messageView: TextView,
        refreshButton: View,
        card: View,
    ) {
        card.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            return
        }

        val info = state.info
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
            bindCreditsLine(balanceView, balanceLine) or
                bindCreditsLine(usageView, usageLine)
        metricsCard.visibility = if (hasMetrics) View.VISIBLE else View.GONE

        val message = when {
            state.isLoading && hasMetrics -> getString(R.string.credits_refreshing)
            state.isLoading -> getString(R.string.credits_loading)
            !state.message.isNullOrBlank() -> state.message
            else -> null
        }
        messageView.text = message
        messageView.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        refreshButton.isEnabled = !state.isLoading
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

    private fun attachTaskPromptCardToActiveContainer() {
        val container =
            if (ConfigManager.getInstance(this).productionMode) {
                binding.taskPromptContainerProduction
            } else {
                binding.taskPromptContainerStandard
            }
        taskPromptCardController.attachTo(container)
    }

    private fun refreshTaskPromptUi(forceReloadModels: Boolean = false) {
        val configManager = ConfigManager.getInstance(this)
        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl =
            PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        val activeTask = configManager.activePortalTask

        if (activeTask == null ||
            activeTask.taskId != activeTaskDetails?.taskId ||
            !PortalTaskTracking.isTerminalStatus(activeTask.lastStatus)
        ) {
            activeTaskDetails = null
        }

        val isProductionMode = configManager.productionMode
        val shouldShowTaskPrompt =
            PortalTaskUiSupport.shouldShowTaskSurface(
                connectionState = currentConnectionState,
                authToken = authToken,
            )

        binding.taskPromptContainerStandard.visibility =
            if (!isProductionMode && shouldShowTaskPrompt) View.VISIBLE else View.GONE
        binding.taskPromptContainerProduction.visibility =
            if (isProductionMode && shouldShowTaskPrompt) View.VISIBLE else View.GONE
        taskPromptCardController.setVisible(shouldShowTaskPrompt)
        taskPromptCardController.setHistoryEnabled(shouldShowTaskPrompt && restBaseUrl != null)

        if (!shouldShowTaskPrompt) {
            activeTaskDetails = null
            taskPromptCardController.setTaskState(null)
            taskPromptCardController.clearTaskId()
            taskPromptCardController.setModelsLoading(false)
            taskPromptCardController.setSubmitting(false)
            taskPromptCardController.setSubmissionEnabled(false)
            taskPromptCardController.setFormEnabled(false)
            updateTaskPromptStatus(null)
            stopTaskPromptPolling()
            return
        }

        taskPromptCardController.applySettings(configManager.taskPromptSettings)
        taskPromptCardController.setReturnToPortalChecked(configManager.taskPromptReturnToPortal)
        val hasBlockingTask = activeTask?.let { PortalTaskTracking.isBlockingStatus(it.lastStatus) } == true
        if (hasBlockingTask && taskPromptStatusKind == TaskPromptCardController.StatusKind.ERROR) {
            updateTaskPromptStatus(null)
        }
        val canCancelTask =
            activeTask != null &&
                PortalTaskTracking.isBlockingStatus(activeTask.lastStatus) &&
                authToken.isNotBlank() &&
                restBaseUrl != null &&
                !PortalTaskTracking.hasReachedPollingDeadline(activeTask, System.currentTimeMillis())

        if (activeTask == null) {
            taskPromptCardController.setTaskState(null)
            taskPromptCardController.clearTaskId()
        } else {
            taskPromptCardController.setTaskState(
                buildTaskStateViewModel(
                    record = activeTask,
                    details = activeTaskDetails,
                    canCancel = canCancelTask,
                ),
            )
        }

        taskPromptCardController.setSubmitting(isTaskPromptSubmitting)
        taskPromptCardController.setModelsLoading(isTaskPromptModelsLoading)
        taskPromptCardController.setFormEnabled(
            currentConnectionState == ConnectionState.CONNECTED &&
                authToken.isNotBlank() &&
                restBaseUrl != null &&
                !hasBlockingTask,
        )

        if (authToken.isBlank()) {
            taskPromptCardController.setModelsLoading(false)
            taskPromptCardController.setSubmitting(false)
            taskPromptCardController.setSubmissionEnabled(false)
            updateTaskPromptStatus(
                getString(R.string.task_prompt_missing_api_key),
                TaskPromptCardController.StatusKind.ERROR,
            )
            stopTaskPromptPolling()
            return
        }

        if (restBaseUrl == null) {
            taskPromptCardController.setModelsLoading(false)
            taskPromptCardController.setSubmitting(false)
            taskPromptCardController.setSubmissionEnabled(false)
            updateTaskPromptStatus(
                getString(R.string.task_prompt_unsupported_custom_url),
                TaskPromptCardController.StatusKind.ERROR,
            )
            stopTaskPromptPolling()
            return
        }

        if (activeTask != null &&
            PortalTaskTracking.isTerminalStatus(activeTask.lastStatus) &&
            activeTaskDetails?.taskId != activeTask.taskId
        ) {
            loadTerminalTaskDetails(activeTask, restBaseUrl, authToken)
        }

        if (currentConnectionState != ConnectionState.CONNECTED) {
            taskPromptCardController.setModelsLoading(false)
            taskPromptCardController.setSubmitting(isTaskPromptSubmitting)
            taskPromptCardController.setSubmissionEnabled(false)
            syncTaskPromptPolling()
            return
        }

        val modelsFingerprint = "$restBaseUrl|$authToken"
        if (forceReloadModels || taskPromptModels.isEmpty() || taskPromptModelsFingerprint != modelsFingerprint) {
            loadTaskPromptModels(restBaseUrl, authToken, modelsFingerprint)
            syncTaskPromptPolling()
            return
        }

        taskPromptCardController.setModelsLoading(isTaskPromptModelsLoading)
        taskPromptCardController.setSubmitting(isTaskPromptSubmitting)
        taskPromptCardController.setSubmissionEnabled(!isTaskPromptSubmitting && !hasBlockingTask)
        renderTaskPromptStatus()
        syncTaskPromptPolling()
    }

    private fun loadTaskPromptModels(
        restBaseUrl: String,
        authToken: String,
        modelsFingerprint: String,
    ) {
        isTaskPromptModelsLoading = true
        taskPromptModelsFingerprint = modelsFingerprint
        taskPromptCardController.setModelsLoading(true)
        taskPromptCardController.setSubmissionEnabled(false)
        updateTaskPromptStatus(
            getString(R.string.task_prompt_loading_models),
            TaskPromptCardController.StatusKind.INFO,
        )

        portalCloudClient.loadModels(restBaseUrl, authToken) { result ->
            runOnUiThread {
                isTaskPromptModelsLoading = false
                if (result.loadedFromServer && result.models.isNotEmpty()) {
                    ConfigManager.getInstance(this).updateTaskPromptDefaultModel(result.models.first().id)
                }
                taskPromptModels = result.models
                taskPromptCardController.setModelOptions(result.models)
                taskPromptCardController.applySettings(ConfigManager.getInstance(this).taskPromptSettings)
                taskPromptCardController.setModelsLoading(false)
                taskPromptCardController.setSubmitting(isTaskPromptSubmitting)
                taskPromptCardController.setSubmissionEnabled(
                    !isTaskPromptSubmitting &&
                        ConfigManager.getInstance(this).activePortalTask?.let {
                            !PortalTaskTracking.isBlockingStatus(it.lastStatus)
                        } != false,
                )
                updateTaskPromptStatus(result.warningMessage, TaskPromptCardController.StatusKind.INFO)
            }
        }
    }

    private fun submitTaskPrompt(draft: PortalTaskDraft) {
        val configManager = ConfigManager.getInstance(this)
        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl =
            PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        val activeTask = configManager.activePortalTask

        if (authToken.isBlank()) {
            updateTaskPromptStatus(
                getString(R.string.task_prompt_missing_api_key),
                TaskPromptCardController.StatusKind.ERROR,
            )
            return
        }

        if (restBaseUrl == null) {
            updateTaskPromptStatus(
                getString(R.string.task_prompt_unsupported_custom_url),
                TaskPromptCardController.StatusKind.ERROR,
            )
            return
        }

        if (activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            refreshTaskPromptUi()
            return
        }

        isTaskPromptSubmitting = true
        taskPromptCardController.clearTaskId()
        taskPromptCardController.setSubmitting(true)
        taskPromptCardController.setSubmissionEnabled(false)
        updateTaskPromptStatus(null)

        taskLaunchCoordinator.launchPrompt(
            prompt = draft.prompt,
            settings = draft.settings,
            broadcastTaskStateChanged = false,
            metadata = PortalTaskLaunchMetadata(
                returnToPortalOnTerminal = draft.returnToPortalOnTerminal,
            ),
        ) { result ->
            runOnUiThread {
                isTaskPromptSubmitting = false
                taskPromptCardController.setSubmitting(false)

                when (result) {
                    is PortalTaskLaunchCoordinator.Result.Success -> {
                        configManager.saveTaskPromptSettings(draft.settings)
                        configManager.taskPromptReturnToPortal = draft.returnToPortalOnTerminal
                        activeTaskDetails = null
                        taskPromptCardController.clearPrompt()
                        taskPromptCardController.applySettings(draft.settings)
                        taskPromptCardController.setReturnToPortalChecked(draft.returnToPortalOnTerminal)
                        updateTaskPromptStatus(
                            getString(R.string.task_prompt_started),
                            TaskPromptCardController.StatusKind.SUCCESS,
                        )
                        refreshTaskPromptUi()
                        syncTaskPromptPolling(immediate = true)
                    }

                    PortalTaskLaunchCoordinator.Result.Busy -> {
                        refreshTaskPromptUi()
                    }

                    is PortalTaskLaunchCoordinator.Result.Error -> {
                        updateTaskPromptStatus(
                            result.message,
                            TaskPromptCardController.StatusKind.ERROR,
                        )
                        refreshTaskPromptUi()
                    }
                }
            }
        }
    }

    private fun cancelActiveTask() {
        val configManager = ConfigManager.getInstance(this)
        val activeTask = configManager.activePortalTask ?: return
        if (!PortalTaskTracking.isBlockingStatus(activeTask.lastStatus) || isTaskPromptCancelInFlight) {
            return
        }

        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl =
            PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)

        if (authToken.isBlank()) {
            updateTaskPromptStatus(
                getString(R.string.task_prompt_missing_api_key),
                TaskPromptCardController.StatusKind.ERROR,
            )
            refreshTaskPromptUi()
            return
        }

        if (restBaseUrl == null) {
            updateTaskPromptStatus(
                getString(R.string.task_prompt_unsupported_custom_url),
                TaskPromptCardController.StatusKind.ERROR,
            )
            refreshTaskPromptUi()
            return
        }

        isTaskPromptCancelInFlight = true
        val cancellingRecord =
            PortalTaskTracking.withUpdatedStatus(activeTask, PortalTaskTracking.STATUS_CANCELLING)
        configManager.saveActivePortalTask(cancellingRecord)
        updateTaskPromptStatus(null)
        TaskPromptNotificationManager.showActiveTask(this, cancellingRecord)
        PortalTaskStateMonitor.reconcileActiveTask(immediate = true)
        refreshTaskPromptUi()

        portalCloudClient.cancelTask(restBaseUrl, authToken, cancellingRecord.taskId) { result ->
            runOnUiThread {
                isTaskPromptCancelInFlight = false
                when (result) {
                    PortalTaskCancelResult.Success,
                    PortalTaskCancelResult.AlreadyFinished -> {
                        refreshTaskPromptUi()
                        syncTaskPromptPolling(immediate = true)
                    }

                    is PortalTaskCancelResult.Error -> {
                        configManager.saveActivePortalTask(activeTask)
                        updateTaskPromptStatus(
                            result.message,
                            TaskPromptCardController.StatusKind.ERROR,
                        )
                        TaskPromptNotificationManager.showActiveTask(this, activeTask)
                        refreshTaskPromptUi()
                    }
                }
            }
        }
    }

    private fun registerTaskPromptStateReceiver() {
        if (isTaskPromptStateReceiverRegistered) return
        val filter = IntentFilter(TaskPromptNotificationManager.ACTION_TASK_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            taskPromptStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isTaskPromptStateReceiverRegistered = true
    }

    private fun unregisterTaskPromptStateReceiver() {
        if (!isTaskPromptStateReceiverRegistered) return
        try {
            unregisterReceiver(taskPromptStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister task prompt receiver", e)
        } finally {
            isTaskPromptStateReceiverRegistered = false
        }
    }

    private fun handleTaskPromptStateChanged() {
        val activeTask = ConfigManager.getInstance(this).activePortalTask
        if (activeTask == null ||
            activeTask.taskId != activeTaskDetails?.taskId ||
            !PortalTaskTracking.isTerminalStatus(activeTask.lastStatus)
        ) {
            activeTaskDetails = null
        }
        refreshTaskPromptUi()
        if (activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            syncTaskPromptPolling(immediate = true)
        } else {
            stopTaskPromptPolling()
        }
    }

    private fun syncTaskPromptPolling(immediate: Boolean = false) {
        val activeTask = ConfigManager.getInstance(this).activePortalTask
        if (!isTaskPromptVisible || activeTask == null) {
            stopTaskPromptPolling()
            return
        }

        if (!PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            stopTaskPromptPolling()
            return
        }

        PortalTaskStateMonitor.initialize(this)
        PortalTaskStateMonitor.reconcileActiveTask(immediate = immediate)
    }

    private fun scheduleTaskPromptPoll(delayMs: Long) {
        taskPromptPollRunnable?.let(taskPromptPollHandler::removeCallbacks)
        taskPromptPollRunnable = Runnable {
            taskPromptPollRunnable = null
            pollActiveTaskStatus()
        }
        taskPromptPollHandler.postDelayed(taskPromptPollRunnable!!, delayMs)
    }

    private fun stopTaskPromptPolling() {
        taskPromptPollRunnable?.let(taskPromptPollHandler::removeCallbacks)
        taskPromptPollRunnable = null
    }

    private fun pollActiveTaskStatus() {
        val configManager = ConfigManager.getInstance(this)
        val activeTask = configManager.activePortalTask ?: run {
            stopTaskPromptPolling()
            return
        }

        if (!isTaskPromptVisible || !PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)) {
            stopTaskPromptPolling()
            return
        }

        if (PortalTaskTracking.hasReachedPollingDeadline(activeTask, System.currentTimeMillis())) {
            updateTrackingTimeoutState(activeTask, showToast = true)
            refreshTaskPromptUi()
            return
        }

        val authToken = configManager.reverseConnectionToken.trim()
        if (authToken.isBlank()) {
            updateTaskPromptStatus(
                getString(R.string.task_prompt_missing_api_key),
                TaskPromptCardController.StatusKind.ERROR,
            )
            stopTaskPromptPolling()
            refreshTaskPromptUi()
            return
        }

        val restBaseUrl =
            PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)
        if (restBaseUrl == null) {
            updateTaskPromptStatus(
                getString(R.string.task_prompt_unsupported_custom_url),
                TaskPromptCardController.StatusKind.ERROR,
            )
            stopTaskPromptPolling()
            refreshTaskPromptUi()
            return
        }

        isTaskPromptStatusRequestInFlight = true
        portalCloudClient.getTaskStatus(restBaseUrl, authToken, activeTask.taskId) { result ->
            runOnUiThread {
                isTaskPromptStatusRequestInFlight = false
                when (result) {
                    is PortalTaskStatusResult.Success -> {
                        handleTaskStatusUpdate(activeTask, result.value.status, restBaseUrl, authToken)
                    }

                    is PortalTaskStatusResult.Error -> {
                        updateTaskPromptStatus(
                            result.message,
                            TaskPromptCardController.StatusKind.ERROR,
                        )
                        refreshTaskPromptUi()
                        syncTaskPromptPolling()
                    }
                }
            }
        }
    }

    private fun handleTaskStatusUpdate(
        previousRecord: PortalActiveTaskRecord,
        status: String,
        restBaseUrl: String,
        authToken: String,
    ) {
        val configManager = ConfigManager.getInstance(this)
        val currentRecord = configManager.activePortalTask ?: previousRecord
        if (currentRecord.taskId != previousRecord.taskId) {
            refreshTaskPromptUi()
            return
        }

        val updatedRecord = PortalTaskTracking.withUpdatedStatus(currentRecord, status)

        if (updatedRecord != currentRecord) {
            configManager.saveActivePortalTask(updatedRecord)
        }

        if (PortalTaskTracking.isTerminalStatus(updatedRecord.lastStatus)) {
            stopTaskPromptPolling()
            refreshTaskPromptUi()
            loadTerminalTaskDetails(updatedRecord, restBaseUrl, authToken)
            return
        }

        TaskPromptNotificationManager.showActiveTask(this, updatedRecord)
        refreshTaskPromptUi()
        syncTaskPromptPolling()
    }

    private fun loadTerminalTaskDetails(
        record: PortalActiveTaskRecord,
        restBaseUrl: String,
        authToken: String,
    ) {
        if (isTaskPromptDetailsRequestInFlight) {
            return
        }

        if (activeTaskDetails?.taskId == record.taskId) {
            refreshTaskPromptUi()
            return
        }

        isTaskPromptDetailsRequestInFlight = true
        portalCloudClient.getTask(restBaseUrl, authToken, record.taskId) { result ->
            runOnUiThread {
                isTaskPromptDetailsRequestInFlight = false
                val configManager = ConfigManager.getInstance(this)
                val currentRecord = configManager.activePortalTask ?: record
                if (currentRecord.taskId != record.taskId) {
                    refreshTaskPromptUi()
                    return@runOnUiThread
                }

                when (result) {
                    is PortalTaskDetailsResult.Success -> {
                        activeTaskDetails = result.value
                        var finalRecord = currentRecord
                        if (result.value.status.isNotBlank() && result.value.status != currentRecord.lastStatus) {
                            finalRecord = PortalTaskTracking.withUpdatedStatus(
                                currentRecord,
                                result.value.status,
                            )
                            configManager.saveActivePortalTask(finalRecord)
                        }
                        updateTaskPromptStatus(null)
                        TaskPromptNotificationManager.showTerminalTask(
                            context = this,
                            record = finalRecord,
                            details = result.value,
                            fallbackMessage = buildTerminalFallbackMessage(finalRecord, result.value),
                        )
                    }

                    is PortalTaskDetailsResult.Error -> {
                        activeTaskDetails = null
                        updateTaskPromptStatus(
                            result.message,
                            TaskPromptCardController.StatusKind.ERROR,
                        )
                        TaskPromptNotificationManager.showTerminalTask(
                            context = this,
                            record = currentRecord,
                            details = null,
                            fallbackMessage = buildTerminalFallbackMessage(currentRecord, null),
                        )
                    }
                }
                refreshTaskPromptUi()
            }
        }
    }

    private fun updateTrackingTimeoutState(
        record: PortalActiveTaskRecord,
        showToast: Boolean,
    ): PortalActiveTaskRecord {
        stopTaskPromptPolling()
        TaskPromptNotificationManager.cancel(this)

        var finalRecord =
            PortalTaskTracking.withUpdatedStatus(
                record,
                PortalTaskTracking.STATUS_TRACKING_TIMEOUT,
            )

        if (showToast && !finalRecord.terminalToastShown) {
            Toast.makeText(this, getString(R.string.task_prompt_timeout_toast), Toast.LENGTH_SHORT)
                .show()
            finalRecord = finalRecord.copy(terminalToastShown = true)
        }

        ConfigManager.getInstance(this).saveActivePortalTask(finalRecord)
        return finalRecord
    }

    private fun showTerminalToastIfNeeded(record: PortalActiveTaskRecord): PortalActiveTaskRecord {
        if (!PortalTaskTracking.shouldShowTerminalToast(record)) {
            return record
        }

        val messageRes = when (record.lastStatus) {
            PortalTaskTracking.STATUS_COMPLETED -> R.string.task_prompt_completed_toast
            PortalTaskTracking.STATUS_FAILED -> R.string.task_prompt_failed_toast
            PortalTaskTracking.STATUS_CANCELLED -> R.string.task_prompt_cancelled_toast
            PortalTaskTracking.STATUS_TRACKING_TIMEOUT -> R.string.task_prompt_timeout_toast
            else -> return record
        }

        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
        val updatedRecord = record.copy(terminalToastShown = true)
        ConfigManager.getInstance(this).saveActivePortalTask(updatedRecord)
        return updatedRecord
    }

    private fun buildTaskStateViewModel(
        record: PortalActiveTaskRecord,
        details: PortalTaskDetails?,
        canCancel: Boolean,
    ): TaskPromptCardController.TaskStateViewModel {
        val promptPreview = details?.promptPreview?.ifBlank { null }
            ?: record.promptPreview.takeIf { it.isNotBlank() }
        val summary = buildTaskSummary(record.lastStatus, details)
        val statusKind = when (PortalTaskUiSupport.statusAppearance(record.lastStatus)) {
            PortalTaskStatusAppearance.SUCCESS -> TaskPromptCardController.StatusKind.SUCCESS
            PortalTaskStatusAppearance.ERROR -> TaskPromptCardController.StatusKind.ERROR
            PortalTaskStatusAppearance.INFO -> TaskPromptCardController.StatusKind.INFO
        }
        val authToken = ConfigManager.getInstance(this).reverseConnectionToken.trim()
        val restBaseUrl =
            PortalCloudClient.deriveRestBaseUrl(ConfigManager.getInstance(this).reverseConnectionUrlOrDefault)

        return TaskPromptCardController.TaskStateViewModel(
            taskId = record.taskId,
            statusLabel = PortalTaskUiSupport.statusLabel(this, record.lastStatus),
            statusKind = statusKind,
            promptPreview = promptPreview,
            summary = summary,
            isClickable = authToken.isNotBlank() && restBaseUrl != null && record.taskId.isNotBlank(),
            isBlocking = PortalTaskTracking.isBlockingStatus(record.lastStatus),
            canCancel = canCancel && record.lastStatus != PortalTaskTracking.STATUS_CANCELLING,
            cancelInFlight = isTaskPromptCancelInFlight ||
                record.lastStatus == PortalTaskTracking.STATUS_CANCELLING,
        )
    }

    private fun buildTaskSummary(status: String, details: PortalTaskDetails?): String? {
        return PortalTaskUiSupport.buildSummary(
            context = this,
            status = status,
            summary = details?.summary,
            steps = details?.steps,
        )
    }

    private fun buildTerminalFallbackMessage(
        record: PortalActiveTaskRecord,
        details: PortalTaskDetails?,
    ): String {
        return buildTaskSummary(record.lastStatus, details)
            ?: when (record.lastStatus) {
                PortalTaskTracking.STATUS_COMPLETED -> getString(R.string.task_prompt_completed_generic)
                PortalTaskTracking.STATUS_FAILED -> getString(R.string.task_prompt_failed_generic)
                PortalTaskTracking.STATUS_CANCELLED -> getString(R.string.task_prompt_cancelled_generic)
                else -> getString(R.string.task_prompt_timeout_stopped)
            }
    }

    private fun openTaskHistory() {
        startActivity(TaskHistoryActivity.createIntent(this))
    }

    private fun openTaskDetails(taskId: String) {
        if (taskId.isBlank()) return
        startActivity(TaskDetailsActivity.createIntent(this, taskId))
    }

    private fun updateTaskPromptStatus(
        message: String?,
        kind: TaskPromptCardController.StatusKind = TaskPromptCardController.StatusKind.INFO,
    ) {
        taskPromptStatusMessage = message
        taskPromptStatusKind = kind
        renderTaskPromptStatus()
    }

    private fun renderTaskPromptStatus() {
        if (taskPromptStatusMessage.isNullOrBlank()) {
            taskPromptCardController.clearStatus()
        } else {
            taskPromptCardController.showStatus(taskPromptStatusMessage, taskPromptStatusKind)
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
        ContextCompat.registerReceiver(
            this,
            installResultReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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

    private fun registerSignatureConflictReceiver() {
        if (isSignatureConflictReceiverRegistered) return
        val filter = IntentFilter(UpdateInstallReceiver.ACTION_SIGNATURE_CONFLICT)
        ContextCompat.registerReceiver(
            this,
            signatureConflictReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isSignatureConflictReceiverRegistered = true
    }

    private fun unregisterSignatureConflictReceiver() {
        if (!isSignatureConflictReceiverRegistered) return
        try {
            unregisterReceiver(signatureConflictReceiver)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to unregister conflict receiver", e)
        } finally {
            isSignatureConflictReceiverRegistered = false
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

    private fun showSignOutConfirmation() {
        AlertDialog.Builder(this, R.style.Theme_DroidrunPortal_Dialog)
            .setTitle(getString(R.string.sign_out_confirmation_title))
            .setMessage(getString(R.string.sign_out_confirmation_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.sign_out)) { _, _ ->
                signOutLocally()
            }
            .show()
    }

    private fun signOutLocally() {
        val configManager = ConfigManager.getInstance(this)
        disconnectService()
        configManager.reverseConnectionToken = ""
        configManager.reverseConnectionEnabled = false
        configManager.forceLoginOnNextConnect = true
        ConnectionStateManager.setState(ConnectionState.DISCONNECTED)
        refreshCreditsBalance(force = true)
    }

    private fun isOfficialMobilerunCloudConnection(): Boolean {
        val configManager = ConfigManager.getInstance(this)
        return PortalCloudClient.isOfficialMobilerunCloudConnection(
            reverseConnectionUrl = configManager.reverseConnectionUrlOrDefault,
            defaultReverseConnectionUrl = configManager.defaultReverseConnectionUrl,
        )
    }

    private fun shouldOfferBrowserReauth(state: ConnectionState): Boolean {
        return state == ConnectionState.UNAUTHORIZED ||
            (state == ConnectionState.BAD_REQUEST && isOfficialMobilerunCloudConnection())
    }

    private fun openBrowserSignIn(forceFreshLogin: Boolean) {
        val configManager = ConfigManager.getInstance(this)
        if (forceFreshLogin) {
            configManager.reverseConnectionToken = ""
            configManager.reverseConnectionEnabled = false
            configManager.forceLoginOnNextConnect = true
            refreshCreditsBalance(force = true)
        }
        openCloudLogin(configManager)
    }

    private fun restartReverseConnectionService() {
        val serviceIntent = Intent(this, ReverseConnectionService::class.java)
        stopService(serviceIntent)

        Handler(Looper.getMainLooper()).postDelayed({
            startForegroundService(serviceIntent)
        }, 150)
    }

    private fun applyConnectionDialogWidth(dialog: AlertDialog) {
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun retryConnection() {
        val configManager = ConfigManager.getInstance(this)
        configManager.reverseConnectionEnabled = true
        configManager.forceLoginOnNextConnect = false
        restartReverseConnectionService()
    }

    private fun showApiKeyDialog() {
        Log.d(TAG, "showApiKeyDialog: Opening dialog")
        val dialogView = layoutInflater.inflate(R.layout.dialog_api_key_connection, null)
        val inputToken = dialogView.findViewById<TextInputEditText>(R.id.input_custom_token)
        val btnCancel =
            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
        val btnConnect =
            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_connect)

        val configManager = ConfigManager.getInstance(this)

        val existingToken = configManager.reverseConnectionToken
        Log.d(TAG, "showApiKeyDialog: Existing API key length=${existingToken.length}")
        if (existingToken.isNotBlank()) {
            inputToken.setText(existingToken)
        }

        inputToken.addWhitespaceStrippingWatcher()

        val dialog = AlertDialog.Builder(this, R.style.Theme_DroidrunPortal_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(R.color.background_card)

        btnCancel.setOnClickListener {
            Log.d(TAG, "showApiKeyDialog: Cancel clicked")
            dialog.dismiss()
        }

        btnConnect.setOnClickListener {
            val apiKey = sanitizeToken(inputToken.text?.toString())

            Log.d(TAG, "showApiKeyDialog: Connect clicked, API key length=${apiKey.length}")

            if (apiKey.isBlank()) {
                Log.w(TAG, "showApiKeyDialog: API key is blank")
                Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Log.d(TAG, "showApiKeyDialog: Saving config...")
            configManager.reverseConnectionUrl = configManager.defaultReverseConnectionUrl
            configManager.reverseConnectionToken = apiKey
            configManager.reverseConnectionEnabled = true
            configManager.forceLoginOnNextConnect = false

            Log.d(TAG, "showApiKeyDialog: Restarting reverse connection service")
            restartReverseConnectionService()
            refreshCreditsBalance(force = true)

            dialog.dismiss()
            Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        applyConnectionDialogWidth(dialog)
        Log.d(TAG, "showApiKeyDialog: Dialog shown")
    }

    private fun showCustomConnectionDialog() {
        Log.d(TAG, "showCustomConnectionDialog: Opening dialog")
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_connection, null)
        val inputUrl = dialogView.findViewById<TextInputEditText>(R.id.input_custom_url)
        val inputToken = dialogView.findViewById<TextInputEditText>(R.id.input_custom_token)
        val btnCancel =
            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel)
        val btnConnect =
            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_connect)

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
        inputToken.addWhitespaceStrippingWatcher()

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
            val token = sanitizeToken(inputToken.text?.toString())

            Log.d(
                TAG,
                "showCustomConnectionDialog: Connect clicked, URL='$url', token length=${token.length}"
            )

            if (url.isBlank()) {
                Log.w(TAG, "showCustomConnectionDialog: URL is blank")
                Toast.makeText(this, "Please enter a WebSocket URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                Log.w(TAG, "showCustomConnectionDialog: Invalid URL scheme")
                Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            Log.d(TAG, "showCustomConnectionDialog: Saving config...")
            // Save configuration
            configManager.reverseConnectionUrl = url
            configManager.reverseConnectionToken = token
            configManager.reverseConnectionEnabled = true
            configManager.forceLoginOnNextConnect = false
            Log.d(
                TAG,
                "showCustomConnectionDialog: Config saved, reverseConnectionEnabled=${configManager.reverseConnectionEnabled}"
            )

            Log.d(TAG, "showCustomConnectionDialog: Restarting reverse connection service")
            restartReverseConnectionService()
            refreshCreditsBalance(force = true)

            dialog.dismiss()
            Toast.makeText(this, "Connecting to custom server...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        applyConnectionDialogWidth(dialog)
        Log.d(TAG, "showCustomConnectionDialog: Dialog shown")
    }

    private fun setupConnectionStateObserver() {
        ConnectionStateManager.connectionState.observe(this) { state ->
            currentConnectionState = state
            binding.layoutDisconnected.visibility = View.GONE
            binding.layoutConnecting.visibility = View.GONE
            binding.layoutConnected.visibility = View.GONE
            binding.layoutError.visibility = View.GONE
            binding.btnErrorPrimaryAction.text = getString(R.string.retry)
            binding.btnErrorUseApiKey.visibility = View.GONE
            binding.btnErrorCustomConnection.visibility = View.GONE

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
                    binding.textErrorSubtitle.text =
                        getString(R.string.error_unauthorized_actionable)
                    binding.btnErrorPrimaryAction.text = getString(R.string.sign_in_with_browser)
                    binding.btnErrorUseApiKey.visibility = View.VISIBLE
                    binding.btnErrorCustomConnection.visibility = View.VISIBLE
                }

                ConnectionState.LIMIT_EXCEEDED -> {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.textErrorSubtitle.text = getString(R.string.error_limit_exceeded)
                }

                ConnectionState.BAD_REQUEST -> {
                    binding.layoutError.visibility = View.VISIBLE
                    if (isOfficialMobilerunCloudConnection()) {
                        binding.textErrorSubtitle.text =
                            getString(R.string.error_bad_request_actionable)
                        binding.btnErrorPrimaryAction.text = getString(R.string.sign_in_with_browser)
                        binding.btnErrorUseApiKey.visibility = View.VISIBLE
                        binding.btnErrorCustomConnection.visibility = View.VISIBLE
                    } else {
                        binding.textErrorSubtitle.text = getString(R.string.error_bad_request)
                    }
                }

                ConnectionState.ERROR -> {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.textErrorSubtitle.text = getString(R.string.error_unknown)
                }

                else -> {
                    binding.layoutDisconnected.visibility = View.VISIBLE
                }
            }
            refreshTaskPromptUi()
            refreshCreditsBalance()
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
        updateFileAccessBanner()
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        return DroidrunAccessibilityService.getInstance() != null
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
        val oneDp = (resources.displayMetrics.density + 0.5f).toInt()

        // Keep keyboard switching accessible from the main screen whenever the app is
        // functional via accessibility or Droidrun Keyboard is currently selected.
        if (isAccessibilityEnabled || isKeyboardSelected) {
            val bannerBackgroundColor = ContextCompat.getColor(
                this,
                if (isKeyboardSelected) R.color.background_card else R.color.alert_warning_bg
            )
            val bannerStrokeColor = ContextCompat.getColor(this, R.color.stroke_gray)
            val buttonBackgroundColor = ContextCompat.getColor(
                this,
                if (isKeyboardSelected) android.R.color.transparent else R.color.alert_warning_button
            )
            val buttonTextColor = ContextCompat.getColor(
                this,
                if (isKeyboardSelected) R.color.status_success else R.color.text_white
            )

            binding.keyboardWarningBanner.setCardBackgroundColor(bannerBackgroundColor)
            binding.keyboardWarningBanner.strokeWidth = if (isKeyboardSelected) oneDp else 0
            binding.keyboardWarningBanner.setStrokeColor(bannerStrokeColor)
            binding.enableKeyboardButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(buttonBackgroundColor)
            )
            binding.enableKeyboardButton.setTextColor(buttonTextColor)
            if (isKeyboardSelected) {
                binding.keyboardWarningText.maxLines = 1
                binding.keyboardWarningText.ellipsize = android.text.TextUtils.TruncateAt.END
            } else {
                binding.keyboardWarningText.maxLines = Int.MAX_VALUE
                binding.keyboardWarningText.ellipsize = null
            }
            binding.keyboardWarningText.text = getString(
                if (isKeyboardSelected) R.string.keyboard_enabled_message
                else R.string.keyboard_not_enabled_warning
            )
            binding.enableKeyboardButton.text = getString(
                if (isKeyboardSelected) R.string.switch_keyboard
                else R.string.select_keyboard
            )
            binding.keyboardWarningIcon.setImageResource(
                if (isKeyboardSelected) R.drawable.circle_check else R.drawable.info
            )
            binding.keyboardWarningIcon.imageTintList = if (isKeyboardSelected) {
                null
            } else {
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.droidrun_warning)
                )
            }
            binding.keyboardWarningBanner.visibility = View.VISIBLE
        } else {
            binding.keyboardWarningBanner.visibility = View.GONE
        }
    }

    private fun updateFileAccessBanner() {
        val needsPermission = !android.os.Environment.isExternalStorageManager()
        binding.fileAccessBanner.visibility = if (needsPermission) View.VISIBLE else View.GONE
    }

    private fun openFileAccessSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening file access settings: ${e.message}")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open file access settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Check if DroidrunKeyboardIME is selected as the active input method
    private fun isKeyboardSelected(): Boolean {
        return DroidrunKeyboardIME.isSelected(this)
    }

    // Open keyboard/input method settings
    private fun openKeyboardSettings() {
        if (!isKeyboardEnabled()) {
            openInputMethodSettings(R.string.keyboard_enable_settings_help)
            return
        }

        if (hasAlternativeEnabledKeyboard() && showInputMethodPicker()) {
            return
        }

        openInputMethodSettings(R.string.keyboard_switch_settings_help)
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

    private fun hasAlternativeEnabledKeyboard(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.size > 1
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking enabled keyboard count: ${e.message}")
            false
        }
    }

    private fun showInputMethodPicker(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing IME picker: ${e.message}")
            false
        }
    }

    // Open input method settings
    private fun openInputMethodSettings(messageResId: Int) {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                getString(messageResId),
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

    private fun checkForUpdates() {
        // Only check once per MainActivity instance to avoid repeated GitHub API hits on every resume
        if (updateCheckDoneThisSession) return
        updateCheckDoneThisSession = true

        updateExecutor.execute {
            val result = UpdateChecker.checkForUpdate(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result is UpdateCheckResult.Available) {
                    pendingUpdateInfo = result.info
                    binding.updateBannerText.text =
                        getString(R.string.update_available_version, result.info.latestVersion)
                    binding.updateBanner.visibility = View.VISIBLE
                    UpdateChecker.showUpdateNotification(this, result.info)
                } else {
                    binding.updateBanner.visibility = View.GONE
                }
            }
        }
    }

    private fun triggerUpdate(info: UpdateInfo) {
        binding.btnUpdate.isEnabled = false
        binding.btnUpdate.text = getString(R.string.update_downloading)

        updateExecutor.execute {
            UpdateChecker.downloadAndInstall(
                context = this,
                downloadUrl = info.downloadUrl,
                onProgress = { percent ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        binding.btnUpdate.text =
                            getString(R.string.update_downloading_percent, percent)
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        binding.btnUpdate.isEnabled = true
                        binding.btnUpdate.text = getString(R.string.update_now)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }

    private fun resetUpdateBannerButton() {
        binding.btnUpdate.isEnabled = true
        binding.btnUpdate.text = getString(R.string.update_now)
    }

    private fun showSignatureConflictDialog() {
        resetUpdateBannerButton()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_requires_reinstall))
            .setMessage(getString(R.string.update_signature_mismatch_message))
            .setPositiveButton(getString(R.string.update_uninstall)) { _, _ ->
                try {
                    val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(settingsIntent)
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

    private fun showLogsDialog() {
        try {
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

    private fun sanitizeToken(value: String?): String {
        return value?.replace("\\s+".toRegex(), "") ?: ""
    }

    private fun openCloudLogin(configManager: ConfigManager) {
        val deviceId = configManager.deviceID
        val forceLogin = if (configManager.forceLoginOnNextConnect) "&force_login=true" else ""
        configManager.forceLoginOnNextConnect = false
        configManager.markBrowserAuthPending(ttlMs = PortalAuthCallbackValidator.PENDING_WINDOW_MS)
        val url = "https://cloud.mobilerun.ai/auth/device?deviceId=$deviceId$forceLogin"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            configManager.clearBrowserAuthPending()
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        try {
            val data: Uri? = intent?.data
            if (data != null && data.scheme == "droidrun" && data.host == "auth-callback") {
                val configManager = ConfigManager.getInstance(this)
                val validationResult = PortalAuthCallbackValidator.validate(
                    token = data.getQueryParameter("token"),
                    reverseConnectionUrl = data.getQueryParameter("url"),
                    authPending = configManager.isBrowserAuthPending(),
                    defaultReverseConnectionUrl = configManager.defaultReverseConnectionUrl,
                )
                configManager.clearBrowserAuthPending()

                if (validationResult is PortalAuthCallbackValidator.Result.Accepted) {
                    configManager.reverseConnectionToken = validationResult.sanitizedToken
                    configManager.reverseConnectionUrl = validationResult.reverseConnectionUrl
                    configManager.reverseConnectionEnabled = true
                    configManager.forceLoginOnNextConnect = false
                    restartReverseConnectionService()
                    refreshCreditsBalance(force = true)
                } else {
                    val message =
                        (validationResult as PortalAuthCallbackValidator.Result.Rejected).message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    Log.w("DROIDRUN_MAIN", "Rejected auth callback: $message")
                }
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error handling deep link: ${e.message}")
        }
    }
}
