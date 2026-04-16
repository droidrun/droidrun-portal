package com.droidrun.portal.ui.triggers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.InputFilter
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.databinding.ActivityTriggerRuleEditorBinding
import com.droidrun.portal.databinding.DialogTriggerDurationPickerBinding
import com.droidrun.portal.taskprompt.PortalCloudClient
import com.droidrun.portal.triggers.TriggerBusyPolicy
import com.droidrun.portal.triggers.TriggerEditorSupport
import com.droidrun.portal.triggers.TriggerNetworkType
import com.droidrun.portal.triggers.TriggerRule
import com.droidrun.portal.triggers.TriggerRuleValidator
import com.droidrun.portal.triggers.TriggerRuntime
import com.droidrun.portal.triggers.TriggerSource
import com.droidrun.portal.triggers.TriggerStringMatchMode
import com.droidrun.portal.triggers.TriggerThresholdComparison
import com.droidrun.portal.triggers.TriggerUiSupport
import com.droidrun.portal.ui.taskprompt.TaskPromptSettingsPanelController
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import androidx.core.net.toUri

class TriggerRuleEditorActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_RULE_ID = "extra_rule_id"

        fun createIntent(context: Context, ruleId: String? = null): Intent {
            return Intent(context, TriggerRuleEditorActivity::class.java).apply {
                ruleId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_RULE_ID, it) }
            }
        }
    }

    private data class LabeledValue<T>(
        val label: String,
        val value: T,
    )

    private enum class RunLimitMode {
        ALWAYS,
        ONCE,
        CUSTOM,
    }

    private lateinit var binding: ActivityTriggerRuleEditorBinding
    private lateinit var settingsController: TaskPromptSettingsPanelController
    private lateinit var weekdayChips: List<Pair<Int, Chip>>

    private val portalCloudClient = PortalCloudClient()
    private val configManager by lazy { ConfigManager.getInstance(this) }
    private val sourceOptions by lazy {
        TriggerSource.entries.map { LabeledValue(TriggerUiSupport.sourceLabel(it), it) }
    }
    private val matchModeOptions by lazy {
        TriggerStringMatchMode.entries.map { LabeledValue(it.name.replace('_', ' '), it) }
    }
    private val thresholdComparisonOptions by lazy {
        TriggerThresholdComparison.entries.map { LabeledValue(it.name.replace('_', ' '), it) }
    }
    private val networkTypeOptions by lazy {
        listOf(LabeledValue("Any", null)) + TriggerNetworkType.entries.map {
            LabeledValue(it.name.replace('_', ' '), it)
        }
    }
    private val repeatModeOptions by lazy {
        listOf(
            LabeledValue("Every day", TriggerSource.TIME_DAILY),
            LabeledValue("Selected weekdays", TriggerSource.TIME_WEEKLY),
        )
    }
    private val runLimitOptions by lazy {
        listOf(
            LabeledValue("Always", RunLimitMode.ALWAYS),
            LabeledValue("Once", RunLimitMode.ONCE),
            LabeledValue("Custom", RunLimitMode.CUSTOM),
        )
    }

    private var originalRule: TriggerRule? = null
    private var selectedSource: TriggerSource = TriggerSource.NOTIFICATION_POSTED
    private var selectedRunLimitMode: RunLimitMode = RunLimitMode.ALWAYS
    private var selectedDelayMinutes: Int? = null
    private var selectedAbsoluteDateUtcMs: Long? = null
    private var selectedAbsoluteHour: Int? = null
    private var selectedAbsoluteMinute: Int? = null
    private var selectedRecurringHour: Int? = null
    private var selectedRecurringMinute: Int? = null
    private var lastExactAlarmAvailable: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TriggerRuntime.initialize(this)

        binding = ActivityTriggerRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsController = TaskPromptSettingsPanelController(this, binding.taskPromptSettingsPanel)
        lastExactAlarmAvailable = hasExactAlarmAccess()
        weekdayChips = listOf(
            Calendar.SUNDAY to binding.chipSunday,
            Calendar.MONDAY to binding.chipMonday,
            Calendar.TUESDAY to binding.chipTuesday,
            Calendar.WEDNESDAY to binding.chipWednesday,
            Calendar.THURSDAY to binding.chipThursday,
            Calendar.FRIDAY to binding.chipFriday,
            Calendar.SATURDAY to binding.chipSaturday,
        )

        setupToolbar()
        setupDropdowns()
        setupControls()
        populateSeedRule()
        loadModelOptions()
    }

    override fun onResume() {
        super.onResume()
        val exactAlarmAvailable = hasExactAlarmAccess()
        if (lastExactAlarmAvailable != null && lastExactAlarmAvailable != exactAlarmAvailable) {
            TriggerRuntime.onRulesChanged()
        }
        lastExactAlarmAvailable = exactAlarmAvailable
        refreshExactAlarmNotice()
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener { finish() }
    }

    private fun setupDropdowns() {
        binding.inputTriggerSource.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, sourceOptions.map { it.label }),
        )
        binding.inputMatchMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, matchModeOptions.map { it.label }),
        )
        binding.inputThresholdComparison.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                thresholdComparisonOptions.map { it.label },
            ),
        )
        binding.inputNetworkType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, networkTypeOptions.map { it.label }),
        )
        binding.inputRepeatMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, repeatModeOptions.map { it.label }),
        )
        binding.inputRunLimitMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, runLimitOptions.map { it.label }),
        )

        binding.inputTriggerSource.setOnItemClickListener { _, _, position, _ ->
            updateSourceSelection(sourceOptions[position].value)
        }
        binding.inputMatchMode.setOnItemClickListener { _, _, _, _ ->
            binding.matchModeLayout.error = null
        }
        binding.inputRepeatMode.setOnItemClickListener { _, _, position, _ ->
            updateSourceSelection(repeatModeOptions[position].value)
        }
        binding.inputRunLimitMode.setOnItemClickListener { _, _, position, _ ->
            updateRunLimitMode(runLimitOptions[position].value)
        }

        binding.inputTriggerSource.setOnClickListener { binding.inputTriggerSource.showDropDown() }
        binding.inputMatchMode.setOnClickListener { binding.inputMatchMode.showDropDown() }
        binding.inputThresholdComparison.setOnClickListener { binding.inputThresholdComparison.showDropDown() }
        binding.inputNetworkType.setOnClickListener { binding.inputNetworkType.showDropDown() }
        binding.inputRepeatMode.setOnClickListener { binding.inputRepeatMode.showDropDown() }
        binding.inputRunLimitMode.setOnClickListener { binding.inputRunLimitMode.showDropDown() }
    }

    private fun setupControls() {
        binding.inputCooldownSeconds.filters = arrayOf(InputFilter.LengthFilter(4))
        binding.inputThresholdValue.filters = arrayOf(InputFilter.LengthFilter(3))
        binding.inputCustomRunLimit.filters = arrayOf(InputFilter.LengthFilter(6))

        binding.switchOverrideTaskSettings.setOnCheckedChangeListener { _, isChecked ->
            binding.overrideTaskSettingsContainer.isVisible = isChecked
            settingsController.setEnabled(isChecked)
        }
        binding.inputCustomRunLimit.doAfterTextChanged {
            if (selectedRunLimitMode == RunLimitMode.CUSTOM) {
                updateRunLimitMode(RunLimitMode.CUSTOM, originalRule)
            }
        }

        binding.chooseDelayButton.setOnClickListener { showDelayPicker() }
        binding.absoluteDateButton.setOnClickListener { showAbsoluteDatePicker() }
        binding.absoluteTimeButton.setOnClickListener { showAbsoluteTimePicker() }
        binding.recurringTimeButton.setOnClickListener { showRecurringTimePicker() }
        binding.requestExactAlarmAccessButton.setOnClickListener { requestExactAlarmAccess() }
        binding.saveRuleButton.setOnClickListener { saveRule(finishAfterSave = true, showToast = true) }
        binding.testRuleButton.setOnClickListener { testRule() }
        binding.deleteRuleButton.setOnClickListener { deleteRule() }
    }

    private fun populateSeedRule() {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)?.takeIf { it.isNotBlank() }
        originalRule = ruleId?.let { id ->
            TriggerRuntime.listRules().firstOrNull { it.id == id }
        }
        val defaults = configManager.taskPromptSettings
        val seed = originalRule ?: TriggerRule(
            name = "",
            source = TriggerSource.NOTIFICATION_POSTED,
            promptTemplate = "",
            cooldownSeconds = TriggerEditorSupport.defaultCooldownSecondsFor(
                TriggerSource.NOTIFICATION_POSTED,
            ),
            busyPolicy = TriggerBusyPolicy.SKIP,
        )

        binding.topAppBar.title = if (originalRule == null) {
            "Add trigger rule"
        } else {
            "Edit trigger rule"
        }
        binding.editActionsRow.isVisible = originalRule != null

        binding.switchRuleEnabled.isChecked = seed.enabled
        binding.switchReturnToPortal.isChecked = seed.returnToPortal
        binding.switchEnableQueuing.isChecked = seed.busyPolicy == TriggerBusyPolicy.QUEUE
        binding.switchIncludeNotificationContext.isChecked = seed.includeNotificationContext
        binding.inputRuleName.setText(seed.name)
        binding.inputPromptTemplate.setText(seed.promptTemplate)
        binding.inputCooldownSeconds.setText(seed.cooldownSeconds.toString())
        binding.inputPackageName.setText(seed.packageName.orEmpty())
        binding.inputTitleFilter.setText(seed.titleFilter.orEmpty())
        binding.inputTextFilter.setText(seed.textFilter.orEmpty())
        binding.inputThresholdValue.setText(seed.thresholdValue?.toString().orEmpty())
        binding.inputPhoneNumber.setText(seed.phoneNumberFilter.orEmpty())
        binding.inputMessageFilter.setText(seed.messageFilter.orEmpty())
        binding.inputCustomRunLimit.setText(
            seed.maxLaunchCount?.takeIf { it > 1 }?.toString().orEmpty(),
        )

        selectedDelayMinutes = seed.delayMinutes
        seed.absoluteTimeMillis?.let { assignAbsoluteDateTime(it) }
        selectedRecurringHour = seed.dailyHour
        selectedRecurringMinute = seed.dailyMinute
        setSelectedWeekdays(seed.resolvedWeeklyDaysOfWeek())
        selectedRunLimitMode = when (seed.maxLaunchCount) {
            null -> RunLimitMode.ALWAYS
            1 -> RunLimitMode.ONCE
            else -> RunLimitMode.CUSTOM
        }

        binding.inputMatchMode.setText(
            matchModeOptions.firstOrNull { it.value == seed.stringMatchMode }?.label.orEmpty(),
            false,
        )
        binding.inputThresholdComparison.setText(
            thresholdComparisonOptions.firstOrNull { it.value == seed.thresholdComparison }?.label.orEmpty(),
            false,
        )
        binding.inputNetworkType.setText(
            networkTypeOptions.firstOrNull { it.value == seed.networkType }?.label.orEmpty(),
            false,
        )
        binding.inputRunLimitMode.setText(
            runLimitOptions.firstOrNull { it.value == selectedRunLimitMode }?.label.orEmpty(),
            false,
        )

        binding.switchOverrideTaskSettings.isChecked = seed.taskSettingsOverride != null
        binding.overrideTaskSettingsContainer.isVisible = seed.taskSettingsOverride != null
        settingsController.applySettings(seed.taskSettingsOverride ?: defaults)
        settingsController.setEnabled(seed.taskSettingsOverride != null)

        updateSourceSelection(seed.source)
        updateRunLimitMode(selectedRunLimitMode, seed)
        refreshTimeSummaries()
    }

    private fun loadModelOptions() {
        val authToken = configManager.reverseConnectionToken.trim()
        val restBaseUrl = PortalCloudClient.deriveRestBaseUrl(configManager.reverseConnectionUrlOrDefault)

        if (authToken.isBlank() || restBaseUrl == null) {
            settingsController.setModelOptions(PortalCloudClient.fallbackModelOptions())
            settingsController.setModelsLoading(false)
            binding.modelWarningText.isVisible = true
            binding.modelWarningText.text =
                "Using fallback models because the current Mobilerun connection is not configured."
            return
        }

        settingsController.setModelsLoading(true)
        portalCloudClient.loadModels(restBaseUrl, authToken) { result ->
            runOnUiThread {
                settingsController.setModelsLoading(false)
                if (result.loadedFromServer && result.models.isNotEmpty()) {
                    configManager.updateTaskPromptDefaultModel(result.models.first().id)
                }
                settingsController.setModelOptions(result.models)
                settingsController.applySettings(originalRule?.taskSettingsOverride ?: configManager.taskPromptSettings)
                binding.modelWarningText.isVisible = !result.warningMessage.isNullOrBlank()
                binding.modelWarningText.text = result.warningMessage.orEmpty()
            }
        }
    }

    private fun updateSourceSelection(source: TriggerSource) {
        selectedSource = source
        binding.inputTriggerSource.setText(
            sourceOptions.firstOrNull { it.value == source }?.label.orEmpty(),
            false,
        )
        binding.triggerSourceDescriptionText.text = TriggerUiSupport.sourceDescription(source)
        if (source == TriggerSource.TIME_DAILY || source == TriggerSource.TIME_WEEKLY) {
            binding.inputRepeatMode.setText(
                repeatModeOptions.firstOrNull { it.value == source }?.label.orEmpty(),
                false,
            )
        }
        val visibility = TriggerEditorSupport.visibilityFor(source)
        val capabilities = TriggerEditorSupport.capabilitiesFor(source)

        binding.matchModeLayout.isVisible = visibility.showMatchMode
        binding.packageNameLayout.isVisible = visibility.showPackageName
        binding.titleFilterLayout.isVisible = visibility.showTitleFilter
        binding.textFilterLayout.isVisible = visibility.showTextFilter
        binding.thresholdValueLayout.isVisible = visibility.showThreshold
        binding.thresholdComparisonLayout.isVisible = visibility.showThreshold
        binding.networkTypeLayout.isVisible = visibility.showNetworkType
        binding.phoneNumberLayout.isVisible = visibility.showPhoneNumber
        binding.messageFilterLayout.isVisible = visibility.showMessageFilter

        binding.detailsCard.isVisible = listOf(
            binding.matchModeLayout,
            binding.packageNameLayout,
            binding.titleFilterLayout,
            binding.textFilterLayout,
            binding.thresholdValueLayout,
            binding.thresholdComparisonLayout,
            binding.networkTypeLayout,
            binding.phoneNumberLayout,
            binding.messageFilterLayout,
        ).any { it.isVisible }

        binding.timeCard.isVisible = visibility.showDelay || visibility.showAbsoluteTime || visibility.showRecurringTime
        binding.delaySection.isVisible = visibility.showDelay
        binding.absoluteTimeSection.isVisible = visibility.showAbsoluteTime
        binding.recurringTimeSection.isVisible = visibility.showRecurringTime
        binding.inputCooldownSecondsLayout.isVisible = capabilities.supportsCooldown
        val showWeekdays = source == TriggerSource.TIME_WEEKLY
        binding.weekdayLabel.isVisible = showWeekdays
        binding.weekdayChipGroup.isVisible = showWeekdays
        if (!capabilities.supportsCooldown) {
            binding.inputCooldownSecondsLayout.error = null
        }
        refreshExactAlarmNotice()
        updateRunLimitMode(selectedRunLimitMode)
        refreshTimeSummaries()
    }

    private fun updateRunLimitMode(mode: RunLimitMode, seedRule: TriggerRule? = originalRule) {
        selectedRunLimitMode = mode
        val supportsRunLimit = TriggerEditorSupport.capabilitiesFor(selectedSource).supportsRunLimit
        binding.inputRunLimitMode.setText(
            runLimitOptions.firstOrNull { it.value == mode }?.label.orEmpty(),
            false,
        )
        binding.runLimitModeLayout.isVisible = supportsRunLimit
        binding.customRunLimitLayout.isVisible = supportsRunLimit && mode == RunLimitMode.CUSTOM
        if (!supportsRunLimit || mode == RunLimitMode.ONCE) {
            binding.inputCustomRunLimit.setText("")
        }
        val maxLaunchCount = if (!supportsRunLimit) {
            null
        } else when (mode) {
            RunLimitMode.ALWAYS -> null
            RunLimitMode.ONCE -> 1
            RunLimitMode.CUSTOM -> binding.inputCustomRunLimit.text?.toString()?.trim()?.toIntOrNull()
        }
        binding.customRunLimitLayout.error = null
        val successfulLaunchCount = seedRule?.successfulLaunchCount ?: 0
        binding.runLimitUsageText.isVisible = supportsRunLimit && seedRule != null && maxLaunchCount != null
        if (binding.runLimitUsageText.isVisible) {
            binding.runLimitUsageText.text =
                "Used $successfulLaunchCount of $maxLaunchCount successful launches."
        }
    }

    private fun refreshExactAlarmNotice() {
        val showNotice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selectedSource.isTimeSource()
        binding.exactAlarmNoticeContainer.isVisible = showNotice
        if (!showNotice) return

        val exactAlarmAvailable = hasExactAlarmAccess()
        binding.exactAlarmNoticeText.text = if (exactAlarmAvailable) {
            "Exact alarms are available. Minute-level time rules should run on time."
        } else {
            "Exact alarms are unavailable, so minute-level time rules can drift until access is granted."
        }
        binding.requestExactAlarmAccessButton.isVisible = !exactAlarmAvailable
    }

    private fun hasExactAlarmAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "Exact alarms do not need extra access on this Android version", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasExactAlarmAccess()) {
            Toast.makeText(this, "Exact alarms are already enabled", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                "package:$packageName".toUri(),
            ),
        )
    }

    private fun showDelayPicker() {
        val dialogBinding = DialogTriggerDurationPickerBinding.inflate(LayoutInflater.from(this))
        val currentMinutes = selectedDelayMinutes ?: 0
        dialogBinding.hoursPicker.minValue = 0
        dialogBinding.hoursPicker.maxValue = 999
        dialogBinding.hoursPicker.value = currentMinutes / 60
        dialogBinding.minutesPicker.minValue = 0
        dialogBinding.minutesPicker.maxValue = 59
        dialogBinding.minutesPicker.value = currentMinutes % 60
        dialogBinding.hoursPicker.wrapSelectorWheel = false
        dialogBinding.minutesPicker.wrapSelectorWheel = false

        AlertDialog.Builder(this)
            .setTitle("Choose delay")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val totalMinutes =
                    dialogBinding.hoursPicker.value * 60 + dialogBinding.minutesPicker.value
                selectedDelayMinutes = totalMinutes.coerceAtLeast(1)
                refreshTimeSummaries()
            }
            .show()
    }

    private fun showAbsoluteDatePicker() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Choose date")
            .setSelection(selectedAbsoluteDateUtcMs ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            selectedAbsoluteDateUtcMs = selection
            refreshTimeSummaries()
        }
        datePicker.show(supportFragmentManager, "absolute_date")
    }

    private fun showAbsoluteTimePicker() {
        val timePicker = MaterialTimePicker.Builder()
            .setTitleText("Choose time")
            .setHour(selectedAbsoluteHour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            .setMinute(selectedAbsoluteMinute ?: Calendar.getInstance().get(Calendar.MINUTE))
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .build()
        timePicker.addOnPositiveButtonClickListener {
            selectedAbsoluteHour = timePicker.hour
            selectedAbsoluteMinute = timePicker.minute
            refreshTimeSummaries()
        }
        timePicker.show(supportFragmentManager, "absolute_time")
    }

    private fun showRecurringTimePicker() {
        val timePicker = MaterialTimePicker.Builder()
            .setTitleText("Choose time")
            .setHour(selectedRecurringHour ?: 9)
            .setMinute(selectedRecurringMinute ?: 0)
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .build()
        timePicker.addOnPositiveButtonClickListener {
            selectedRecurringHour = timePicker.hour
            selectedRecurringMinute = timePicker.minute
            refreshTimeSummaries()
        }
        timePicker.show(supportFragmentManager, "recurring_time")
    }

    private fun refreshTimeSummaries() {
        binding.delaySummaryText.text = if (selectedDelayMinutes == null) {
            "Choose how long to wait before this trigger runs."
        } else {
            formatDelay(selectedDelayMinutes!!)
        }
        binding.absoluteSummaryText.text = buildAbsoluteTimeMillis()?.let { absoluteMs ->
            "Runs at ${formatDateTime(absoluteMs)}"
        } ?: "Choose a date and a time."

        val recurringTimeText = if (selectedRecurringHour == null || selectedRecurringMinute == null) {
            "Choose a time for this recurring trigger."
        } else {
            val summary = formatHourMinute(selectedRecurringHour!!, selectedRecurringMinute!!)
            when (selectedSource) {
                TriggerSource.TIME_DAILY -> "Runs every day at $summary"
                TriggerSource.TIME_WEEKLY -> {
                    val days = selectedWeekdays()
                        .mapNotNull { TriggerUiSupport.dayOfWeekLabel(it) }
                        .joinToString(", ")
                    if (days.isBlank()) {
                        "Choose at least one weekday for $summary"
                    } else {
                        "Runs on $days at $summary"
                    }
                }

                else -> summary
            }
        }
        binding.recurringSummaryText.text = recurringTimeText
    }

    private fun buildRuleOrShowErrors(): TriggerRule? {
        binding.inputRuleNameLayout.error = null
        binding.inputPromptTemplateLayout.error = null
        binding.inputCooldownSecondsLayout.error = null
        binding.thresholdValueLayout.error = null
        binding.customRunLimitLayout.error = null
        val capabilities = TriggerEditorSupport.capabilitiesFor(selectedSource)

        val ruleName = binding.inputRuleName.text?.toString()?.trim().orEmpty()
        val promptTemplate = binding.inputPromptTemplate.text?.toString()?.trim().orEmpty()
        if (ruleName.isBlank()) {
            binding.inputRuleNameLayout.error = "Required"
            return null
        }
        if (promptTemplate.isBlank()) {
            binding.inputPromptTemplateLayout.error = "Required"
            return null
        }

        val cooldownSeconds = if (capabilities.supportsCooldown) {
            binding.inputCooldownSeconds.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.toIntOrNull()
        } else {
            0
        }
        if (capabilities.supportsCooldown &&
            binding.inputCooldownSeconds.text?.isNotBlank() == true &&
            cooldownSeconds == null
        ) {
            binding.inputCooldownSecondsLayout.error = "Enter a valid number"
            return null
        }

        val thresholdValue = binding.inputThresholdValue.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()

        val maxLaunchCount = if (!capabilities.supportsRunLimit) {
            null
        } else {
            when (selectedRunLimitMode) {
                RunLimitMode.ALWAYS -> null
                RunLimitMode.ONCE -> 1
                RunLimitMode.CUSTOM -> {
                    val customLimit = binding.inputCustomRunLimit.text?.toString()?.trim()?.toIntOrNull()
                    if (customLimit == null || customLimit <= 0) {
                        binding.customRunLimitLayout.error = "Enter a positive number"
                        return null
                    }
                    customLimit
                }
            }
        }

        val overrideSettings = if (binding.switchOverrideTaskSettings.isChecked) {
            settingsController.buildSettingsOrShowErrors() ?: return null
        } else {
            null
        }

        val absoluteTimeMillis = when (selectedSource) {
            TriggerSource.TIME_DELAY -> {
                val delayMinutes = selectedDelayMinutes
                if (originalRule?.source == TriggerSource.TIME_DELAY &&
                    originalRule?.delayMinutes == delayMinutes
                ) {
                    originalRule?.absoluteTimeMillis
                } else {
                    null
                }
            }

            TriggerSource.TIME_ABSOLUTE -> {
                buildAbsoluteTimeMillis()
            }

            else -> null
        }

        val seed = originalRule ?: TriggerRule(
            name = ruleName,
            source = selectedSource,
            promptTemplate = promptTemplate,
            busyPolicy = TriggerBusyPolicy.SKIP,
        )
        val weeklyDays = selectedWeekdays().takeIf { selectedSource == TriggerSource.TIME_WEEKLY }
        val candidate = seed.copy(
            enabled = binding.switchRuleEnabled.isChecked,
            name = ruleName,
            source = selectedSource,
            promptTemplate = promptTemplate,
            cooldownSeconds = cooldownSeconds ?: 0,
            busyPolicy = if (binding.switchEnableQueuing.isChecked) {
                TriggerBusyPolicy.QUEUE
            } else {
                TriggerBusyPolicy.SKIP
            },
            stringMatchMode = selectedMatchMode(),
            packageName = binding.inputPackageName.text?.toString(),
            titleFilter = binding.inputTitleFilter.text?.toString(),
            textFilter = binding.inputTextFilter.text?.toString(),
            thresholdValue = thresholdValue,
            thresholdComparison = selectedThresholdComparison(),
            networkType = selectedNetworkType(),
            phoneNumberFilter = binding.inputPhoneNumber.text?.toString(),
            messageFilter = binding.inputMessageFilter.text?.toString(),
            absoluteTimeMillis = absoluteTimeMillis,
            delayMinutes = if (selectedSource == TriggerSource.TIME_DELAY) selectedDelayMinutes else null,
            dailyHour = if (selectedSource == TriggerSource.TIME_DAILY ||
                selectedSource == TriggerSource.TIME_WEEKLY
            ) {
                selectedRecurringHour
            } else {
                null
            },
            dailyMinute = if (selectedSource == TriggerSource.TIME_DAILY ||
                selectedSource == TriggerSource.TIME_WEEKLY
            ) {
                selectedRecurringMinute
            } else {
                null
            },
            weeklyDaysOfWeek = weeklyDays,
            weeklyDayOfWeek = weeklyDays?.firstOrNull(),
            maxLaunchCount = maxLaunchCount,
            successfulLaunchCount = originalRule?.successfulLaunchCount ?: 0,
            returnToPortal = binding.switchReturnToPortal.isChecked,
            includeNotificationContext = binding.switchIncludeNotificationContext.isChecked,
            taskSettingsOverride = overrideSettings,
        )
        val validation = TriggerRuleValidator.validateForSave(candidate)
        if (!validation.isValid) {
            applyValidationIssues(validation)
            return null
        }
        return validation.rule
    }

    private fun applyValidationIssues(validation: TriggerRuleValidator.Result) {
        validation.firstIssueFor(TriggerRuleValidator.Field.NAME)?.let {
            binding.inputRuleNameLayout.error = it.message
        }
        validation.firstIssueFor(TriggerRuleValidator.Field.PROMPT_TEMPLATE)?.let {
            binding.inputPromptTemplateLayout.error = it.message
        }
        validation.firstIssueFor(TriggerRuleValidator.Field.COOLDOWN_SECONDS)?.let {
            binding.inputCooldownSecondsLayout.error = it.message
        }
        validation.firstIssueFor(TriggerRuleValidator.Field.THRESHOLD_VALUE)?.let {
            binding.thresholdValueLayout.error = it.message
        }
        validation.firstIssueFor(TriggerRuleValidator.Field.MAX_LAUNCH_COUNT)?.let {
            binding.customRunLimitLayout.error = it.message
        }

        val toastMessage = validation.firstIssueFor(TriggerRuleValidator.Field.DELAY_MINUTES)?.message
            ?: validation.firstIssueFor(TriggerRuleValidator.Field.ABSOLUTE_TIME)?.message
            ?: validation.firstIssueFor(TriggerRuleValidator.Field.RECURRING_TIME)?.message
            ?: validation.firstIssueFor(TriggerRuleValidator.Field.WEEKLY_DAYS)?.message
        if (!toastMessage.isNullOrBlank()) {
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRule(finishAfterSave: Boolean, showToast: Boolean): TriggerRule? {
        val rule = buildRuleOrShowErrors() ?: return null
        TriggerRuntime.saveRule(rule)
        originalRule = TriggerRuntime.listRules().firstOrNull { it.id == rule.id } ?: rule
        if (showToast) {
            Toast.makeText(this, "Trigger rule saved", Toast.LENGTH_SHORT).show()
        }
        if (finishAfterSave) {
            setResult(RESULT_OK)
            finish()
        } else {
            populateSeedRule()
        }
        return originalRule
    }

    private fun testRule() {
        val savedRule = saveRule(finishAfterSave = false, showToast = false) ?: return
        TriggerRuntime.launchTest(savedRule.id)
        Toast.makeText(this, "Test run requested for ${savedRule.name}", Toast.LENGTH_SHORT).show()
    }

    private fun deleteRule() {
        val rule = originalRule ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete trigger")
            .setMessage("Delete '${rule.name}'?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                TriggerRuntime.deleteRule(rule.id)
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    private fun selectedMatchMode(): TriggerStringMatchMode {
        val label = binding.inputMatchMode.text?.toString()
        return matchModeOptions.firstOrNull { it.label == label }?.value
            ?: TriggerStringMatchMode.CONTAINS
    }

    private fun selectedThresholdComparison(): TriggerThresholdComparison {
        val label = binding.inputThresholdComparison.text?.toString()
        return thresholdComparisonOptions.firstOrNull { it.label == label }?.value
            ?: TriggerThresholdComparison.AT_OR_BELOW
    }

    private fun selectedNetworkType(): TriggerNetworkType? {
        val label = binding.inputNetworkType.text?.toString()
        return networkTypeOptions.firstOrNull { it.label == label }?.value
    }

    private fun selectedWeekdays(): List<Int> {
        return weekdayChips
            .filter { (_, chip) -> chip.isChecked }
            .map { (day, _) -> day }
            .sorted()
    }

    private fun setSelectedWeekdays(days: List<Int>) {
        weekdayChips.forEach { (day, chip) ->
            chip.isChecked = day in days
        }
    }

    private fun assignAbsoluteDateTime(absoluteTimeMillis: Long) {
        val localCalendar = Calendar.getInstance().apply {
            timeInMillis = absoluteTimeMillis
        }
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                localCalendar.get(Calendar.YEAR),
                localCalendar.get(Calendar.MONTH),
                localCalendar.get(Calendar.DAY_OF_MONTH),
                0,
                0,
                0,
            )
        }
        selectedAbsoluteDateUtcMs = utcCalendar.timeInMillis
        selectedAbsoluteHour = localCalendar.get(Calendar.HOUR_OF_DAY)
        selectedAbsoluteMinute = localCalendar.get(Calendar.MINUTE)
    }

    private fun buildAbsoluteTimeMillis(): Long? {
        val dateSelection = selectedAbsoluteDateUtcMs ?: return null
        val hour = selectedAbsoluteHour ?: return null
        val minute = selectedAbsoluteMinute ?: return null
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateSelection
        }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, utcCalendar.get(Calendar.YEAR))
            set(Calendar.MONTH, utcCalendar.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcCalendar.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatDelay(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> String.format(Locale.getDefault(), "%d h %d min", hours, minutes)
            hours > 0 -> String.format(Locale.getDefault(), "%d h", hours)
            else -> String.format(Locale.getDefault(), "%d min", minutes)
        }
    }

    private fun formatDateTime(timestampMs: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val datePart = DateFormat.getMediumDateFormat(this).format(calendar.time)
        return "$datePart ${formatHourMinute(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))}"
    }

    private fun formatHourMinute(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(this).format(calendar.time)
    }

    private fun TriggerSource.isTimeSource(): Boolean {
        return this == TriggerSource.TIME_DELAY ||
            this == TriggerSource.TIME_ABSOLUTE ||
            this == TriggerSource.TIME_DAILY ||
            this == TriggerSource.TIME_WEEKLY
    }
}
