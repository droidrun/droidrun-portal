package com.mobilerun.portal.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Utility to auto-accept the MediaProjection permission dialog.
 *
 * Supports both:
 * 1. Spinner/dropdown-based two-step flows
 * 2. Inline list/radio-based flows where the share mode is visible in the dialog
 */
object MediaProjectionAutoAccept {
    private const val TAG = "MediaProjectionAutoAccept"

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val MEDIA_PROJECTION_ACTIVITY = "MediaProjectionPermissionActivity"

    private const val COOLDOWN_MS = 2000L
    private const val PENDING_SPINNER_TIMEOUT_MS = 1500L
    private const val PENDING_DROPDOWN_SETTLE_TIMEOUT_MS = 1000L
    private const val FAILURE_COOLDOWN_MS = 60_000L
    private const val ASSUMED_SELECTION_TTL_MS = 3000L
    private const val INLINE_SELECTION_RETRY_MS = 1500L
    private const val MAX_INLINE_SELECTION_ATTEMPTS = 2
    private const val DECISION_LOG_COOLDOWN_MS = 1500L

    private var lastSuccessTime = 0L
    private var pendingSpinnerOriginWindowId: Int? = null
    private var pendingSpinnerRequestedAtMs = 0L
    private var pendingSpinnerStrategy = PendingSpinnerStrategy.GENERIC
    private var pendingDropdownSettleRequestedAtMs = 0L
    private var pendingInlineSelectionWindowId: Int? = null
    private var pendingInlineSelectionRequestedAtMs = 0L
    private var pendingInlineSelectionAttempts = 0
    private var blockedUntilMs = 0L
    private var assumedEntireScreenUntilMs = 0L
    private var lastDecisionLogKey: String? = null
    private var lastDecisionLogAtMs = 0L

    private const val DIALOG_VIEW_ID = "com.android.systemui:id/screen_share_permission_dialog"
    private const val SHARE_MODE_OPTIONS_VIEW_ID =
        "com.android.systemui:id/screen_share_mode_options"
    private const val POSITIVE_BUTTON_ID = "android:id/button1"
    private const val LEGACY_ALERT_TITLE_ID = "android:id/alertTitle"
    private const val LEGACY_MESSAGE_ID = "android:id/message"
    private const val LEGACY_TEXT_MATCH = "recording or casting"
    private const val ANDROID_TEXT1_ID = "android:id/text1"

    private val START_BUTTON_TEXTS = listOf(
        "Share screen",
        "Start now",
        "Start",
        "Allow",
        "Accept",
        "OK",
        "Next",
    )

    private val ENTIRE_SCREEN_TEXTS = listOf(
        "Share entire screen",
        "Entire screen",
    )

    sealed class AutoAcceptResult {
        object NoAction : AutoAcceptResult()
        object ActionPerformed : AutoAcceptResult()
        data class Failed(val reason: String) : AutoAcceptResult()
    }

    internal enum class DialogMode {
        INLINE_OPTIONS,
        DROPDOWN,
        SPINNER,
        SINGLE_STEP,
        UNKNOWN,
    }

    internal enum class InlineSelection {
        ENTIRE_SCREEN_SELECTED,
        SINGLE_APP_SELECTED,
        UNKNOWN,
    }

    internal enum class PlannedAction {
        CLICK_POSITIVE_BUTTON,
        SELECT_INLINE_ENTIRE_SCREEN,
        OPEN_SPINNER,
        SELECT_DROPDOWN_OPTION,
        WAIT,
    }

    internal enum class OptionTarget {
        NONE,
        EXACT_ENTIRE_SCREEN,
        SECOND_OPTION,
        NON_SELECTED_OPTION,
    }

    internal enum class PendingSpinnerStrategy {
        GENERIC,
        AOSP_ORDERED_TWO_OPTION,
    }

    internal enum class PendingSpinnerStep {
        NO_TRANSACTION,
        WAIT,
        CONSUME_DROPDOWN,
        EXPIRE,
    }

    internal enum class PendingDropdownSettleStep {
        NO_PENDING,
        WAIT,
        CONTINUE,
        EXPIRE,
    }

    internal data class InlineOptionSnapshot(
        val label: String,
        val selected: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
    )

    internal data class DecisionInput(
        val optionListMode: DialogMode,
        val inlineOptions: List<InlineOptionSnapshot>,
        val optionCount: Int,
        val selectedOptionCount: Int,
        val hasPositiveButton: Boolean,
        val hasDialogRoot: Boolean,
        val hasShareModeOptionsViewId: Boolean,
        val listWindowLooksStandalone: Boolean,
        val hasSpinnerWidget: Boolean,
        val spinnerText: String,
        val assumeEntireScreen: Boolean,
        val selectedOptionRowIndex: Int?,
        val isAospSpinnerDialog: Boolean,
    )

    internal data class DialogDecision(
        val mode: DialogMode,
        val selection: InlineSelection,
        val action: PlannedAction,
        val optionTarget: OptionTarget = OptionTarget.NONE,
        val reason: String,
    )

    private data class DialogSignals(
        val hasDialogRoot: Boolean,
        val hasShareModeOptionsViewId: Boolean,
    )

    private data class OptionListContext(
        val mode: DialogMode,
        val nodes: List<InlineOptionNode>,
        val hasShareModeOptionsViewId: Boolean,
        val listWindowLooksStandalone: Boolean,
    ) {
        val optionCount: Int
            get() = nodes.size

        val selectedOptionCount: Int
            get() = nodes.count { it.snapshot.selected }

        val selectedOptionRowIndex: Int?
            get() = nodes.mapIndexedNotNull { index, node ->
                (index + 1).takeIf { node.snapshot.selected }
            }.singleOrNull()

        val allOptionsEnabledAndClickable: Boolean
            get() = nodes.isNotEmpty() && nodes.all { it.snapshot.enabled && it.snapshot.clickable }
    }

