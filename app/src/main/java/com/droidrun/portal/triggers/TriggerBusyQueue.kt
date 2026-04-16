package com.droidrun.portal.triggers

import com.droidrun.portal.taskprompt.PortalTaskSettings
import java.util.UUID

/**
 * In-memory FIFO queue holding triggers that matched while the device was busy
 * and chose the QUEUE busy policy. Entries are self-contained snapshots — they
 * remain valid even if the originating rule is later deleted or edited.
 */
data class TriggerQueueEntry(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val ruleName: String,
    val source: TriggerSource,
    val prompt: String,
    val settings: PortalTaskSettings,
    val signal: TriggerSignal,
    val memoryNamespace: String?,
    val returnToPortalOnTerminal: Boolean,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
)

class TriggerBusyQueue(private val maxSize: Int = 20) {
    private val lock = Any()
    private val entries: ArrayDeque<TriggerQueueEntry> = ArrayDeque()

    fun enqueue(entry: TriggerQueueEntry): Boolean {
        synchronized(lock) {
            if (entries.size >= maxSize) return false
            entries.addLast(entry)
            return true
        }
    }

    fun popNext(): TriggerQueueEntry? {
        synchronized(lock) {
            return entries.removeFirstOrNull()
        }
    }

    fun pushFront(entry: TriggerQueueEntry) {
        synchronized(lock) {
            entries.addFirst(entry)
        }
    }

    fun removeById(entryId: String): Boolean {
        synchronized(lock) {
            return entries.removeAll { it.id == entryId }
        }
    }

    fun list(): List<TriggerQueueEntry> {
        synchronized(lock) {
            return entries.toList()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return entries.size
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
