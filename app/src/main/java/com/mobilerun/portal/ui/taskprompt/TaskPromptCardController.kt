package com.mobilerun.portal.ui.taskprompt

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mobilerun.portal.R
import com.mobilerun.portal.databinding.ViewTaskPromptCardBinding
import com.mobilerun.portal.taskprompt.PortalModelOption
import com.mobilerun.portal.taskprompt.PortalTaskDraft
import com.mobilerun.portal.taskprompt.PortalTaskSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class TaskPromptCardController(
    private val context: Context,
    layoutInflater: LayoutInflater,
    private val onSubmit: (PortalTaskDraft) -> Unit,
    private val onReturnToPortalChanged: (Boolean) -> Unit,
    private val onCancelTask: () -> Unit,
    private val onOpenTaskDetails: (String) -> Unit,
    private val onOpenTaskHistory: () -> Unit,
) {
    data class TaskStateViewModel(
        val taskId: String,
        val statusLabel: String,
        val statusKind: StatusKind,
        val promptPreview: String? = null,
        val summary: String? = null,
        val isClickable: Boolean = false,
        val isBlocking: Boolean = false,
        val canCancel: Boolean = false,
        val cancelInFlight: Boolean = false,
    )

    enum class StatusKind {
        INFO,
        SUCCESS,
        ERROR,
    }

    private val binding = ViewTaskPromptCardBinding.inflate(layoutInflater)
    private val rootView: View
        get() = binding.root
    private val historyButton: MaterialButton
        get() = binding.taskPromptHistoryButton
    private val promptInputLayout: TextInputLayout
        get() = binding.taskPromptInputLayout
    private val promptInput: TextInputEditText
        get() = binding.taskPromptInput
    private val returnToPortalSwitch: SwitchMaterial
        get() = binding.taskPromptReturnToPortalSwitch
    private val statusText: TextView
        get() = binding.taskPromptStatusText
    private val taskStateContainer: View
        get() = binding.taskPromptTaskStateContainer
    private val latestTaskLabel: TextView
        get() = binding.taskPromptLatestLabel
    private val taskStateChip: TextView
        get() = binding.taskPromptTaskStateChip
    private val taskStateDetail: TextView
        get() = binding.taskPromptTaskStateDetail
    private val taskStateSummary: TextView
        get() = binding.taskPromptTaskStateSummary
    private val cancelTaskButton: MaterialButton
        get() = binding.taskPromptCancelButton
    private val submitButton: MaterialButton
        get() = binding.taskPromptSubmitButton
    private val submitProgress: CircularProgressIndicator
        get() = binding.taskPromptSubmitProgress

    private var currentSettings = PortalTaskSettings()
    private var isModelsLoading = false
    private var isSubmitting = false
    private var canSubmit = false
    private var isFormEnabled = true
    private var isHistoryEnabled = false
    private var taskState: TaskStateViewModel? = null
    private var suppressReturnToPortalChange = false
    private val settingsController = TaskPromptSettingsPanelController(
        context,
        binding.taskPromptSettingsPanel,
    )

    init {
        historyButton.setOnClickListener { onOpenTaskHistory() }

        cancelTaskButton.setOnClickListener {
            onCancelTask()
        }
        returnToPortalSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressReturnToPortalChange) {
                onReturnToPortalChanged(isChecked)
            }
        }

        submitButton.setOnClickListener {
            val draft = buildDraft() ?: return@setOnClickListener
            onSubmit(draft)
        }

        updateSubmitButtonState()
        applyTaskState(null)
    }

    fun attachTo(container: ViewGroup) {
        if (rootView.parent === container) return
        (rootView.parent as? ViewGroup)?.removeView(rootView)
        container.removeAllViews()
        container.addView(rootView)
    }

    fun setVisible(visible: Boolean) {
        rootView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun applySettings(settings: PortalTaskSettings, preservePrompt: Boolean = true) {
        currentSettings = settings
        settingsController.applySettings(settings)
        if (!preservePrompt) {
            promptInput.setText("")
        }
    }

    fun setReturnToPortalChecked(checked: Boolean) {
        if (returnToPortalSwitch.isChecked == checked) return
        suppressReturnToPortalChange = true
        returnToPortalSwitch.isChecked = checked
        suppressReturnToPortalChange = false
    }

    fun setModelOptions(options: List<PortalModelOption>) {
        settingsController.setModelOptions(options)
        updateSubmitButtonState()
    }

    fun setTaskState(state: TaskStateViewModel?) {
        taskState = state
        applyTaskState(state)
        updateSubmitButtonState()
    }

    fun setModelsLoading(loading: Boolean) {
        isModelsLoading = loading
        settingsController.setModelsLoading(loading)
        updateSubmitButtonState()
    }

    fun setSubmissionEnabled(enabled: Boolean) {
        canSubmit = enabled
        updateSubmitButtonState()
    }

    fun setSubmitting(submitting: Boolean) {
        isSubmitting = submitting
        updateSubmitButtonState()
    }

    fun setHistoryEnabled(enabled: Boolean) {
        isHistoryEnabled = enabled
        historyButton.isEnabled = enabled
        historyButton.alpha = if (enabled) 1f else 0.5f
    }

    fun setFormEnabled(enabled: Boolean) {
        isFormEnabled = enabled
        promptInput.isEnabled = enabled
        returnToPortalSwitch.isEnabled = enabled
        settingsController.setEnabled(enabled)
        updateSubmitButtonState()
    }

    fun clearPrompt() {
        promptInput.setText("")
        promptInputLayout.error = null
    }

    fun clearTaskId() {
        // No-op. Task IDs are surfaced through task details metadata only.
    }

    fun showTaskCreated(taskId: String) {
        if (taskId.isBlank()) return
        showStatus(context.getString(R.string.task_prompt_started), StatusKind.SUCCESS)
    }

    fun showStatus(message: String?, kind: StatusKind) {
        if (message.isNullOrBlank()) {
            statusText.visibility = View.GONE
            statusText.text = ""
            return
        }

        statusText.visibility = View.VISIBLE
        statusText.text = message
        val colorRes = when (kind) {
            StatusKind.INFO -> R.color.task_prompt_text_secondary
            StatusKind.SUCCESS -> R.color.task_prompt_accent_light
            StatusKind.ERROR -> R.color.task_prompt_error
        }
        statusText.setTextColor(ContextCompat.getColor(context, colorRes))
    }

    fun clearStatus() {
        showStatus(null, StatusKind.INFO)
    }

    private fun updateSubmitButtonState() {
        val showRunningState = taskState?.isBlocking == true && taskState?.cancelInFlight != true
        submitButton.text = when {
            isSubmitting -> context.getString(R.string.task_prompt_starting_button)
            showRunningState -> context.getString(R.string.task_prompt_running_button)
            else -> context.getString(R.string.task_prompt_submit)
        }
        submitProgress.visibility = if (showRunningState) View.VISIBLE else View.GONE

        cancelTaskButton.text = if (taskState?.cancelInFlight == true) {
            context.getString(R.string.task_prompt_cancelling_button)
        } else {
            context.getString(R.string.task_prompt_cancel_button)
        }

        val hasModels = settingsController.hasModels()
        submitButton.isEnabled =
            isFormEnabled && canSubmit && !isModelsLoading && !isSubmitting && !showRunningState && hasModels
        cancelTaskButton.isEnabled =
            taskState?.canCancel == true && taskState?.cancelInFlight != true
        historyButton.isEnabled = isHistoryEnabled
        historyButton.alpha = if (isHistoryEnabled) 1f else 0.5f
    }

    private fun buildDraft(): PortalTaskDraft? {
        promptInputLayout.error = null

        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) {
            promptInputLayout.error = context.getString(R.string.task_prompt_required)
            return null
        }

        currentSettings = settingsController.buildSettingsOrShowErrors() ?: return null

        return PortalTaskDraft(
            prompt = prompt,
            settings = currentSettings,
            returnToPortalOnTerminal = returnToPortalSwitch.isChecked,
        )
    }

    private fun applyTaskState(state: TaskStateViewModel?) {
        taskStateContainer.visibility = if (state == null) View.GONE else View.VISIBLE
        if (state == null) {
            latestTaskLabel.visibility = View.GONE
            taskStateSummary.visibility = View.GONE
            taskStateDetail.visibility = View.GONE
            cancelTaskButton.visibility = View.GONE
            taskStateContainer.isClickable = false
            taskStateContainer.isFocusable = false
            taskStateContainer.setOnClickListener(null)
            return
        }

        latestTaskLabel.visibility = View.VISIBLE
        taskStateChip.text = state.statusLabel
        taskStateChip.background = createChipBackground(state.statusKind)
        taskStateChip.setTextColor(ContextCompat.getColor(context, R.color.task_prompt_text_primary))

        taskStateDetail.text = state.promptPreview.orEmpty()
        taskStateDetail.visibility = if (state.promptPreview.isNullOrBlank()) View.GONE else View.VISIBLE

        taskStateSummary.text = state.summary.orEmpty()
        taskStateSummary.visibility = if (state.summary.isNullOrBlank()) View.GONE else View.VISIBLE

        cancelTaskButton.visibility =
            if (state.canCancel || state.cancelInFlight) View.VISIBLE else View.GONE

        taskStateContainer.isClickable = state.isClickable
        taskStateContainer.isFocusable = state.isClickable
        taskStateContainer.setOnClickListener(
            if (state.isClickable) {
                View.OnClickListener { onOpenTaskDetails(state.taskId) }
            } else {
                null
            },
        )
    }

    private fun createChipBackground(kind: StatusKind): GradientDrawable {
        val backgroundColor = when (kind) {
            StatusKind.INFO -> ContextCompat.getColor(context, R.color.task_prompt_chip_info_bg)
            StatusKind.SUCCESS -> ContextCompat.getColor(context, R.color.task_prompt_chip_success_bg)
            StatusKind.ERROR -> ContextCompat.getColor(context, R.color.task_prompt_chip_error_bg)
        }

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(backgroundColor)
        }
    }

}
