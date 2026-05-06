package com.mobilerun.portal.taskprompt

import com.mobilerun.portal.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PortalTaskTrajectoryEvent(
    val event: String,
    val data: Any?,
    val rawJson: String,
)

data class PortalTaskTrajectorySet(
    val events: List<PortalTaskTrajectoryEvent>,
) {
    val count: Int
        get() = events.size
}

data class PortalTaskTrajectoryIconAppearance(
    val iconResId: Int,
    val iconTintResId: Int,
    val backgroundColorResId: Int,
)

object PortalTaskTrajectoryUiSupport {
    private val HIDDEN_EVENTS = setOf(
        "FastAgentInputEvent",
        "ScreenshotEvent",
        "RecordUIStateEvent",
        "StartEvent",
        "StopEvent",
        "ManagerInputEvent",
        "ManagerContextEvent",
        "ManagerResponseEvent",
        "ExecutorContextEvent",
        "ExecutorResponseEvent",
    )

    private val REFRESHABLE_STATUSES = setOf(
        PortalTaskTracking.STATUS_CREATED,
        PortalTaskTracking.STATUS_RUNNING,
        PortalTaskTracking.STATUS_CANCELLING,
    )

    private val SUMMARY_PRIORITY_KEYS = listOf(
        "summary",
        "progress_summary",
        "description",
        "current_subgoal",
        "subgoal",
        "thought",
        "plan",
        "reason",
        "answer",
        "tool_calls_repr",
        "output",
        "instruction",
        "response",
        "exception",
        "error",
    )

    data class ParsedToolCall(
        val name: String,
        val parameters: Map<String, String>,
    )

    data class ParsedToolResult(
        val name: String,
        val output: String,
    )

    fun filterVisibleEvents(events: List<PortalTaskTrajectoryEvent>): List<PortalTaskTrajectoryEvent> {
        return events.filterNot { event -> HIDDEN_EVENTS.contains(event.event) }
    }

    fun shouldShowRefreshAction(
        status: String?,
        hasLoadedTrajectory: Boolean,
    ): Boolean {
        return hasLoadedTrajectory && status in REFRESHABLE_STATUSES
    }

    fun eventLabel(event: PortalTaskTrajectoryEvent): String {
        if (event.event == "ToolExecutionEvent") {
            val toolName = (event.data as? JSONObject)?.optString("tool_name").orEmpty()
            if (toolName.isNotBlank()) {
                return formatToolName(toolName)
            }
        }

        return when (event.event) {
            "CreatedEvent" -> "Created"
            "ManagerPlanEvent" -> "Planning"
            "ManagerPlanDetailsEvent" -> "Planning details"
            "ExecutorInputEvent" -> "Action starting"
            "ExecutorActionEvent" -> "Action prepared"
            "ExecutorActionResultEvent" -> "Action result"
            "ExecutorResultEvent" -> "Step result"
            "FastAgentExecuteEvent" -> "Agent executing"
            "FastAgentResponseEvent" -> "Agent reasoning"
            "FastAgentToolCallEvent" -> "Tool calls"
            "FastAgentOutputEvent" -> "Tool output"
            "FastAgentResultEvent" -> "Agent result"
            "FastAgentEndEvent" -> "Agent finished"
            "ToolExecutionEvent" -> "Tool execution"
            "FinalizeEvent" -> "Finalizing"
            "ResultEvent" -> "Final result"
            "CancelEvent" -> "Cancelled"
            "ExceptionEvent" -> "Exception"
            else -> humanizeEventName(event.event)
        }
    }