    private data class InlineOptionNode(
        val snapshot: InlineOptionSnapshot,
        val actionNode: AccessibilityNodeInfo?,
    )

    fun isMediaProjectionDialog(
        event: AccessibilityEvent?,
        eventClassName: String? = null,
    ): Boolean {
        if (event == null) return false
        val className = eventClassName ?: event.className?.toString()
        if (!className.isNullOrEmpty() && className.contains(MEDIA_PROJECTION_ACTIVITY)) {
            return true
        }

        val packageName = event.packageName?.toString() ?: return false
        return packageName == SYSTEM_UI_PACKAGE
    }

    fun tryAutoAccept(
        rootNode: AccessibilityNodeInfo?,
        eventClassName: String? = null,
    ): AutoAcceptResult {
        if (rootNode == null) return AutoAcceptResult.NoAction

        val now = System.currentTimeMillis()
        if (now - lastSuccessTime < COOLDOWN_MS) {
            return AutoAcceptResult.NoAction
        }

        clearExpiredBlock(now)
        if (isBlocked(now)) {
            return AutoAcceptResult.NoAction
        }

        val windowId = rootNode.windowId
        val packageName = rootNode.packageName?.toString().orEmpty()
        val isMediaProjectionActivity =
            !eventClassName.isNullOrEmpty() && eventClassName.contains(MEDIA_PROJECTION_ACTIVITY)
        if (packageName != SYSTEM_UI_PACKAGE && !isMediaProjectionActivity) {
            clearTransientState()
            return AutoAcceptResult.NoAction
        }

        if (!isActualMediaProjectionDialog(rootNode, eventClassName)) {
            clearTransientState()
            return AutoAcceptResult.NoAction
        }

        val positiveButton = findNodeByViewId(rootNode, POSITIVE_BUTTON_ID)
            ?: findButtonByTexts(rootNode, START_BUTTON_TEXTS)
        val spinner = findSpinner(rootNode)
        val dialogSignals = buildDialogSignals(rootNode)
        val optionListContext = buildOptionListContext(
            rootNode = rootNode,
            hasPositiveButton = positiveButton != null,
            dialogSignals = dialogSignals,
        )
        val inlineOptionNodes = optionListContext.nodes
        val inlineSnapshots = inlineOptionNodes.map { it.snapshot }
        val inlineSelection = inferInlineSelection(inlineSnapshots)
        val localizedInlineSecondOptionSelected =
            optionListContext.mode == DialogMode.INLINE_OPTIONS &&
                    optionListContext.hasShareModeOptionsViewId &&
                    optionListContext.optionCount == 2 &&
                    optionListContext.selectedOptionCount == 1 &&
                    optionListContext.selectedOptionRowIndex == 2
        val isAospSpinnerMainDialog = isAospSpinnerDialog(
            dialogSignals = dialogSignals,
            optionListContext = optionListContext,
            hasSpinnerWidget = spinner != null,
            hasPositiveButton = positiveButton != null,
        )

        clearInlineSelectionIfWindowChanged(windowId)

        val pendingInlineResult = handlePendingInlineSelection(
            windowId = windowId,
            inlineSelection = inlineSelection,
            localizedInlineSecondOptionSelected = localizedInlineSecondOptionSelected,
            now = now,
        )
        if (pendingInlineResult != null) {
            recyclePendingBranchNodes(
                positiveButton = positiveButton,
                spinner = spinner,
                inlineOptionNodes = inlineOptionNodes,
            )
            return pendingInlineResult
        }

        val pendingSpinnerResult = handlePendingSpinnerSelection(
            optionListContext = optionListContext,
            now = now,
        )
        if (pendingSpinnerResult != null) {
            recyclePendingBranchNodes(
                positiveButton = positiveButton,
                spinner = spinner,
                inlineOptionNodes = inlineOptionNodes,
            )
            return pendingSpinnerResult
        }

        val pendingDropdownSettleResult = handlePendingDropdownSettle(
            optionListContext = optionListContext,
            now = now,
        )
        if (pendingDropdownSettleResult != null) {
            recyclePendingBranchNodes(
                positiveButton = positiveButton,
                spinner = spinner,
                inlineOptionNodes = inlineOptionNodes,
            )
            return pendingDropdownSettleResult
        }

        val spinnerText = spinner?.let { getSpinnerSelectedText(it) }.orEmpty()
        val decision = decideAction(
            DecisionInput(
                optionListMode = optionListContext.mode,
                inlineOptions = inlineSnapshots,
                optionCount = optionListContext.optionCount,
                selectedOptionCount = optionListContext.selectedOptionCount,
                hasPositiveButton = positiveButton != null,
                hasDialogRoot = dialogSignals.hasDialogRoot,
                hasShareModeOptionsViewId = optionListContext.hasShareModeOptionsViewId,
                listWindowLooksStandalone = optionListContext.listWindowLooksStandalone,
                hasSpinnerWidget = spinner != null,
                spinnerText = spinnerText,
                assumeEntireScreen = isAssumedEntireScreen(now),
                selectedOptionRowIndex = optionListContext.selectedOptionRowIndex,
                isAospSpinnerDialog = isAospSpinnerMainDialog,
            ),
        )
        spinner?.recycle()
        logDecision(decision, now)

        return when (decision.action) {
            PlannedAction.CLICK_POSITIVE_BUTTON -> {
                recycleInlineOptionNodes(inlineOptionNodes)
                val button = positiveButton ?: return AutoAcceptResult.NoAction
                Log.i(TAG, "Clicking positive button")
                val result = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                button.recycle()
                if (result) {
                    lastSuccessTime = now
                    clearTransientState()
                    Log.i(TAG, "MediaProjection dialog accepted!")
                    AutoAcceptResult.ActionPerformed
                } else {
                    markFailure("Failed to click positive button")
                }
            }

            PlannedAction.SELECT_INLINE_ENTIRE_SCREEN -> {
                positiveButton?.recycle()
                val optionNode = findTargetOptionNode(
                    optionNodes = inlineOptionNodes,
                    optionTarget = decision.optionTarget,
                )
                recycleInlineOptionNodes(inlineOptionNodes, except = optionNode)
                if (optionNode == null) {
                    return markFailure("Safe inline target option not found")
                }
                logOptionSelection(decision.mode, decision.optionTarget)
                val result = performOptionAction(optionNode)
                optionNode.recycle()
                if (result) {
                    pendingInlineSelectionWindowId = windowId
                    pendingInlineSelectionRequestedAtMs = now
                    pendingInlineSelectionAttempts += 1
                    AutoAcceptResult.ActionPerformed
                } else {
                    markFailure("Failed to select inline Entire screen option")
                }
            }

            PlannedAction.OPEN_SPINNER -> {
                positiveButton?.recycle()
                recycleInlineOptionNodes(inlineOptionNodes)
                val opened = clickSpinnerToChange(rootNode)
                if (opened) {
                    pendingSpinnerOriginWindowId = windowId
                    pendingSpinnerRequestedAtMs = now
                    pendingSpinnerStrategy = if (isAospSpinnerMainDialog) {
                        PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION
                    } else {
                        PendingSpinnerStrategy.GENERIC
                    }
                    Log.d(
                        TAG,
                        "Started pending spinner transaction strategy=$pendingSpinnerStrategy originWindowId=$windowId",
                    )
                    AutoAcceptResult.ActionPerformed
                } else {
                    markFailure("Spinner present but could not open options")
                }
            }

            PlannedAction.SELECT_DROPDOWN_OPTION -> {
                positiveButton?.recycle()
                trySelectDropdownOption(
                    optionListContext = optionListContext,
                    optionTarget = decision.optionTarget,
                    now = now,
                )
            }

            PlannedAction.WAIT -> {
                positiveButton?.recycle()
                recycleInlineOptionNodes(inlineOptionNodes)
                AutoAcceptResult.NoAction
            }
        }
    }

