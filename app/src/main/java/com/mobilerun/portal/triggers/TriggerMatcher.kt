package com.mobilerun.portal.triggers

object TriggerMatcher {
    fun matches(rule: TriggerRule, signal: TriggerSignal): Boolean {
        if (!rule.enabled || rule.source != signal.source) return false

        val payload = signal.payload
        if (!matchesPackage(rule.packageName, payload["package"], rule.stringMatchMode)) return false
        if (!matchesText(rule.titleFilter, payload["title"], rule.stringMatchMode)) return false
        if (!matchesText(rule.textFilter, payload["text"], rule.stringMatchMode)) return false
        if (!matchesText(rule.phoneNumberFilter, payload["phone_number"], rule.stringMatchMode)) return false
        if (!matchesText(rule.messageFilter, payload["message"], rule.stringMatchMode)) return false
        if (!matchesNetwork(rule.networkType, payload["network_type"])) return false
        if (!matchesThreshold(rule, payload["battery_level"])) return false

        return true
    }

    private fun matchesPackage(
        expected: String?,
        actual: String?,
        matchMode: TriggerStringMatchMode,
    ): Boolean {
        if (expected.isNullOrBlank()) return true
        if (actual.isNullOrBlank()) return false
        return when (matchMode) {
            TriggerStringMatchMode.CONTAINS -> {
                actual.equals(expected, ignoreCase = true) ||
                    actual.contains(expected, ignoreCase = true)
            }

            TriggerStringMatchMode.REGEX -> safeRegex(expected, actual)
        }
    }

    private fun matchesText(
        expected: String?,
        actual: String?,
        matchMode: TriggerStringMatchMode,
    ): Boolean {
        if (expected.isNullOrBlank()) return true
        if (actual.isNullOrBlank()) return false
        return when (matchMode) {
            TriggerStringMatchMode.CONTAINS -> actual.contains(expected, ignoreCase = true)
            TriggerStringMatchMode.REGEX -> safeRegex(expected, actual)
        }
    }

    private fun matchesNetwork(expected: TriggerNetworkType?, actual: String?): Boolean {
        if (expected == null) return true
        return actual.equals(expected.name, ignoreCase = true)
    }

    private fun matchesThreshold(rule: TriggerRule, actual: String?): Boolean {
        val threshold = rule.thresholdValue ?: return true
        val actualInt = actual?.toIntOrNull() ?: return false
        return when (rule.thresholdComparison) {
            TriggerThresholdComparison.AT_OR_BELOW -> actualInt <= threshold
            TriggerThresholdComparison.AT_OR_ABOVE -> actualInt >= threshold
        }
    }

    private fun safeRegex(pattern: String, actual: String): Boolean {
        return try {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(actual)
        } catch (_: Exception) {
            false
        }
    }
}
