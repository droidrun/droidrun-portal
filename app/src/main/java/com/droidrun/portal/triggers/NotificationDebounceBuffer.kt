package com.droidrun.portal.triggers

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class BufferedMessage(
    val text: String,
    val timestampMs: Long,
)

class NotificationDebounceBuffer(
    private val onFlush: (rule: TriggerRule, senderName: String, packageName: String, messages: List<BufferedMessage>) -> Unit,
    private val debounceMs: Long = 5_000L,
    private val hardCapMs: Long = 30_000L,
) {
    private class SenderBuffer(
        val senderName: String,
        val packageName: String,
        val messages: MutableList<BufferedMessage>,
        val firstMessageMs: Long,
        val rule: TriggerRule,
    )

    private val lock = Any()
    private val buffers = mutableMapOf<String, SenderBuffer>()
    private val debounceTimers = mutableMapOf<String, ScheduledFuture<*>>()
    private val hardCapTimers = mutableMapOf<String, ScheduledFuture<*>>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifDebounce").apply { isDaemon = true }
    }

    fun add(rule: TriggerRule, signal: TriggerSignal) {
        val senderName = signal.payload["title"] ?: "Unknown"
        val packageName = signal.payload["package"] ?: ""
        val text = signal.payload["text"] ?: ""
        val key = "$packageName|$senderName|${rule.id}"
        val nowMs = System.currentTimeMillis()

        synchronized(lock) {
            val existing = buffers[key]
            if (existing == null) {
                buffers[key] = SenderBuffer(
                    senderName = senderName,
                    packageName = packageName,
                    messages = mutableListOf(BufferedMessage(text, nowMs)),
                    firstMessageMs = nowMs,
                    rule = rule,
                )
                hardCapTimers[key] = executor.schedule(
                    { flush(key) },
                    hardCapMs,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                existing.messages.add(BufferedMessage(text, nowMs))
            }

            debounceTimers[key]?.cancel(false)
            debounceTimers[key] = executor.schedule(
                { flush(key) },
                debounceMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun flush(key: String) {
        val buffer: SenderBuffer
        synchronized(lock) {
            buffer = buffers.remove(key) ?: return
            debounceTimers.remove(key)?.cancel(false)
            hardCapTimers.remove(key)?.cancel(false)
        }
        onFlush(buffer.rule, buffer.senderName, buffer.packageName, buffer.messages.toList())
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