    internal fun decideAction(input: DecisionInput): DialogDecision {
        val inlineSelection = inferInlineSelection(input.inlineOptions)
        val hasInlineEntireScreenOption = input.inlineOptions.any { isEntireScreenLabel(it.label) }
        val hasStructuralFallback = input.optionCount == 2 && input.selectedOptionCount == 1
        val hasLocalizedInlineSecondRowHeuristic =
            input.hasShareModeOptionsViewId &&
                    input.hasPositiveButton &&
                    input.optionCount == 2 &&
                    input.selectedOptionCount == 1 &&
                    !hasInlineEntireScreenOption

        if (input.optionListMode == DialogMode.INLINE_OPTIONS) {
            return when {
                inlineSelection == InlineSelection.ENTIRE_SCREEN_SELECTED ->
                    DialogDecision(
                        mode = DialogMode.INLINE_OPTIONS,
                        selection = inlineSelection,
                        action = if (input.hasPositiveButton) {
                            PlannedAction.CLICK_POSITIVE_BUTTON
                        } else {
                            PlannedAction.WAIT
                        },
                        reason = if (input.hasPositiveButton) {
                            "inline entire screen selected"
                        } else {
                            "inline entire screen selected but positive button missing"
                        },
                    )

                hasInlineEntireScreenOption ->
                    DialogDecision(
                        mode = DialogMode.INLINE_OPTIONS,
                        selection = inlineSelection,
                        action = PlannedAction.SELECT_INLINE_ENTIRE_SCREEN,
                        optionTarget = OptionTarget.EXACT_ENTIRE_SCREEN,
                        reason = when (inlineSelection) {
                            InlineSelection.SINGLE_APP_SELECTED -> "inline single app selected"
                            InlineSelection.UNKNOWN -> "inline exact entire screen option available"
                            InlineSelection.ENTIRE_SCREEN_SELECTED -> "inline entire screen selected"
                        },
                    )

                hasLocalizedInlineSecondRowHeuristic && input.selectedOptionRowIndex == 2 ->
                    DialogDecision(
                        mode = DialogMode.INLINE_OPTIONS,
                        selection = inlineSelection,
                        action = PlannedAction.CLICK_POSITIVE_BUTTON,
                        reason = "localized inline second-row heuristic: row 2 selected",
                    )

                hasLocalizedInlineSecondRowHeuristic && input.selectedOptionRowIndex == 1 ->
                    DialogDecision(
                        mode = DialogMode.INLINE_OPTIONS,
                        selection = inlineSelection,
                        action = PlannedAction.SELECT_INLINE_ENTIRE_SCREEN,
                        optionTarget = OptionTarget.SECOND_OPTION,
                        reason = "localized inline second-row heuristic: row 1 selected",
                    )

                input.optionCount > 0 ->
                    DialogDecision(
                        mode = DialogMode.INLINE_OPTIONS,
                        selection = inlineSelection,
                        action = PlannedAction.WAIT,
                        reason = "inline options present but no safe target inferred",
                    )

                else ->
                    DialogDecision(
                        mode = DialogMode.UNKNOWN,
                        selection = InlineSelection.UNKNOWN,
                        action = PlannedAction.WAIT,
                        reason = "no supported controls found",
                    )
            }
        }

        if (input.optionListMode == DialogMode.DROPDOWN) {
            return when {
                inlineSelection == InlineSelection.ENTIRE_SCREEN_SELECTED ->
                    DialogDecision(
                        mode = DialogMode.DROPDOWN,
                        selection = inlineSelection,
                        action = PlannedAction.WAIT,
                        reason = "dropdown already shows entire screen selected",
                    )

                hasInlineEntireScreenOption ->
                    DialogDecision(
                        mode = DialogMode.DROPDOWN,
                        selection = inlineSelection,
                        action = PlannedAction.SELECT_DROPDOWN_OPTION,
                        optionTarget = OptionTarget.EXACT_ENTIRE_SCREEN,
                        reason = "dropdown exact entire screen option available",
                    )

                hasStructuralFallback ->
                    DialogDecision(
                        mode = DialogMode.DROPDOWN,
                        selection = inlineSelection,
                        action = PlannedAction.SELECT_DROPDOWN_OPTION,
                        optionTarget = OptionTarget.NON_SELECTED_OPTION,
                        reason = "dropdown structural two-option fallback",
                    )

                input.optionCount > 0 ->
                    DialogDecision(
                        mode = DialogMode.DROPDOWN,
                        selection = inlineSelection,
                        action = PlannedAction.WAIT,
                        reason = "dropdown options present but no safe target inferred",
                    )

                else ->
                    DialogDecision(
                        mode = DialogMode.UNKNOWN,
                        selection = InlineSelection.UNKNOWN,
                        action = PlannedAction.WAIT,
                        reason = "no supported controls found",
                    )
            }
        }

        val spinnerShowsEntireScreen = spinnerLooksLikeEntireScreen(input)
        if (
            input.hasPositiveButton &&
            input.hasSpinnerWidget &&
            input.spinnerText.isNotBlank() &&
            !spinnerShowsEntireScreen &&
            !input.assumeEntireScreen
        ) {
            return DialogDecision(
                mode = DialogMode.SPINNER,
                selection = InlineSelection.UNKNOWN,
                action = PlannedAction.OPEN_SPINNER,
                reason = if (input.isAospSpinnerDialog) {
                    "aosp localized spinner selection requires dropdown"
                } else {
                    "spinner selection is not entire screen"
                },
            )
        }

        if (input.hasPositiveButton) {
            return DialogDecision(
                mode = DialogMode.SINGLE_STEP,
                selection = if (input.assumeEntireScreen) {
                    InlineSelection.ENTIRE_SCREEN_SELECTED
                } else {
                    InlineSelection.UNKNOWN
                },
                action = PlannedAction.CLICK_POSITIVE_BUTTON,
                reason = "positive button available",
            )
        }

        return DialogDecision(
            mode = DialogMode.UNKNOWN,
            selection = InlineSelection.UNKNOWN,
            action = PlannedAction.WAIT,
            reason = "no supported controls found",
        )
    }

