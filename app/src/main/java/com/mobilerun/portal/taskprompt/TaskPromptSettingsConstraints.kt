package com.mobilerun.portal.taskprompt

object TaskPromptSettingsConstraints {
    const val MIN_MAX_STEPS = 1
    const val MAX_MAX_STEPS = 1000
    const val MIN_EXECUTION_TIMEOUT = 1
    const val MAX_EXECUTION_TIMEOUT = 1000
    const val MIN_TEMPERATURE = 0.0
    const val MAX_TEMPERATURE = 2.0

    fun clampMaxSteps(value: Int): Int = value.coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS)

    fun clampExecutionTimeout(value: Int): Int =
        value.coerceIn(MIN_EXECUTION_TIMEOUT, MAX_EXECUTION_TIMEOUT)

    fun clampTemperature(value: Double): Double =
        value.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)

    fun clampTemperature(value: Float): Float =
        value.coerceIn(MIN_TEMPERATURE.toFloat(), MAX_TEMPERATURE.toFloat())

    fun clamp(settings: PortalTaskSettings): PortalTaskSettings {
        return settings.copy(
            maxSteps = clampMaxSteps(settings.maxSteps),
            executionTimeout = clampExecutionTimeout(settings.executionTimeout),
            temperature = clampTemperature(settings.temperature),
        )
    }

    fun isValidMaxSteps(value: Int?): Boolean =
        value != null && value in MIN_MAX_STEPS..MAX_MAX_STEPS

    fun isValidExecutionTimeout(value: Int?): Boolean =
        value != null && value in MIN_EXECUTION_TIMEOUT..MAX_EXECUTION_TIMEOUT

    fun isValidTemperature(value: Double?): Boolean =
        value != null && value in MIN_TEMPERATURE..MAX_TEMPERATURE
}
