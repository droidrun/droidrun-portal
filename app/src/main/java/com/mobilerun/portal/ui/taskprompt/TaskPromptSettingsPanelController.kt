package com.mobilerun.portal.ui.taskprompt

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mobilerun.portal.R
import com.mobilerun.portal.databinding.DialogTaskPromptModelPickerBinding
import com.mobilerun.portal.databinding.ItemTaskPromptModelOptionBinding
import com.mobilerun.portal.databinding.ViewTaskPromptSettingsPanelBinding
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalModelOption
import com.mobilerun.portal.taskprompt.PortalTaskSettings
import com.mobilerun.portal.taskprompt.TaskPromptSettingsConstraints
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class TaskPromptSettingsPanelController(
    private val context: Context,
    private val binding: ViewTaskPromptSettingsPanelBinding,
) {
    private val modelInputLayout: TextInputLayout
        get() = binding.taskPromptModelLayout
    private val modelInput: TextInputEditText
        get() = binding.taskPromptModelInput
    private val reasoningTile: MaterialCardView
        get() = binding.taskPromptReasoningTile
    private val reasoningSwitch: SwitchMaterial
        get() = binding.taskPromptReasoningToggle
    private val visionTile: MaterialCardView
        get() = binding.taskPromptVisionTile
    private val visionSwitch: SwitchMaterial
        get() = binding.taskPromptVisionToggle
    private val advancedHeader: LinearLayout
        get() = binding.taskPromptAdvancedHeader
    private val advancedChevron: ImageView
        get() = binding.taskPromptAdvancedChevron
    private val advancedContent: LinearLayout
        get() = binding.taskPromptAdvancedContent
    private val maxStepsInputLayout: TextInputLayout
        get() = binding.taskPromptMaxStepsLayout
    private val maxStepsInput: TextInputEditText
        get() = binding.taskPromptMaxStepsInput
    private val timeoutInputLayout: TextInputLayout
        get() = binding.taskPromptTimeoutLayout
    private val timeoutInput: TextInputEditText
        get() = binding.taskPromptTimeoutInput
    private val temperatureInputLayout: TextInputLayout
        get() = binding.taskPromptTemperatureLayout
    private val temperatureInput: TextInputEditText
        get() = binding.taskPromptTemperatureInput

    private var modelOptions: List<PortalModelOption> = emptyList()
    private var filteredModelOptions: List<PortalModelOption> = emptyList()
    private var currentSettings = PortalTaskSettings()
    private var isEnabled = true
    private var isModelsLoading = false
    private var isAdvancedExpanded = false
    private var selectedModelId: String? = null

    init {
        modelInput.isFocusable = false
        modelInput.isClickable = false
        modelInputLayout.setEndIconOnClickListener { openModelPicker() }
        modelInputLayout.setOnClickListener { openModelPicker() }
        modelInput.setOnClickListener { openModelPicker() }

        advancedHeader.setOnClickListener {
            if (!isEnabled) return@setOnClickListener
            isAdvancedExpanded = !isAdvancedExpanded
            advancedContent.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            advancedChevron.rotation = if (isAdvancedExpanded) 90f else 0f
        }

        reasoningTile.setOnClickListener {
            if (reasoningSwitch.isEnabled) {
                reasoningSwitch.isChecked = !reasoningSwitch.isChecked
            }
        }
        visionTile.setOnClickListener {
            if (visionSwitch.isEnabled) {
                visionSwitch.isChecked = !visionSwitch.isChecked
            }
        }
        reasoningSwitch.setOnCheckedChangeListener { _, _ -> updateToggleTiles() }
        visionSwitch.setOnCheckedChangeListener { _, _ -> updateToggleTiles() }
        maxStepsInput.filters = arrayOf(InputFilter.LengthFilter(4))
        timeoutInput.filters = arrayOf(InputFilter.LengthFilter(4))
        temperatureInput.filters = arrayOf(InputFilter.LengthFilter(4))
        installBoundedIntegerWatcher(
            input = maxStepsInput,
            inputLayout = maxStepsInputLayout,
            min = TaskPromptSettingsConstraints.MIN_MAX_STEPS,
            max = TaskPromptSettingsConstraints.MAX_MAX_STEPS,
        )
        installBoundedIntegerWatcher(
            input = timeoutInput,
            inputLayout = timeoutInputLayout,
            min = TaskPromptSettingsConstraints.MIN_EXECUTION_TIMEOUT,
            max = TaskPromptSettingsConstraints.MAX_EXECUTION_TIMEOUT,
        )
        installBoundedDoubleWatcher(
            input = temperatureInput,
            inputLayout = temperatureInputLayout,
            min = TaskPromptSettingsConstraints.MIN_TEMPERATURE,
            max = TaskPromptSettingsConstraints.MAX_TEMPERATURE,
        )

        updateToggleTiles()
        updateInteractivity()
    }

    fun applySettings(settings: PortalTaskSettings) {
        currentSettings = TaskPromptSettingsConstraints.clamp(settings)
        reasoningSwitch.isChecked = currentSettings.reasoning
        visionSwitch.isChecked = currentSettings.vision
        maxStepsInput.setText(currentSettings.maxSteps.toString())
        timeoutInput.setText(currentSettings.executionTimeout.toString())
        temperatureInput.setText(formatTemperature(currentSettings.temperature))
        selectModel(currentSettings.llmModel)
        updateToggleTiles()
        syncSettingTileHeights()
    }

    fun setModelOptions(options: List<PortalModelOption>) {
        val mergedOptions = LinkedHashMap<String, PortalModelOption>()
        val currentModelId = selectedModelId ?: currentSettings.llmModel
        if (!currentModelId.isNullOrBlank()) {
            PortalCloudClient.buildModelOptions(listOf(currentModelId)).forEach { mergedOptions[it.id] = it }
        }
        options.forEach { mergedOptions[it.id] = it }
        modelOptions = mergedOptions.values.toList()
        filteredModelOptions = modelOptions
        selectModel(currentModelId)
        updateInteractivity()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        updateInteractivity()
        updateToggleTiles()
        syncSettingTileHeights()
    }

    fun setModelsLoading(loading: Boolean) {
        isModelsLoading = loading
        updateInteractivity()
    }

    fun hasModels(): Boolean = modelOptions.isNotEmpty()

    fun buildSettingsOrShowErrors(): PortalTaskSettings? {
        modelInputLayout.error = null
        maxStepsInputLayout.error = null
        timeoutInputLayout.error = null
        temperatureInputLayout.error = null

        val modelId = selectedModelId
        if (modelId.isNullOrBlank()) {
            modelInputLayout.error = context.getString(R.string.task_prompt_model_required)
            return null
        }

        val maxSteps = maxStepsInput.text?.toString()?.trim()?.toIntOrNull()
        if (!TaskPromptSettingsConstraints.isValidMaxSteps(maxSteps)) {
            maxStepsInputLayout.error = context.getString(R.string.task_prompt_invalid_max_steps)
            return null
        }

        val executionTimeout = timeoutInput.text?.toString()?.trim()?.toIntOrNull()
        if (!TaskPromptSettingsConstraints.isValidExecutionTimeout(executionTimeout)) {
            timeoutInputLayout.error = context.getString(R.string.task_prompt_invalid_timeout)
            return null
        }

        val temperature = temperatureInput.text?.toString()?.trim()?.toDoubleOrNull()
        if (!TaskPromptSettingsConstraints.isValidTemperature(temperature)) {
            temperatureInputLayout.error = context.getString(R.string.task_prompt_invalid_temperature)
            return null
        }

        currentSettings = PortalTaskSettings(
            llmModel = modelId,
            reasoning = reasoningSwitch.isChecked,
            vision = visionSwitch.isChecked,
            maxSteps = maxSteps!!,
            temperature = temperature!!,
            executionTimeout = executionTimeout!!,
        )
        return currentSettings
    }

    private fun updateInteractivity() {
        val canInteractWithModel = isEnabled && !isModelsLoading && modelOptions.isNotEmpty()
        modelInputLayout.isEnabled = canInteractWithModel
        modelInput.isEnabled = canInteractWithModel
        reasoningTile.isEnabled = isEnabled
        reasoningSwitch.isEnabled = isEnabled
        visionTile.isEnabled = isEnabled
        visionSwitch.isEnabled = isEnabled
        advancedHeader.isEnabled = isEnabled
        advancedHeader.alpha = if (isEnabled) 1f else 0.6f
        maxStepsInput.isEnabled = isEnabled
        timeoutInput.isEnabled = isEnabled
        temperatureInput.isEnabled = isEnabled
    }

    private fun selectModel(modelId: String?) {
        if (modelId.isNullOrBlank()) return
        selectedModelId = modelId
        val selected = modelOptions.firstOrNull { it.id == modelId }
        modelInput.setText(selected?.label ?: PortalCloudClient.formatModelLabel(modelId))
    }

    private fun openModelPicker() {
        if (!isEnabled || modelOptions.isEmpty()) return

        val dialog = BottomSheetDialog(context)
        val dialogBinding = DialogTaskPromptModelPickerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(dialogBinding.root)

        val adapter = ModelPickerAdapter { selected ->
            selectedModelId = selected.id
            modelInputLayout.error = null
            modelInput.setText(selected.label)
            dialog.dismiss()
        }

        dialogBinding.taskPromptModelList.layoutManager = LinearLayoutManager(context)
        dialogBinding.taskPromptModelList.adapter = adapter
        dialogBinding.taskPromptModelList.itemAnimator = null

        fun updateFilteredOptions(query: String) {
            val normalizedQuery = query.trim().lowercase()
            filteredModelOptions =
                if (normalizedQuery.isBlank()) {
                    modelOptions
                } else {
                    modelOptions.filter { option ->
                        option.label.lowercase().contains(normalizedQuery) ||
                            option.id.lowercase().contains(normalizedQuery)
                    }
                }
            adapter.notifyDataSetChanged()
            dialogBinding.taskPromptModelEmptyText.visibility =
                if (filteredModelOptions.isEmpty()) View.VISIBLE else View.GONE
        }

        dialogBinding.taskPromptModelSearchInput.doAfterTextChanged { editable ->
            updateFilteredOptions(editable?.toString().orEmpty())
        }
        dialogBinding.taskPromptModelCloseButton.setOnClickListener { dialog.dismiss() }

        updateFilteredOptions("")
        dialog.setOnShowListener {
            dialog.behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = false
            }
        }
        dialog.show()
    }

    private fun updateToggleTiles() {
        updateToggleTile(reasoningTile, reasoningSwitch.isChecked)
        updateToggleTile(visionTile, visionSwitch.isChecked)
    }

    private fun syncSettingTileHeights() {
        val tileRow = reasoningTile.parent as? View ?: return
        resetTileHeight(reasoningTile)
        resetTileHeight(visionTile)
        tileRow.doOnLayout {
            val maxHeight = maxOf(
                measureTileHeight(reasoningTile),
                measureTileHeight(visionTile),
            )
            if (maxHeight <= 0) return@doOnLayout
            applyTileHeight(reasoningTile, maxHeight)
            applyTileHeight(visionTile, maxHeight)
        }
    }

    private fun resetTileHeight(card: MaterialCardView) {
        val layoutParams = card.layoutParams ?: return
        if (layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) return
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        card.layoutParams = layoutParams
    }

    private fun measureTileHeight(card: MaterialCardView): Int {
        if (card.width <= 0) {
            return card.measuredHeight
        }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return card.measuredHeight
    }

    private fun applyTileHeight(card: MaterialCardView, height: Int) {
        val layoutParams = card.layoutParams ?: return
        if (layoutParams.height == height) return
        layoutParams.height = height
        card.layoutParams = layoutParams
    }

    private fun updateToggleTile(card: MaterialCardView, isChecked: Boolean) {
        val backgroundColor = ContextCompat.getColor(
            context,
            if (isChecked) R.color.task_prompt_chip_info_bg else R.color.task_prompt_input_surface,
        )
        val strokeColor = ContextCompat.getColor(
            context,
            if (isChecked) R.color.task_prompt_accent else R.color.task_prompt_stroke,
        )
        card.setCardBackgroundColor(backgroundColor)
        card.strokeColor = strokeColor
        card.alpha = if (isEnabled) 1f else 0.6f
    }

    private fun formatTemperature(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            value.toString()
        }
    }

    private fun installBoundedIntegerWatcher(
        input: TextInputEditText,
        inputLayout: TextInputLayout,
        min: Int,
        max: Int,
    ) {
        var selfChange = false
        input.doAfterTextChanged { editable ->
            if (selfChange) return@doAfterTextChanged
            val rawValue = editable?.toString()?.trim().orEmpty()
            if (rawValue.isEmpty()) {
                inputLayout.error = null
                return@doAfterTextChanged
            }
            val parsedValue = rawValue.toIntOrNull() ?: return@doAfterTextChanged
            val boundedValue = parsedValue.coerceIn(min, max)
            inputLayout.error = null
            if (boundedValue == parsedValue) return@doAfterTextChanged
            selfChange = true
            input.setText(boundedValue.toString())
            input.setSelection(input.text?.length ?: 0)
            selfChange = false
        }
    }

    private fun installBoundedDoubleWatcher(
        input: TextInputEditText,
        inputLayout: TextInputLayout,
        min: Double,
        max: Double,
    ) {
        var selfChange = false
        input.doAfterTextChanged { editable ->
            if (selfChange) return@doAfterTextChanged
            val rawValue = editable?.toString()?.trim().orEmpty()
            if (rawValue.isEmpty() || rawValue == "." || rawValue == "-") {
                inputLayout.error = null
                return@doAfterTextChanged
            }
            val parsedValue = rawValue.toDoubleOrNull() ?: return@doAfterTextChanged
            val boundedValue = parsedValue.coerceIn(min, max)
            inputLayout.error = null
            if (boundedValue == parsedValue) return@doAfterTextChanged
            selfChange = true
            input.setText(formatTemperature(boundedValue))
            input.setSelection(input.text?.length ?: 0)
            selfChange = false
        }
    }

    private inner class ModelPickerAdapter(
        private val onModelSelected: (PortalModelOption) -> Unit,
    ) : RecyclerView.Adapter<ModelPickerAdapter.ModelViewHolder>() {
        private val inflater = LayoutInflater.from(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
            val itemBinding = ItemTaskPromptModelOptionBinding.inflate(inflater, parent, false)
            return ModelViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = filteredModelOptions.size

        override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
            holder.bind(filteredModelOptions[position])
        }

        inner class ModelViewHolder(
            private val itemBinding: ItemTaskPromptModelOptionBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(option: PortalModelOption) {
                itemBinding.taskPromptModelOptionTitle.text = option.label
                itemBinding.taskPromptModelOptionSubtitle.text = option.id

                val isSelected = option.id == selectedModelId
                itemBinding.taskPromptModelOptionSelectedIcon.visibility =
                    if (isSelected) View.VISIBLE else View.INVISIBLE
                itemBinding.taskPromptModelOptionCard.strokeColor = ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.task_prompt_accent else R.color.task_prompt_stroke,
                )
                itemBinding.taskPromptModelOptionCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (isSelected) R.color.task_prompt_chip_info_bg else R.color.task_prompt_input_surface,
                    ),
                )
                itemBinding.taskPromptModelOptionCard.setOnClickListener { onModelSelected(option) }
            }
        }
    }
}