    internal fun inferInlineSelection(
        options: List<InlineOptionSnapshot>,
    ): InlineSelection {
        val entireScreenOption = options.firstOrNull { isEntireScreenLabel(it.label) }
            ?: return InlineSelection.UNKNOWN
        if (entireScreenOption.selected) {
            return InlineSelection.ENTIRE_SCREEN_SELECTED
        }
        if (options.any { it != entireScreenOption && it.selected }) {
            return InlineSelection.SINGLE_APP_SELECTED
        }
        return InlineSelection.UNKNOWN
    }

    internal fun inspectPendingSpinnerStepForTest(
        hasPendingTransaction: Boolean,
        optionListMode: DialogMode,
        elapsedMs: Long,
    ): PendingSpinnerStep {
        return evaluatePendingSpinnerStep(
            hasPendingTransaction = hasPendingTransaction,
            optionListMode = optionListMode,
            elapsedMs = elapsedMs,
        )
    }

    internal fun startPendingSpinnerTransactionForTest(
        requestedAtMs: Long,
        strategy: PendingSpinnerStrategy,
        originWindowId: Int? = null,
    ) {
        pendingSpinnerOriginWindowId = originWindowId
        pendingSpinnerRequestedAtMs = requestedAtMs
        pendingSpinnerStrategy = strategy
    }

    internal fun hasPendingSpinnerTransactionForTest(): Boolean {
        return hasPendingSpinnerTransaction()
    }

    internal fun clearTransientStateForTest() {
        clearTransientState()
    }

    internal fun inspectPendingDropdownSettleStepForTest(
        hasPendingDropdownSettle: Boolean,
        optionListMode: DialogMode,
        elapsedMs: Long,
    ): PendingDropdownSettleStep {
        return evaluatePendingDropdownSettleStep(
            hasPendingDropdownSettle = hasPendingDropdownSettle,
            optionListMode = optionListMode,
            elapsedMs = elapsedMs,
        )
    }

    internal fun startPendingDropdownSettleForTest(requestedAtMs: Long) {
        pendingDropdownSettleRequestedAtMs = requestedAtMs
    }

    internal fun hasPendingDropdownSettleForTest(): Boolean {
        return hasPendingDropdownSettle()
    }

    private fun clickSpinnerToChange(rootNode: AccessibilityNodeInfo): Boolean {
        val spinner = findSpinner(rootNode) ?: return false
        Log.d(TAG, "Clicking spinner to select 'Entire screen'")
        val result = spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        spinner.recycle()
        return result
    }

    private fun handlePendingSpinnerSelection(
        optionListContext: OptionListContext,
        now: Long,
    ): AutoAcceptResult? {
        return when (
            evaluatePendingSpinnerStep(
                hasPendingTransaction = hasPendingSpinnerTransaction(),
                optionListMode = optionListContext.mode,
                elapsedMs = now - pendingSpinnerRequestedAtMs,
            )
        ) {
            PendingSpinnerStep.NO_TRANSACTION -> null

            PendingSpinnerStep.CONSUME_DROPDOWN -> {
                val pendingStrategy = pendingSpinnerStrategy
                Log.d(
                    TAG,
                    "Consuming pending spinner transaction strategy=$pendingStrategy originWindowId=$pendingSpinnerOriginWindowId",
                )
                clearPendingSpinnerTransaction()
                val optionTarget = resolveOptionTarget(
                    optionListContext = optionListContext,
                    pendingStrategy = pendingStrategy,
                )
                if (optionTarget == null) {
                    return markFailure("Dropdown options visible but no safe target inferred")
                }
                trySelectDropdownOption(optionListContext, optionTarget, now)
            }

            PendingSpinnerStep.EXPIRE -> {
                clearPendingSpinnerTransaction()
                markFailure("No selectable option after opening spinner")
            }

            PendingSpinnerStep.WAIT -> AutoAcceptResult.NoAction
        }
    }

