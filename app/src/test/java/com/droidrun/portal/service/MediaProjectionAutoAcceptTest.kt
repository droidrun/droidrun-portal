package com.droidrun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProjectionAutoAcceptTest {

    @Test
    fun decideAction_honorInlineEntireScreenSelected_clicksPositive() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("A single app", selected = false),
                    option("Entire screen", selected = true),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasShareModeOptionsViewId = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.ENTIRE_SCREEN_SELECTED, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.CLICK_POSITIVE_BUTTON, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.NONE, decision.optionTarget)
    }

    @Test
    fun decideAction_honorInlineSingleAppSelected_selectsExactEntireScreen() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("A single app", selected = true),
                    option("Entire screen", selected = false),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasShareModeOptionsViewId = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.SINGLE_APP_SELECTED, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.SELECT_INLINE_ENTIRE_SCREEN, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.EXACT_ENTIRE_SCREEN, decision.optionTarget)
    }

    @Test
    fun decideAction_inlineExactTargetWithUnknownSelection_doesNotClickPositive() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("A single app", selected = false),
                    option("Entire screen", selected = false),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasShareModeOptionsViewId = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.UNKNOWN, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.SELECT_INLINE_ENTIRE_SCREEN, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.EXACT_ENTIRE_SCREEN, decision.optionTarget)
    }

    @Test
    fun decideAction_samsungStandaloneDropdown_selectsDropdownOption() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.DROPDOWN,
                options = listOf(
                    option("Share one app", selected = true),
                    option("Share entire screen", selected = false),
                ),
                listWindowLooksStandalone = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.DROPDOWN, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.SINGLE_APP_SELECTED, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.SELECT_DROPDOWN_OPTION, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.EXACT_ENTIRE_SCREEN, decision.optionTarget)
    }

    @Test
    fun decideAction_localizedInlineSecondRowSelected_clicksPositive() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("Только в отдельном приложении", selected = false),
                    option("На весь экран", selected = true),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasShareModeOptionsViewId = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.UNKNOWN, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.CLICK_POSITIVE_BUTTON, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.NONE, decision.optionTarget)
    }

    @Test
    fun decideAction_localizedInlineFirstRowSelected_selectsSecondRow() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("Только в отдельном приложении", selected = true),
                    option("На весь экран", selected = false),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasShareModeOptionsViewId = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.UNKNOWN, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.SELECT_INLINE_ENTIRE_SCREEN, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.SECOND_OPTION, decision.optionTarget)
    }

    @Test
    fun decideAction_localizedInlineWithoutSingleSelection_waits() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("Только в отдельном приложении", selected = false),
                    option("На весь экран", selected = false),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasShareModeOptionsViewId = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.UNKNOWN, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.WAIT, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.NONE, decision.optionTarget)
    }

    @Test
    fun decideAction_localizedDropdownTwoOptions_usesStructuralFallback() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.DROPDOWN,
                options = listOf(
                    option("Partager une application", selected = true),
                    option("Partager tout l ecran", selected = false),
                ),
                listWindowLooksStandalone = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.DROPDOWN, decision.mode)
        assertEquals(MediaProjectionAutoAccept.InlineSelection.UNKNOWN, decision.selection)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.SELECT_DROPDOWN_OPTION, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.NON_SELECTED_OPTION, decision.optionTarget)
    }

    @Test
    fun decideAction_pixelLocalizedSpinner_opensSpinner() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.UNKNOWN,
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasSpinnerWidget = true,
                spinnerText = "Показать весь экран",
                isAospSpinnerDialog = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.SPINNER, decision.mode)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.OPEN_SPINNER, decision.action)
        assertEquals("aosp localized spinner selection requires dropdown", decision.reason)
    }

    @Test
    fun resolveOptionTarget_pixelLocalizedDropdownUsesSecondOption() {
        val target = MediaProjectionAutoAccept.resolveOptionTargetForTest(
            optionSnapshots = listOf(
                option("Показать одно приложение", selected = true),
                option("Показать весь экран", selected = false),
            ),
            pendingStrategy = MediaProjectionAutoAccept.PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION,
        )

        assertEquals(MediaProjectionAutoAccept.OptionTarget.SECOND_OPTION, target)
    }

    @Test
    fun resolveOptionTarget_pixelLocalizedDropdownSecondRowStillUsesSecondOption() {
        val target = MediaProjectionAutoAccept.resolveOptionTargetForTest(
            optionSnapshots = listOf(
                option("Показать одно приложение", selected = false),
                option("Показать весь экран", selected = true),
            ),
            pendingStrategy = MediaProjectionAutoAccept.PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION,
        )

        assertEquals(MediaProjectionAutoAccept.OptionTarget.SECOND_OPTION, target)
    }

    @Test
    fun resolveOptionTarget_pixelLocalizedDropdownDisabledRowDoesNotUseSecondOption() {
        val target = MediaProjectionAutoAccept.resolveOptionTargetForTest(
            optionSnapshots = listOf(
                option("Показать одно приложение", selected = true),
                option("Показать весь экран", selected = false, enabled = false),
            ),
            pendingStrategy = MediaProjectionAutoAccept.PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION,
        )

        assertEquals(MediaProjectionAutoAccept.OptionTarget.NON_SELECTED_OPTION, target)
    }

    @Test
    fun resolveOptionTarget_pixelLocalizedDropdownMoreThanTwoDoesNotUseSecondOption() {
        val target = MediaProjectionAutoAccept.resolveOptionTargetForTest(
            optionSnapshots = listOf(
                option("Опция 1", selected = true),
                option("Опция 2", selected = false),
                option("Опция 3", selected = false),
            ),
            pendingStrategy = MediaProjectionAutoAccept.PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION,
        )

        assertEquals(null, target)
    }

    @Test
    fun pendingSpinnerStep_crossWindowDropdownConsumesTransaction() {
        val step = MediaProjectionAutoAccept.inspectPendingSpinnerStepForTest(
            hasPendingTransaction = true,
            optionListMode = MediaProjectionAutoAccept.DialogMode.DROPDOWN,
            elapsedMs = 100L,
        )

        assertEquals(MediaProjectionAutoAccept.PendingSpinnerStep.CONSUME_DROPDOWN, step)
    }

    @Test
    fun pendingSpinnerStep_timeoutExpiresTransaction() {
        val step = MediaProjectionAutoAccept.inspectPendingSpinnerStepForTest(
            hasPendingTransaction = true,
            optionListMode = MediaProjectionAutoAccept.DialogMode.UNKNOWN,
            elapsedMs = 2_000L,
        )

        assertEquals(MediaProjectionAutoAccept.PendingSpinnerStep.EXPIRE, step)
    }

    @Test
    fun pendingSpinnerTransaction_clearTransientStateClearsPendingTransaction() {
        MediaProjectionAutoAccept.clearTransientStateForTest()
        MediaProjectionAutoAccept.startPendingSpinnerTransactionForTest(
            requestedAtMs = 1_000L,
            strategy = MediaProjectionAutoAccept.PendingSpinnerStrategy.AOSP_ORDERED_TWO_OPTION,
            originWindowId = 10,
        )

        assertEquals(true, MediaProjectionAutoAccept.hasPendingSpinnerTransactionForTest())

        MediaProjectionAutoAccept.clearTransientStateForTest()

        assertEquals(false, MediaProjectionAutoAccept.hasPendingSpinnerTransactionForTest())
    }

    @Test
    fun pendingDropdownSettleStep_dropdownStillVisible_waits() {
        val step = MediaProjectionAutoAccept.inspectPendingDropdownSettleStepForTest(
            hasPendingDropdownSettle = true,
            optionListMode = MediaProjectionAutoAccept.DialogMode.DROPDOWN,
            elapsedMs = 100L,
        )

        assertEquals(MediaProjectionAutoAccept.PendingDropdownSettleStep.WAIT, step)
    }

    @Test
    fun pendingDropdownSettleStep_dropdownGone_continues() {
        val step = MediaProjectionAutoAccept.inspectPendingDropdownSettleStepForTest(
            hasPendingDropdownSettle = true,
            optionListMode = MediaProjectionAutoAccept.DialogMode.UNKNOWN,
            elapsedMs = 100L,
        )

        assertEquals(MediaProjectionAutoAccept.PendingDropdownSettleStep.CONTINUE, step)
    }

    @Test
    fun pendingDropdownSettleStep_timeoutExpires() {
        val step = MediaProjectionAutoAccept.inspectPendingDropdownSettleStepForTest(
            hasPendingDropdownSettle = true,
            optionListMode = MediaProjectionAutoAccept.DialogMode.DROPDOWN,
            elapsedMs = 1_500L,
        )

        assertEquals(MediaProjectionAutoAccept.PendingDropdownSettleStep.EXPIRE, step)
    }

    @Test
    fun pendingDropdownSettle_clearTransientStateClearsPendingState() {
        MediaProjectionAutoAccept.clearTransientStateForTest()
        MediaProjectionAutoAccept.startPendingDropdownSettleForTest(requestedAtMs = 1_000L)

        assertEquals(true, MediaProjectionAutoAccept.hasPendingDropdownSettleForTest())

        MediaProjectionAutoAccept.clearTransientStateForTest()

        assertEquals(false, MediaProjectionAutoAccept.hasPendingDropdownSettleForTest())
    }

    @Test
    fun allowsContinuationListFallback_withoutPendingContinuation_rejectsListOnlyFallback() {
        val result = MediaProjectionAutoAccept.allowsContinuationListFallbackForTest(
            hasPendingSpinnerTransaction = false,
            hasPendingDropdownSettle = false,
        )

        assertEquals(false, result)
    }

    @Test
    fun allowsContinuationListFallback_withPendingSpinner_allowsListOnlyFallback() {
        val result = MediaProjectionAutoAccept.allowsContinuationListFallbackForTest(
            hasPendingSpinnerTransaction = true,
            hasPendingDropdownSettle = false,
        )

        assertEquals(true, result)
    }

    @Test
    fun allowsContinuationListFallback_withPendingDropdownSettle_allowsListOnlyFallback() {
        val result = MediaProjectionAutoAccept.allowsContinuationListFallbackForTest(
            hasPendingSpinnerTransaction = false,
            hasPendingDropdownSettle = true,
        )

        assertEquals(true, result)
    }

    @Test
    fun decideAction_pixelEnglishSpinnerAlreadyEntireScreenClicksPositive() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.UNKNOWN,
                hasPositiveButton = true,
                hasDialogRoot = true,
                hasSpinnerWidget = true,
                spinnerText = "Share entire screen",
                isAospSpinnerDialog = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.SINGLE_STEP, decision.mode)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.CLICK_POSITIVE_BUTTON, decision.action)
    }

    @Test
    fun decideAction_ambiguousMultiOptionList_waits() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS,
                options = listOf(
                    option("Option one", selected = true),
                    option("Option two", selected = false),
                    option("Option three", selected = false),
                ),
                hasPositiveButton = true,
                hasDialogRoot = true,
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.INLINE_OPTIONS, decision.mode)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.WAIT, decision.action)
        assertEquals(MediaProjectionAutoAccept.OptionTarget.NONE, decision.optionTarget)
    }

    @Test
    fun decideAction_spinnerWithoutEntireScreenSelection_opensSpinner() {
        val decision = MediaProjectionAutoAccept.decideAction(
            decisionInput(
                optionListMode = MediaProjectionAutoAccept.DialogMode.UNKNOWN,
                hasPositiveButton = true,
                hasSpinnerWidget = true,
                spinnerText = "A single app",
            ),
        )

        assertEquals(MediaProjectionAutoAccept.DialogMode.SPINNER, decision.mode)
        assertEquals(MediaProjectionAutoAccept.PlannedAction.OPEN_SPINNER, decision.action)
    }

    @Test
    fun inferInlineSelection_detectsEntireScreenSelected() {
        val selection = MediaProjectionAutoAccept.inferInlineSelection(
            listOf(
                option("A single app", selected = false),
                option("Entire screen", selected = true),
            ),
        )

        assertEquals(MediaProjectionAutoAccept.InlineSelection.ENTIRE_SCREEN_SELECTED, selection)
    }

    @Test
    fun inferInlineSelection_detectsSingleAppSelected() {
        val selection = MediaProjectionAutoAccept.inferInlineSelection(
            listOf(
                option("A single app", selected = true),
                option("Entire screen", selected = false),
            ),
        )

        assertEquals(MediaProjectionAutoAccept.InlineSelection.SINGLE_APP_SELECTED, selection)
    }

    @Test
    fun inferInlineSelection_returnsUnknownWhenNoExactEntireScreenOptionExists() {
        val selection = MediaProjectionAutoAccept.inferInlineSelection(
            listOf(
                option("Partager une application", selected = true),
                option("Partager tout l ecran", selected = false),
            ),
        )

        assertEquals(MediaProjectionAutoAccept.InlineSelection.UNKNOWN, selection)
    }

    private fun option(
        label: String,
        selected: Boolean,
        enabled: Boolean = true,
        clickable: Boolean = true,
    ) = MediaProjectionAutoAccept.InlineOptionSnapshot(
        label = label,
        selected = selected,
        enabled = enabled,
        clickable = clickable,
    )

    private fun decisionInput(
        optionListMode: MediaProjectionAutoAccept.DialogMode,
        options: List<MediaProjectionAutoAccept.InlineOptionSnapshot> = emptyList(),
        hasPositiveButton: Boolean = false,
        hasDialogRoot: Boolean = false,
        hasShareModeOptionsViewId: Boolean = false,
        listWindowLooksStandalone: Boolean = false,
        hasSpinnerWidget: Boolean = false,
        spinnerText: String = "",
        assumeEntireScreen: Boolean = false,
        isAospSpinnerDialog: Boolean = false,
    ): MediaProjectionAutoAccept.DecisionInput {
        val selectedOptionRowIndex = options.mapIndexedNotNull { index, option ->
            (index + 1).takeIf { option.selected }
        }.singleOrNull()

        return MediaProjectionAutoAccept.DecisionInput(
            optionListMode = optionListMode,
            inlineOptions = options,
            optionCount = options.size,
            selectedOptionCount = options.count { it.selected },
            hasPositiveButton = hasPositiveButton,
            hasDialogRoot = hasDialogRoot,
            hasShareModeOptionsViewId = hasShareModeOptionsViewId,
            listWindowLooksStandalone = listWindowLooksStandalone,
            hasSpinnerWidget = hasSpinnerWidget,
            spinnerText = spinnerText,
            assumeEntireScreen = assumeEntireScreen,
            selectedOptionRowIndex = selectedOptionRowIndex,
            isAospSpinnerDialog = isAospSpinnerDialog,
        )
    }
}
