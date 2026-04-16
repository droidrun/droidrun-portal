package com.mobilerun.portal.triggers

object TriggerTemplateRenderer {
    fun render(promptTemplate: String, signal: TriggerSignal): String {
        var rendered = promptTemplate
        signal.payload.forEach { (key, value) ->
            rendered = rendered.replace("{{trigger.$key}}", value)
        }
        rendered = rendered
            .replace("{{trigger.type}}", signal.source.name)
            .replace("{{trigger.timestamp}}", signal.timestampMs.toString())
        return rendered.trim()
    }
}