    private fun handlePendingDropdownSettle(
        optionListContext: OptionListContext,
        now: Long,
    ): AutoAcceptResult? {
        return when (
            evaluatePendingDropdownSettleStep(
                hasPendingDropdownSettle = hasPendingDropdownSettle(),
                optionListMode = optionListContext.mode,
                elapsedMs = now - pendingDropdownSettleRequestedAtMs,
            )
        ) {
            PendingDropdownSettleStep.NO_PENDING -> null

            PendingDropdownSettleStep.WAIT -> {
                Log.d(TAG, "Waiting for dropdown dismissal after selection")
                AutoAcceptResult.NoAction
            }

            PendingDropdownSettleStep.CONTINUE -> {
                clearPendingDropdownSettle()
                null
            }

            PendingDropdownSettleStep.EXPIRE -> {
                Log.d(TAG, "Dropdown settle expired")
                clearPendingDropdownSettle()
                markFailure("Dropdown selection did not dismiss")
            }
        }
    }

    private fun handlePendingInlineSelection(
        windowId: Int,
        inlineSelection: InlineSelection,
        localizedInlineSecondOptionSelected: Boolean,
        now: Long,
    ): AutoAcceptResult? {
        if (pendingInlineSelectionWindowId != windowId) return null

        if (
            inlineSelection == InlineSelection.ENTIRE_SCREEN_SELECTED ||
            localizedInlineSecondOptionSelected
        ) {
            clearPendingInlineSelection()
            return null
        }

        if (now - pendingInlineSelectionRequestedAtMs < INLINE_SELECTION_RETRY_MS) {
            return AutoAcceptResult.NoAction
        }

        if (pendingInlineSelectionAttempts >= MAX_INLINE_SELECTION_ATTEMPTS) {
            return markFailure("Inline Entire screen selection did not settle")
        }

        clearPendingInlineSelection()
        return null
    }

    private fun isActualMediaProjectionDialog(
        node: AccessibilityNodeInfo,
        eventClassName: String?,
    ): Boolean {
        val directSignal = hasDirectMediaProjectionSignal(node, eventClassName)
        if (directSignal) {
            return true
        }

        return isContinuationMediaProjectionOptionList(node)
    }