    fun previewSummary(
        event: PortalTaskTrajectoryEvent,
        maxLength: Int = 160,
    ): String? {
        val dataObject = event.data as? JSONObject
        if (dataObject != null) {
            for (key in SUMMARY_PRIORITY_KEYS) {
                val summary = when (key) {
                    "tool_calls_repr" -> dataObject.optString(key).trim().takeIf { it.isNotBlank() }
                        ?.let { formatXmlSummary(it) ?: ellipsize(it, maxLength) }

                    "output" -> dataObject.optString(key).trim().takeIf { it.isNotBlank() }
                        ?.let { formatXmlSummary(it) ?: ellipsize(it, maxLength) }

                    else -> stringValue(dataObject.opt(key))?.let { ellipsize(it, maxLength) }
                }
                if (!summary.isNullOrBlank()) {
                    return summary
                }
            }
        }

        return when (val value = event.data) {
            is String -> value.trim().takeIf { it.isNotBlank() }?.let { ellipsize(it, maxLength) }
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    fun detailText(event: PortalTaskTrajectoryEvent): String? {
        val dataObject = event.data as? JSONObject
        return when (event.event) {
            "CreatedEvent" -> buildSectionText(
                "Task ID" to stringValue(dataObject?.opt("id")),
                "Stream URL" to stringValue(dataObject?.opt("streamUrl")),
            )

            "ManagerPlanEvent", "ManagerPlanDetailsEvent" -> buildSectionText(
                "Plan" to stringValue(dataObject?.opt("plan")),
                "Current subgoal" to stringValue(dataObject?.opt("current_subgoal")),
                "Subgoal" to stringValue(dataObject?.opt("subgoal")),
                "Thought" to stringValue(dataObject?.opt("thought")),
                "Progress summary" to stringValue(dataObject?.opt("progress_summary")),
                "Answer" to stringValue(dataObject?.opt("answer")),
            )

            "ExecutorInputEvent" -> buildSectionText(
                "Current subgoal" to stringValue(dataObject?.opt("current_subgoal")),
            )

            "ExecutorActionEvent" -> buildSectionText(
                "Description" to stringValue(dataObject?.opt("description")),
                "Thought" to stringValue(dataObject?.opt("thought")),
                "Action" to prettyJsonString(dataObject?.opt("action_json")),
                "Full response" to stringValue(dataObject?.opt("full_response")),
            )

            "ExecutorActionResultEvent" -> buildSectionText(
                "Status" to booleanStatus(dataObject?.opt("success")),
                "Summary" to stringValue(dataObject?.opt("summary")),
                "Error" to stringValue(dataObject?.opt("error")),
                "Thought" to stringValue(dataObject?.opt("thought")),
                "Action" to prettyJsonString(dataObject?.opt("action")),
            )

            "ExecutorResultEvent" -> buildSectionText(
                "Status" to booleanStatus(dataObject?.opt("outcome")),
                "Summary" to stringValue(dataObject?.opt("summary")),
                "Error" to stringValue(dataObject?.opt("error")),
                "Action" to prettyJsonString(dataObject?.opt("action")),
            )

            "FastAgentExecuteEvent" -> buildSectionText(
                "Instruction" to stringValue(dataObject?.opt("instruction")),
            )

            "FastAgentResponseEvent" -> buildSectionText(
                "Thought" to stringValue(dataObject?.opt("thought")),
                "Code" to stringValue(dataObject?.opt("code")),
            )

            "FastAgentToolCallEvent" -> formatToolCallsDetail(
                stringValue(dataObject?.opt("tool_calls_repr")).orEmpty(),
            ) ?: buildSectionText(
                "Tool calls" to stringValue(dataObject?.opt("tool_calls_repr")),
            )

            "FastAgentOutputEvent" -> formatToolResultsDetail(
                stringValue(dataObject?.opt("output")).orEmpty(),
            ) ?: buildSectionText(
                "Output" to stringValue(dataObject?.opt("output")),
            )

            "FastAgentResultEvent", "FastAgentEndEvent", "FinalizeEvent" -> buildSectionText(
                "Status" to booleanStatus(dataObject?.opt("success")),
                "Reason" to stringValue(dataObject?.opt("reason")),
                "Instruction" to stringValue(dataObject?.opt("instruction")),
            )

            "ToolExecutionEvent" -> buildSectionText(
                "Tool" to stringValue(dataObject?.opt("tool_name"))?.let(::formatToolName),
                "Arguments" to prettyJsonString(dataObject?.opt("tool_args")),
                "Status" to booleanStatus(dataObject?.opt("success")),
                "Summary" to stringValue(dataObject?.opt("summary")),
            )

            "ResultEvent" -> buildSectionText(
                "Status" to booleanStatus(dataObject?.opt("success")),
                "Reason" to stringValue(dataObject?.opt("reason")),
                "Steps" to stringValue(dataObject?.opt("steps")),
                "Structured output" to prettyJsonString(dataObject?.opt("structured_output")),
            )

            "CancelEvent" -> buildSectionText(
                "Reason" to stringValue(dataObject?.opt("reason")),
            )

            "ExceptionEvent" -> buildSectionText(
                "Exception" to stringValue(dataObject?.opt("exception")),
            )

            else -> genericDetailText(event)
        }
    }

    fun iconAppearance(event: PortalTaskTrajectoryEvent): PortalTaskTrajectoryIconAppearance {
        val dataObject = event.data as? JSONObject
        return when (event.event) {
            "CancelEvent", "ExceptionEvent" -> PortalTaskTrajectoryIconAppearance(
                iconResId = R.drawable.triangle_alert,
                iconTintResId = R.color.task_prompt_error,
                backgroundColorResId = R.color.task_prompt_chip_error_bg,
            )

            "ResultEvent", "FastAgentResultEvent", "FastAgentEndEvent", "FinalizeEvent" -> {
                val isSuccess = when (dataObject?.opt("success")) {
                    is Boolean -> dataObject.optBoolean("success")
                    else -> null
                }
                if (isSuccess == false) {
                    PortalTaskTrajectoryIconAppearance(
                        iconResId = R.drawable.triangle_alert,
                        iconTintResId = R.color.task_prompt_error,
                        backgroundColorResId = R.color.task_prompt_chip_error_bg,
                    )
                } else {
                    PortalTaskTrajectoryIconAppearance(
                        iconResId = R.drawable.circle_check,
                        iconTintResId = R.color.task_prompt_accent_light,
                        backgroundColorResId = R.color.task_prompt_chip_success_bg,
                    )
                }
            }

            "ToolExecutionEvent", "FastAgentToolCallEvent", "FastAgentOutputEvent" ->
                PortalTaskTrajectoryIconAppearance(
                    iconResId = R.drawable.settings,
                    iconTintResId = R.color.task_prompt_accent_light,
                    backgroundColorResId = R.color.task_prompt_chip_info_bg,
                )

            "ExecutorActionEvent", "ExecutorActionResultEvent", "ExecutorResultEvent", "FastAgentExecuteEvent" ->
                PortalTaskTrajectoryIconAppearance(
                    iconResId = R.drawable.refresh_cw,
                    iconTintResId = R.color.task_prompt_accent_light,
                    backgroundColorResId = R.color.task_prompt_chip_info_bg,
                )

            "CreatedEvent" -> PortalTaskTrajectoryIconAppearance(
                iconResId = R.drawable.cloud,
                iconTintResId = R.color.task_prompt_accent_light,
                backgroundColorResId = R.color.task_prompt_chip_info_bg,
            )

            else -> PortalTaskTrajectoryIconAppearance(
                iconResId = R.drawable.info,
                iconTintResId = R.color.task_prompt_text_secondary,
                backgroundColorResId = R.color.task_prompt_card_surface,
            )
        }
    }

    fun formatXmlSummary(xml: String): String? {
        val parsedCalls = parseToolCalls(xml)
        if (!parsedCalls.isNullOrEmpty()) {
            return parsedCalls.joinToString(", ") { call ->
                val args = call.parameters.values.toList()
                val formattedArgs = args
                    .take(2)
                    .map { arg ->
                        if (arg.length > 20) {
                            "'${arg.take(17)}...'"
                        } else if (arg.all(Char::isDigit)) {
                            arg
                        } else {
                            "'$arg'"
                        }
                    }
                    .joinToString(", ")
                val suffix = if (args.size > 2) ", ..." else ""
                "${formatToolName(call.name)}($formattedArgs$suffix)"
            }
        }

        val parsedResults = parseToolResults(xml)
        if (!parsedResults.isNullOrEmpty()) {
            return parsedResults.joinToString(" | ") { result ->
                val shortenedOutput = if (result.output.length > 50) {
                    result.output.take(47) + "..."
                } else {
                    result.output
                }
                "${formatToolName(result.name)}: $shortenedOutput"
            }
        }

        return null
    }

    fun formatToolCallsDetail(xml: String): String? {
        val parsedCalls = parseToolCalls(xml)
        if (parsedCalls.isNullOrEmpty()) return null

        return parsedCalls.joinToString("\n\n") { call ->
            buildString {
                append("Tool\n")
                append(formatToolName(call.name))
                if (call.parameters.isNotEmpty()) {
                    append("\n\nParameters")
                    call.parameters.forEach { (key, value) ->
                        append("\n")
                        append(key)
                        append(": ")
                        append(value)
                    }
                }
            }
        }
    }

    fun formatToolResultsDetail(xml: String): String? {
        val parsedResults = parseToolResults(xml)
        if (parsedResults.isNullOrEmpty()) return null

        return parsedResults.joinToString("\n\n") { result ->
            buildString {
                append("Tool\n")
                append(formatToolName(result.name))
                append("\n\nOutput\n")
                append(result.output)
            }
        }
    }

    fun parseToolCalls(xml: String): List<ParsedToolCall>? {
        if (!xml.trim().startsWith("<")) return null

        val invokeRegex = Regex(
            pattern = """<invoke\s+name="([^"]+)">(.*?)</invoke>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val parameterRegex = Regex(
            pattern = """<parameter\s+name="([^"]+)">(.*?)</parameter>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        val calls = invokeRegex.findAll(xml).map { invokeMatch ->
            val name = invokeMatch.groupValues.getOrNull(1).orEmpty()
            val body = invokeMatch.groupValues.getOrNull(2).orEmpty()
            val parameters = linkedMapOf<String, String>()
            parameterRegex.findAll(body).forEach { parameterMatch ->
                val key = parameterMatch.groupValues.getOrNull(1).orEmpty()
                val value = decodeXmlText(parameterMatch.groupValues.getOrNull(2).orEmpty())
                if (key.isNotBlank()) {
                    parameters[key] = value
                }
            }
            ParsedToolCall(name = name, parameters = parameters)
        }.toList()

        return calls.takeIf { it.isNotEmpty() }
    }