    private fun hasDirectMediaProjectionSignal(
        node: AccessibilityNodeInfo,
        eventClassName: String?,
    ): Boolean {
        if (!eventClassName.isNullOrEmpty() &&
            eventClassName.contains(MEDIA_PROJECTION_ACTIVITY)
        ) {
            return true
        }

        val dialogNodes = node.findAccessibilityNodeInfosByViewId(DIALOG_VIEW_ID)
        if (dialogNodes.isNotEmpty()) {
            dialogNodes.forEach { it.recycle() }
            return true
        }

        val shareModeNodes = node.findAccessibilityNodeInfosByViewId(SHARE_MODE_OPTIONS_VIEW_ID)
        if (shareModeNodes.isNotEmpty()) {
            shareModeNodes.forEach { it.recycle() }
            return true
        }

        for (text in ENTIRE_SCREEN_TEXTS) {
            val matches = node.findAccessibilityNodeInfosByText(text)
            if (matches.isNotEmpty()) {
                matches.forEach { it.recycle() }
                return true
            }
        }

        val legacyTitle = findNodeByViewId(node, LEGACY_ALERT_TITLE_ID)
        if (legacyTitle != null) {
            val titleText = legacyTitle.text?.toString().orEmpty()
            legacyTitle.recycle()
            if (titleText.contains(LEGACY_TEXT_MATCH, ignoreCase = true)) {
                return true
            }
        }

        val legacyMessage = findNodeByViewId(node, LEGACY_MESSAGE_ID)
        if (legacyMessage != null) {
            val messageText = legacyMessage.text?.toString().orEmpty()
            legacyMessage.recycle()
            if (messageText.contains(LEGACY_TEXT_MATCH, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun isContinuationMediaProjectionOptionList(rootNode: AccessibilityNodeInfo): Boolean {
        if (rootNode.packageName?.toString() != SYSTEM_UI_PACKAGE) {
            return false
        }
        if (!allowsContinuationListFallback()) {
            return false
        }
        return looksLikeMediaProjectionOptionList(rootNode)
    }

    private fun buildDialogSignals(rootNode: AccessibilityNodeInfo): DialogSignals {
        return DialogSignals(
            hasDialogRoot = hasViewId(rootNode, DIALOG_VIEW_ID) ||
                    hasViewId(rootNode, LEGACY_ALERT_TITLE_ID) ||
                    hasViewId(rootNode, LEGACY_MESSAGE_ID),
            hasShareModeOptionsViewId = hasViewId(rootNode, SHARE_MODE_OPTIONS_VIEW_ID),
        )
    }

    private fun buildOptionListContext(
        rootNode: AccessibilityNodeInfo,
        hasPositiveButton: Boolean,
        dialogSignals: DialogSignals,
    ): OptionListContext {
        val container = findOptionListContainer(rootNode) ?: return OptionListContext(
            mode = DialogMode.UNKNOWN,
            nodes = emptyList(),
            hasShareModeOptionsViewId = dialogSignals.hasShareModeOptionsViewId,
            listWindowLooksStandalone = false,
        )

        try {
            if (!looksLikeOptionListContainer(container)) {
                return OptionListContext(
                    mode = DialogMode.UNKNOWN,
                    nodes = emptyList(),
                    hasShareModeOptionsViewId = dialogSignals.hasShareModeOptionsViewId,
                    listWindowLooksStandalone = false,
                )
            }

            val nodes = buildOptionNodesFromContainer(container)
            val listWindowLooksStandalone = nodes.isNotEmpty() &&
                    !dialogSignals.hasDialogRoot &&
                    !dialogSignals.hasShareModeOptionsViewId &&
                    !hasPositiveButton
            val mode = when {
                nodes.isEmpty() -> DialogMode.UNKNOWN
                dialogSignals.hasShareModeOptionsViewId ||
                        dialogSignals.hasDialogRoot ||
                        hasPositiveButton -> DialogMode.INLINE_OPTIONS

                listWindowLooksStandalone -> DialogMode.DROPDOWN
                else -> DialogMode.DROPDOWN
            }

            return OptionListContext(
                mode = mode,
                nodes = nodes,
                hasShareModeOptionsViewId = dialogSignals.hasShareModeOptionsViewId,
                listWindowLooksStandalone = listWindowLooksStandalone,
            )
        } finally {
            container.recycle()
        }
    }

    private fun findOptionListContainer(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val optionsNode = findNodeByViewId(rootNode, SHARE_MODE_OPTIONS_VIEW_ID)
        if (optionsNode != null) {
            val className = optionsNode.className?.toString().orEmpty()
            if (className.contains("ListView") || className.contains("RecyclerView")) {
                return optionsNode
            }
            optionsNode.recycle()
        }

        return findFirstListNode(rootNode)
    }

    private fun buildOptionNodesFromContainer(
        container: AccessibilityNodeInfo,
    ): List<InlineOptionNode> {
        val result = mutableListOf<InlineOptionNode>()
        for (i in 0 until container.childCount) {
            val row = container.getChild(i) ?: continue
            try {
                val label = findInlineOptionLabel(row)
                if (label.isBlank()) continue
                result += InlineOptionNode(
                    snapshot = InlineOptionSnapshot(
                        label = label,
                        selected = isInlineOptionSelected(row),
                        enabled = row.isEnabled,
                        clickable = row.isClickable || hasAction(
                            row,
                            AccessibilityNodeInfo.ACTION_CLICK
                        ),
                    ),
                    actionNode = findInlineOptionActionNode(row),
                )
            } finally {
                row.recycle()
            }
        }
        return result
    }

    private fun looksLikeOptionListContainer(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        if (!className.contains("ListView") && !className.contains("RecyclerView")) {
            return false
        }

        var labeledRowCount = 0
        for (i in 0 until node.childCount) {
            val row = node.getChild(i) ?: continue
            try {
                val label = findInlineOptionLabel(row)
                if (label.isNotBlank()) {
                    labeledRowCount += 1
                }
            } finally {
                row.recycle()
            }
        }

        return labeledRowCount >= 2
    }

    private fun findInlineOptionLabel(node: AccessibilityNodeInfo): String {
        val text1Node = findNodeByViewId(node, ANDROID_TEXT1_ID)
        if (text1Node != null) {
            val text = text1Node.text?.toString().orEmpty()
            text1Node.recycle()
            if (text.isNotBlank()) {
                return text
            }
        }
        return findFirstTextInSubtree(node).orEmpty()
    }

    private fun findFirstTextInSubtree(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = try {
                findFirstTextInSubtree(child)
            } finally {
                child.recycle()
            }
            if (!result.isNullOrBlank()) {
                return result
            }
        }
        return null
    }

    private fun isInlineOptionSelected(node: AccessibilityNodeInfo): Boolean {
        if (node.collectionItemInfo?.isSelected == true) return true
        if (node.isSelected || node.isChecked) return true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            isPositiveSelectionDescription(node.stateDescription?.toString())
        ) return true

        val checkedIndicator = findCheckedIndicator(node)
        if (checkedIndicator != null) {
            checkedIndicator.recycle()
            return true
        }

        return false
    }

    private fun findCheckedIndicator(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.collectionItemInfo?.isSelected == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        val checkedByState =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                isPositiveSelectionDescription(node.stateDescription?.toString())
        if (node.isChecked || checkedByState) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCheckedIndicator(child)
            child.recycle()
            if (result != null) return result
        }

        return null
    }

    private fun findInlineOptionActionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable || hasAction(node, AccessibilityNodeInfo.ACTION_SELECT)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        return findClickableOrCheckableChild(node, 2)
    }

    private fun looksLikeMediaProjectionOptionList(rootNode: AccessibilityNodeInfo): Boolean {
        val container = findOptionListContainer(rootNode) ?: return false
        return try {
            looksLikeOptionListContainer(container)
        } finally {
            container.recycle()
        }
    }

    internal fun allowsContinuationListFallbackForTest(
        hasPendingSpinnerTransaction: Boolean,
        hasPendingDropdownSettle: Boolean,
    ): Boolean {
        return allowsContinuationListFallback(
            hasPendingSpinnerTransaction = hasPendingSpinnerTransaction,
            hasPendingDropdownSettle = hasPendingDropdownSettle,
        )
    }

    private fun allowsContinuationListFallback(): Boolean {
        return allowsContinuationListFallback(
            hasPendingSpinnerTransaction = hasPendingSpinnerTransaction(),
            hasPendingDropdownSettle = hasPendingDropdownSettle(),
        )
    }

    private fun allowsContinuationListFallback(
        hasPendingSpinnerTransaction: Boolean,
        hasPendingDropdownSettle: Boolean,
    ): Boolean {
        return hasPendingSpinnerTransaction || hasPendingDropdownSettle
    }

    internal fun resolveOptionTargetForTest(
        optionSnapshots: List<InlineOptionSnapshot>,
        pendingStrategy: PendingSpinnerStrategy = PendingSpinnerStrategy.GENERIC,
    ): OptionTarget? {
        return when {
            optionSnapshots.any { isEntireScreenLabel(it.label) } ->
                OptionTarget.EXACT_ENTIRE_SCREEN

            pendingStrategy == PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION &&
                    optionSnapshots.size == 2 &&
                    optionSnapshots.all { it.enabled && it.clickable } ->
                OptionTarget.SECOND_OPTION

            optionSnapshots.size == 2 && optionSnapshots.count { it.selected } == 1 ->
                OptionTarget.NON_SELECTED_OPTION

            else -> null
        }
    }

    private fun resolveOptionTarget(
        optionListContext: OptionListContext,
        pendingStrategy: PendingSpinnerStrategy = PendingSpinnerStrategy.GENERIC,
    ): OptionTarget? {
        return resolveOptionTargetForTest(
            optionSnapshots = optionListContext.nodes.map { it.snapshot },
            pendingStrategy = pendingStrategy,
        )
    }

    private fun findTargetOptionNode(
        optionNodes: List<InlineOptionNode>,
        optionTarget: OptionTarget,
    ): AccessibilityNodeInfo? {
        return when (optionTarget) {
            OptionTarget.EXACT_ENTIRE_SCREEN ->
                optionNodes.firstOrNull { isEntireScreenLabel(it.snapshot.label) }?.actionNode

            OptionTarget.SECOND_OPTION ->
                optionNodes.getOrNull(1)?.actionNode

            OptionTarget.NON_SELECTED_OPTION ->
                optionNodes.firstOrNull { !it.snapshot.selected }?.actionNode

            OptionTarget.NONE -> null
        }
    }

    private fun logOptionSelection(
        mode: DialogMode,
        optionTarget: OptionTarget,
    ) {
        val modeLabel = when (mode) {
            DialogMode.INLINE_OPTIONS -> "INLINE"
            DialogMode.DROPDOWN -> "DROPDOWN"
            DialogMode.SPINNER -> "SPINNER"
            DialogMode.SINGLE_STEP -> "SINGLE_STEP"
            DialogMode.UNKNOWN -> "UNKNOWN"
        }
        val targetLabel = when (optionTarget) {
            OptionTarget.EXACT_ENTIRE_SCREEN -> "exact-entire-screen"
            OptionTarget.SECOND_OPTION -> "second-option-fallback"
            OptionTarget.NON_SELECTED_OPTION -> "structural-two-option-fallback"
            OptionTarget.NONE -> "none"
        }
        Log.d(TAG, "Selecting option mode=$modeLabel target=$targetLabel")
    }

    private fun trySelectDropdownOption(
        optionListContext: OptionListContext,
        optionTarget: OptionTarget,
        now: Long,
    ): AutoAcceptResult {
        val optionNode = findTargetOptionNode(optionListContext.nodes, optionTarget)
        recycleInlineOptionNodes(optionListContext.nodes, except = optionNode)
        if (optionNode == null) {
            return markFailure("Dropdown options visible but no safe target found")
        }

        logOptionSelection(DialogMode.DROPDOWN, optionTarget)
        val result = performOptionAction(optionNode)
        optionNode.recycle()
        return if (result) {
            assumeEntireScreenSelected(now)
            pendingDropdownSettleRequestedAtMs = now
            Log.d(TAG, "Started pending dropdown settle")
            AutoAcceptResult.ActionPerformed
        } else {
            markFailure("Failed to select dropdown option")
        }
    }

    private fun clearInlineSelectionIfWindowChanged(windowId: Int) {
        if (pendingInlineSelectionWindowId != null && pendingInlineSelectionWindowId != windowId) {
            clearPendingInlineSelection()
        }
    }

    private fun recyclePendingBranchNodes(
        positiveButton: AccessibilityNodeInfo?,
        spinner: AccessibilityNodeInfo?,
        inlineOptionNodes: List<InlineOptionNode>,
    ) {
        positiveButton?.recycle()
        spinner?.recycle()
        recycleInlineOptionNodes(inlineOptionNodes)
    }

    private fun clearTransientState() {
        clearPendingSpinnerTransaction()
        clearPendingDropdownSettle()
        clearPendingInlineSelection()
        assumedEntireScreenUntilMs = 0L
    }

    private fun clearPendingSpinnerTransaction() {
        pendingSpinnerOriginWindowId = null
        pendingSpinnerRequestedAtMs = 0L
        pendingSpinnerStrategy = PendingSpinnerStrategy.GENERIC
    }

    private fun clearPendingInlineSelection() {
        pendingInlineSelectionWindowId = null
        pendingInlineSelectionRequestedAtMs = 0L
        pendingInlineSelectionAttempts = 0
    }

    private fun clearPendingDropdownSettle() {
        pendingDropdownSettleRequestedAtMs = 0L
    }

    private fun clearExpiredBlock(now: Long) {
        if (blockedUntilMs != 0L && now >= blockedUntilMs) {
            blockedUntilMs = 0L
        }
    }

    private fun hasPendingSpinnerTransaction(): Boolean {
        return pendingSpinnerRequestedAtMs != 0L
    }

    private fun hasPendingDropdownSettle(): Boolean {
        return pendingDropdownSettleRequestedAtMs != 0L
    }

    private fun evaluatePendingSpinnerStep(
        hasPendingTransaction: Boolean,
        optionListMode: DialogMode,
        elapsedMs: Long,
    ): PendingSpinnerStep {
        return when {
            !hasPendingTransaction -> PendingSpinnerStep.NO_TRANSACTION
            optionListMode == DialogMode.DROPDOWN -> PendingSpinnerStep.CONSUME_DROPDOWN
            elapsedMs >= PENDING_SPINNER_TIMEOUT_MS -> PendingSpinnerStep.EXPIRE
            else -> PendingSpinnerStep.WAIT
        }
    }

    private fun evaluatePendingDropdownSettleStep(
        hasPendingDropdownSettle: Boolean,
        optionListMode: DialogMode,
        elapsedMs: Long,
    ): PendingDropdownSettleStep {
        return when {
            !hasPendingDropdownSettle -> PendingDropdownSettleStep.NO_PENDING
            optionListMode == DialogMode.DROPDOWN &&
                    elapsedMs < PENDING_DROPDOWN_SETTLE_TIMEOUT_MS -> PendingDropdownSettleStep.WAIT

            optionListMode == DialogMode.DROPDOWN -> PendingDropdownSettleStep.EXPIRE
            else -> PendingDropdownSettleStep.CONTINUE
        }
    }