    fun parseToolResults(xml: String): List<ParsedToolResult>? {
        if (!xml.trim().startsWith("<")) return null

        val resultRegex = Regex(
            pattern = """<result>(.*?)</result>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val nameRegex = Regex(
            pattern = """<name>(.*?)</name>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val outputRegex = Regex(
            pattern = """<output>(.*?)</output>""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        val results = resultRegex.findAll(xml).map { resultMatch ->
            val body = resultMatch.groupValues.getOrNull(1).orEmpty()
            val name = nameRegex.find(body)?.groupValues?.getOrNull(1).orEmpty()
            val output = decodeXmlText(outputRegex.find(body)?.groupValues?.getOrNull(1).orEmpty())
            ParsedToolResult(name = name, output = output)
        }.toList()

        return results.takeIf { it.isNotEmpty() }
    }

    private fun genericDetailText(event: PortalTaskTrajectoryEvent): String? {
        val dataObject = event.data as? JSONObject ?: return stringValue(event.data)
        if (dataObject.length() == 0) return null

        val keys = dataObject.keys().asSequence().toList().sorted()
        return buildSectionText(
            *keys.map { key ->
                humanizeFieldName(key) to prettyJsonString(dataObject.opt(key))
            }.toTypedArray(),
        )
    }

    private fun buildSectionText(vararg sections: Pair<String, String?>): String? {
        val visibleSections = sections.filter { (_, value) -> !value.isNullOrBlank() }
        if (visibleSections.isEmpty()) return null

        return buildString {
            visibleSections.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    append("\n\n")
                }
                append(label)
                append("\n")
                append(value!!.trim())
            }
        }
    }

    private fun stringValue(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value.trim().takeIf { it.isNotBlank() }
            is Number, is Boolean -> value.toString()
            is JSONObject -> value.takeIf { it.length() > 0 }?.toString(2)
            is JSONArray -> value.takeIf { it.length() > 0 }?.toString(2)
            else -> value.toString().trim().takeIf { it.isNotBlank() }
        }
    }

    private fun prettyJsonString(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.takeIf { it.length() > 0 }?.toString(2)
            is JSONArray -> value.takeIf { it.length() > 0 }?.toString(2)
            is String -> prettyJsonString(value) ?: value.trim().takeIf { it.isNotBlank() }
            else -> stringValue(value)
        }
    }

    private fun prettyJsonString(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun booleanStatus(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Boolean -> if (value) "Success" else "Failed"
            else -> value.toString()
        }
    }

    private fun humanizeEventName(raw: String): String {
        val noSuffix = raw.removeSuffix("Event")
        return noSuffix
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.US)
                    } else {
                        char.toString()
                    }
                }
            }
    }

    private fun humanizeFieldName(raw: String): String {
        return raw
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.US)
                } else {
                    char.toString()
                }
            }
    }

    private fun formatToolName(raw: String): String {
        return raw
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.US)
                    } else {
                        char.toString()
                    }
                }
            }
    }

    private fun ellipsize(text: String, maxLength: Int): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.length <= maxLength) return normalized
        return normalized.take(maxLength - 1).trimEnd() + "…"
    }

    private fun decodeXmlText(raw: String): String {
        return raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .trim()
    }
}