    private fun markFailure(reason: String): AutoAcceptResult {
        blockedUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        clearTransientState()
        Log.w(TAG, "Auto-accept failed: $reason")
        return AutoAcceptResult.Failed(reason)
    }

    private fun isBlocked(now: Long): Boolean {
        return blockedUntilMs != 0L && now < blockedUntilMs
    }

    private fun logDecision(decision: DialogDecision, now: Long) {
        val key =
            "${decision.mode}|${decision.selection}|${decision.action}|${decision.optionTarget}|${decision.reason}"
        if (key == lastDecisionLogKey && now - lastDecisionLogAtMs < DECISION_LOG_COOLDOWN_MS) {
            return
        }
        lastDecisionLogKey = key
        lastDecisionLogAtMs = now
        Log.d(
            TAG,
            "Decision mode=${decision.mode} selection=${decision.selection} action=${decision.action} target=${decision.optionTarget} reason=${decision.reason}",
        )
    }

    private fun hasViewId(
        root: AccessibilityNodeInfo,
        viewId: String,
    ): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    private fun findNodeByViewId(
        root: AccessibilityNodeInfo,
        viewId: String,
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isEmpty()) return null
        val node = nodes.first()
        nodes.drop(1).forEach { it.recycle() }
        return node
    }

    private fun findFirstListNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        if (className.contains("ListView") || className.contains("RecyclerView")) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstListNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun assumeEntireScreenSelected(now: Long) {
        assumedEntireScreenUntilMs = now + ASSUMED_SELECTION_TTL_MS
    }

    private fun isAssumedEntireScreen(now: Long): Boolean {
        if (assumedEntireScreenUntilMs == 0L) return false
        if (now <= assumedEntireScreenUntilMs) return true
        assumedEntireScreenUntilMs = 0L
        return false
    }

    private fun performOptionAction(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (node.isCheckable || hasAction(node, AccessibilityNodeInfo.ACTION_SELECT)) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
                return true
            }
        }
        val child = findClickableOrCheckableChild(node, 2)
        if (child != null) {
            val result = child.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    child.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            child.recycle()
            if (result) return true
        }
        val parent = findClickableParent(node)
        if (parent != null) {
            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    parent.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            parent.recycle()
            if (result) return true
        }
        return false
    }

    private fun hasAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
    }

    private fun findClickableOrCheckableChild(
        node: AccessibilityNodeInfo,
        maxDepth: Int,
    ): AccessibilityNodeInfo? {
        if (maxDepth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable || child.isCheckable || hasAction(
                    child,
                    AccessibilityNodeInfo.ACTION_SELECT
                )
            ) {
                return child
            }
            val result = findClickableOrCheckableChild(child, maxDepth - 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isInSpinner(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            val className = current.className?.toString().orEmpty()
            val parent = current.parent
            current.recycle()
            if (className.contains("Spinner")) {
                parent?.recycle()
                return true
            }
            current = parent
            depth += 1
        }
        return false
    }

    private fun isEntireScreenLabel(label: String): Boolean {
        return ENTIRE_SCREEN_TEXTS.any { label.contains(it, ignoreCase = true) }
    }

    private fun spinnerLooksLikeEntireScreen(input: DecisionInput): Boolean {
        return ENTIRE_SCREEN_TEXTS.any {
            input.spinnerText.contains(it, ignoreCase = true)
        }
    }

    private fun isAospSpinnerDialog(
        dialogSignals: DialogSignals,
        optionListContext: OptionListContext,
        hasSpinnerWidget: Boolean,
        hasPositiveButton: Boolean,
    ): Boolean {
        return dialogSignals.hasDialogRoot &&
                hasSpinnerWidget &&
                hasPositiveButton &&
                optionListContext.mode != DialogMode.INLINE_OPTIONS
    }

    private fun isPositiveSelectionDescription(description: String?): Boolean {
        val normalized = description?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        if (normalized.contains("not selected") || normalized.contains("not checked")) return false
        return normalized.contains("selected") || normalized.contains("checked")
    }

    private fun recycleInlineOptionNodes(
        optionNodes: List<InlineOptionNode>,
        except: AccessibilityNodeInfo? = null,
    ) {
        optionNodes.forEach { optionNode ->
            if (optionNode.actionNode != null && optionNode.actionNode != except) {
                optionNode.actionNode.recycle()
            }
        }
    }

    private fun findButtonByTexts(
        node: AccessibilityNodeInfo,
        texts: List<String>,
    ): AccessibilityNodeInfo? {
        for (text in texts) {
            val matches = node.findAccessibilityNodeInfosByText(text)
            for (match in matches) {
                val matchText = match.text?.toString().orEmpty()
                val isButton = match.className?.toString()?.contains("Button") == true
                val isClickable = match.isClickable

                if (
                    (matchText.equals(text, ignoreCase = true) || matchText.contains(
                        text,
                        ignoreCase = true
                    )) &&
                    (isButton || isClickable)
                ) {
                    matches.filter { it != match }.forEach { it.recycle() }
                    return match
                }
            }
            matches.forEach { it.recycle() }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent ?: return null
        var depth = 0

        while (depth < 5) {
            if (parent.isClickable) return parent
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent ?: return null
            depth += 1
        }
        parent.recycle()
        return null
    }

    private fun findSpinner(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("Spinner") == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSpinner(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun getSpinnerSelectedText(spinner: AccessibilityNodeInfo): String {
        spinner.text?.toString()?.let {
            if (it.isNotEmpty()) return it
        }
        for (i in 0 until spinner.childCount) {
            val child = spinner.getChild(i) ?: continue
            val text = child.text?.toString().orEmpty()
            child.recycle()
            if (text.isNotEmpty()) return text
        }
        return ""
    }
}
