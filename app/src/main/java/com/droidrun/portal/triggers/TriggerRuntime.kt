package com.droidrun.portal.triggers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.droidrun.portal.config.ConfigManager
import com.droidrun.portal.events.EventHub
import com.droidrun.portal.events.model.EventType
import com.droidrun.portal.events.model.PortalEvent
import com.droidrun.portal.taskprompt.PortalTaskSettings
import com.droidrun.portal.taskprompt.PortalTaskTracking
import com.droidrun.portal.taskprompt.TaskPromptNotificationManager
import org.json.JSONObject
import java.util.Locale

object TriggerRuntime {
    private const val BATTERY_LOW_THRESHOLD = 15

    private val initLock = Any()
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var repository: TriggerRepository
    private lateinit var scheduler: TriggerScheduler
    private lateinit var taskLauncher: TriggerTaskLauncher
    private lateinit var notificationBuffer: NotificationDebounceBuffer
    private val busyQueue = TriggerBusyQueue()

    private var batteryReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var taskStateReceiver: BroadcastReceiver? = null

    private var lastBatteryLevel: Int? = null
    private var lastCharging: Boolean? = null
    private var lastNetworkType: TriggerNetworkType? = null

    private val eventListener: (PortalEvent) -> Unit = { event ->
        handlePortalEvent(event)
    }

    fun initialize(context: Context) {
        synchronized(initLock) {
            appContext = context.applicationContext
            repository = TriggerRepository.getInstance(appContext)
            scheduler = TriggerScheduler(appContext)
            taskLauncher = TriggerTaskLauncher(appContext)
            normalizePersistedRules()

            if (initialized) {
                return
            }

            notificationBuffer = NotificationDebounceBuffer(
                onFlush = ::evaluateBatchedNotification,
            )
            EventHub.subscribe(eventListener)
            registerBatteryReceiver()
            registerScreenReceiver()
            registerNetworkCallback()
            registerTaskStateReceiver()
            scheduler.rescheduleAll(repository.listRules())
            initialized = true
        }
    }

    fun listRules(): List<TriggerRule> {
        if (!initialized) return emptyList()
        return repository.listRules().map(::normalizeRule)
    }

    fun listRuns(limit: Int = 50): List<TriggerRunRecord> {
        if (!initialized) return emptyList()
        return repository.listRuns(limit)
    }

    fun listQueued(): List<TriggerQueueEntry> = busyQueue.list()

    fun cancelQueued(entryId: String): Boolean = busyQueue.removeById(entryId)

    fun restoreRun(record: TriggerRunRecord) {
        initialize(appContext)
        repository.addRun(record)
    }

    fun deleteRun(runId: String) {
        initialize(appContext)
        repository.deleteRun(runId)
    }

    fun clearRuns() {
        initialize(appContext)
        repository.clearRuns()
    }

    fun saveRule(rule: TriggerRule) {
        initialize(appContext)
        repository.saveRule(normalizeRule(rule))
        onRulesChanged()
    }

    fun deleteRule(ruleId: String) {
        initialize(appContext)
        repository.deleteRule(ruleId)
        scheduler.cancel(ruleId)
        onRulesChanged()
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        initialize(appContext)
        repository.getRule(ruleId)?.let { rule ->
            repository.saveRule(normalizeRule(rule.copy(enabled = enabled)))
        }
        onRulesChanged()
    }

    fun onRulesChanged() {
        initialize(appContext)
        normalizePersistedRules()
        scheduler.rescheduleAll(repository.listRules())
    }

    fun handleScheduledRule(ruleId: String) {
        initialize(appContext)
        val rule = repository.getRule(ruleId) ?: return
        if (!rule.enabled) return

        val signal = TriggerSignal(
            source = rule.source,
            payload = mapOf(
                "rule_id" to rule.id,
                "rule_name" to rule.name,
                "schedule_kind" to rule.source.name,
            ),
        )
        evaluateRule(rule, signal, isTestRun = false)
    }

    fun launchTest(ruleId: String) {
        initialize(appContext)
        val rule = repository.getRule(ruleId) ?: return
        val missingPermissions = missingPermissions(rule)
        if (missingPermissions.isNotEmpty()) {
            logRun(
                rule = rule,
                disposition = TriggerRunDisposition.PERMISSION_MISSING,
                summary = "Missing permissions: ${missingPermissions.joinToString()}",
                signal = buildTestSignal(rule),
            )
            return
        }
        evaluateRule(rule, buildTestSignal(rule), isTestRun = true)
    }

    private fun handlePortalEvent(event: PortalEvent) {
        val signal = when (event.type) {
            EventType.NOTIFICATION_POSTED -> portalEventToSignal(TriggerSource.NOTIFICATION_POSTED, event)
            EventType.NOTIFICATION_REMOVED -> portalEventToSignal(TriggerSource.NOTIFICATION_REMOVED, event)
            EventType.APP_ENTERED -> portalEventToSignal(TriggerSource.APP_ENTERED, event)
            EventType.APP_EXITED -> portalEventToSignal(TriggerSource.APP_EXITED, event)
            EventType.BATTERY_LOW -> portalEventToSignal(TriggerSource.BATTERY_LOW, event)
            EventType.BATTERY_OKAY -> portalEventToSignal(TriggerSource.BATTERY_OKAY, event)
            EventType.BATTERY_LEVEL_CHANGED -> portalEventToSignal(TriggerSource.BATTERY_LEVEL_CHANGED, event)
            EventType.POWER_CONNECTED -> portalEventToSignal(TriggerSource.POWER_CONNECTED, event)
            EventType.POWER_DISCONNECTED -> portalEventToSignal(TriggerSource.POWER_DISCONNECTED, event)
            EventType.USER_PRESENT -> portalEventToSignal(TriggerSource.USER_PRESENT, event)
            EventType.NETWORK_CONNECTED -> portalEventToSignal(TriggerSource.NETWORK_CONNECTED, event)
            EventType.NETWORK_TYPE_CHANGED -> portalEventToSignal(TriggerSource.NETWORK_TYPE_CHANGED, event)
            EventType.SMS_RECEIVED -> portalEventToSignal(TriggerSource.SMS_RECEIVED, event)
            else -> null
        } ?: return

        val nowMs = System.currentTimeMillis()
        repository.listRules()
            .asSequence()
            .filter { it.enabled && it.source == signal.source }
            .filter { it.hasLaunchLimitRemaining() }
            .filterNot { isCoolingDown(it, nowMs) }
            .filter { TriggerMatcher.matches(it, signal) }
            .forEach { rule ->
                if (isNotificationSource(signal.source)) {
                    logRun(
                        rule = rule,
                        disposition = TriggerRunDisposition.DEBOUNCED,
                        summary = "Buffered message from ${signal.payload["title"] ?: "Unknown"}",
                        signal = signal,
                    )
                    notificationBuffer.add(rule, signal)
                } else {
                    evaluateRule(rule, signal, isTestRun = false)
                }
            }
    }

    private fun isNotificationSource(source: TriggerSource): Boolean =
        source == TriggerSource.NOTIFICATION_POSTED

    private fun evaluateRule(rule: TriggerRule, signal: TriggerSignal, isTestRun: Boolean) {
        val nowMs = System.currentTimeMillis()
        repository.updateRuleTimestamps(rule.id, matchedAtMs = nowMs, launchedAtMs = null)
        logRun(
            rule = rule,
            disposition = TriggerRunDisposition.MATCHED,
            summary = "Matched ${rule.source.name.lowercase().replace('_', ' ')}",
            signal = signal,
        )

        val renderedPrompt = TriggerTemplateRenderer.render(rule.promptTemplate, signal)
        val taskSettings = rule.taskSettingsOverride ?: ConfigManager.getInstance(appContext).taskPromptSettings

        val activeTask = ConfigManager.getInstance(appContext).activePortalTask
        val deviceBusy = activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)
        val shouldQueue = !isTestRun && deviceBusy && rule.busyPolicy == TriggerBusyPolicy.QUEUE

        if (shouldQueue) {
            val entry = TriggerQueueEntry(
                ruleId = rule.id,
                ruleName = rule.name,
                source = rule.source,
                prompt = renderedPrompt,
                settings = taskSettings,
                signal = signal,
                memoryNamespace = null,
                returnToPortalOnTerminal = rule.returnToPortal,
            )
            val enqueued = busyQueue.enqueue(entry)
            if (enqueued) {
                logRun(
                    rule = rule,
                    disposition = TriggerRunDisposition.BUFFERED,
                    summary = "Queued because another portal task is active",
                    signal = signal,
                )
            } else {
                logRun(
                    rule = rule,
                    disposition = TriggerRunDisposition.LAUNCH_FAILED,
                    summary = "Queue is full; trigger dropped",
                    signal = signal,
                )
            }
            finalizeTimeRuleIfNeeded(rule, isTestRun)
            return
        }

        taskLauncher.launchPrompt(
            prompt = renderedPrompt,
            settings = taskSettings,
            triggerRuleId = rule.id,
            returnToPortalOnTerminal = rule.returnToPortal,
            skipBusyCheck = false,
        ) { result ->
            when (result) {
                is TriggerTaskLauncher.Result.Success -> {
                    val updatedRule = handleLaunchSuccess(rule, nowMs, isTestRun)
                    logRun(
                        rule = updatedRule,
                        disposition = if (isTestRun) {
                            TriggerRunDisposition.TEST_LAUNCHED
                        } else {
                            TriggerRunDisposition.LAUNCHED
                        },
                        summary = "Launched portal task ${result.record.taskId}",
                        signal = signal,
                    )
                    finalizeTimeRuleIfNeeded(updatedRule, isTestRun)
                }

                TriggerTaskLauncher.Result.Busy -> {
                    logRun(
                        rule = rule,
                        disposition = TriggerRunDisposition.SKIPPED_BUSY,
                        summary = "Skipped because another portal task is active",
                        signal = signal,
                    )
                    finalizeTimeRuleIfNeeded(rule, isTestRun)
                }

                is TriggerTaskLauncher.Result.Error -> {
                    logRun(
                        rule = rule,
                        disposition = TriggerRunDisposition.LAUNCH_FAILED,
                        summary = result.message,
                        signal = signal,
                    )
                    finalizeTimeRuleIfNeeded(rule, isTestRun)
                }
            }
        }
    }

    private fun evaluateBatchedNotification(
        rule: TriggerRule,
        senderName: String,
        packageName: String,
        messages: List<BufferedMessage>,
    ) {
        val currentRule = repository.getRule(rule.id)
        if (currentRule == null || !currentRule.enabled) return

        val nowMs = System.currentTimeMillis()
        repository.updateRuleTimestamps(currentRule.id, matchedAtMs = nowMs, launchedAtMs = null)

        val messageBlock = if (messages.size == 1) {
            "New message from $senderName: ${messages[0].text}"
        } else {
            "New messages from $senderName (${messages.size} messages):\n" +
                messages.joinToString("\n") { "- ${it.text}" }
        }

        val combinedText = messages.joinToString("\n") { it.text }
        val batchSignal = TriggerSignal(
            source = currentRule.source,
            payload = mapOf(
                "package" to packageName,
                "title" to senderName,
                "text" to combinedText,
            ),
        )

        val renderedPrompt = TriggerTemplateRenderer.render(currentRule.promptTemplate, batchSignal)
        val finalPrompt = when {
            "{{trigger.text}}" in currentRule.promptTemplate -> renderedPrompt
            currentRule.includeNotificationContext -> "$renderedPrompt\n\n$messageBlock"
            else -> renderedPrompt
        }

        val memoryNamespace = sanitizeSenderToNamespace(senderName)
        val taskSettings = currentRule.taskSettingsOverride
            ?: ConfigManager.getInstance(appContext).taskPromptSettings

        logRun(
            rule = currentRule,
            disposition = TriggerRunDisposition.MATCHED,
            summary = "Matched ${messages.size} message(s) from $senderName",
            signal = batchSignal,
        )

        val activeTask = ConfigManager.getInstance(appContext).activePortalTask
        val deviceBusy = activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)
        val shouldQueue = deviceBusy && currentRule.busyPolicy == TriggerBusyPolicy.QUEUE

        if (shouldQueue) {
            val entry = TriggerQueueEntry(
                ruleId = currentRule.id,
                ruleName = currentRule.name,
                source = currentRule.source,
                prompt = finalPrompt,
                settings = taskSettings,
                signal = batchSignal,
                memoryNamespace = memoryNamespace,
                returnToPortalOnTerminal = currentRule.returnToPortal,
            )
            val enqueued = busyQueue.enqueue(entry)
            if (enqueued) {
                logRun(
                    rule = currentRule,
                    disposition = TriggerRunDisposition.BUFFERED,
                    summary = "Queued because another portal task is active",
                    signal = batchSignal,
                )
            } else {
                logRun(
                    rule = currentRule,
                    disposition = TriggerRunDisposition.LAUNCH_FAILED,
                    summary = "Queue is full; trigger dropped",
                    signal = batchSignal,
                )
            }
            return
        }

        taskLauncher.launchPrompt(
            prompt = finalPrompt,
            settings = taskSettings,
            triggerRuleId = currentRule.id,
            returnToPortalOnTerminal = currentRule.returnToPortal,
            skipBusyCheck = false,
            memoryNamespace = memoryNamespace,
        ) { result ->
            when (result) {
                is TriggerTaskLauncher.Result.Success -> {
                    val updatedRule = handleLaunchSuccess(currentRule, nowMs, isTestRun = false)
                    logRun(
                        rule = updatedRule,
                        disposition = TriggerRunDisposition.LAUNCHED,
                        summary = "Launched portal task ${result.record.taskId}",
                        signal = batchSignal,
                    )
                    finalizeTimeRuleIfNeeded(updatedRule, isTestRun = false)
                }

                TriggerTaskLauncher.Result.Busy -> {
                    logRun(
                        rule = currentRule,
                        disposition = TriggerRunDisposition.SKIPPED_BUSY,
                        summary = "Skipped because another portal task is active",
                        signal = batchSignal,
                    )
                }

                is TriggerTaskLauncher.Result.Error -> {
                    logRun(
                        rule = currentRule,
                        disposition = TriggerRunDisposition.LAUNCH_FAILED,
                        summary = result.message,
                        signal = batchSignal,
                    )
                }
            }
        }
    }

    private fun sanitizeSenderToNamespace(sender: String): String {
        return sender.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(64)
            .ifEmpty { "unknown" }
    }

    private fun handleLaunchSuccess(
        rule: TriggerRule,
        matchedAtMs: Long,
        isTestRun: Boolean,
    ): TriggerRule {
        val currentRule = repository.getRule(rule.id) ?: rule
        val launchedAtMs = System.currentTimeMillis()
        val successfulLaunchCount = if (isTestRun) {
            currentRule.successfulLaunchCount
        } else {
            currentRule.successfulLaunchCount + 1
        }
        val limitReached = !isTestRun &&
            currentRule.maxLaunchCount != null &&
            successfulLaunchCount >= currentRule.maxLaunchCount
        val justDisabled = limitReached && currentRule.enabled
        val updatedRule = currentRule.copy(
            lastMatchedAtMs = matchedAtMs,
            lastLaunchedAtMs = launchedAtMs,
            successfulLaunchCount = successfulLaunchCount,
            enabled = if (limitReached) false else currentRule.enabled,
        )
        repository.saveRule(updatedRule)
        if (justDisabled) {
            logRun(
                rule = updatedRule,
                disposition = TriggerRunDisposition.RULE_DISABLED,
                summary = "Rule disabled because the run limit was reached",
                signal = TriggerSignal(
                    source = updatedRule.source,
                    payload = mapOf(
                        "rule_id" to updatedRule.id,
                        "run_limit" to updatedRule.maxLaunchCount.toString(),
                    ),
                ),
            )
        }
        return updatedRule
    }

    private fun finalizeTimeRuleIfNeeded(rule: TriggerRule, isTestRun: Boolean) {
        if (isTestRun || !rule.isTimeRule()) return
        if (TriggerTimeSupport.shouldDisableAfterFire(rule) && rule.enabled) {
            repository.saveRule(rule.copy(enabled = false))
        }
        scheduler.rescheduleAll(repository.listRules())
    }

    private fun isCoolingDown(rule: TriggerRule, nowMs: Long): Boolean {
        if (rule.cooldownSeconds <= 0) return false
        val cutoff = rule.lastMatchedAtMs + rule.cooldownSeconds * 1000L
        return cutoff > nowMs
    }

    private fun logRun(
        rule: TriggerRule,
        disposition: TriggerRunDisposition,
        summary: String,
        signal: TriggerSignal,
    ) {
        repository.addRun(
            TriggerRunRecord(
                ruleId = rule.id,
                ruleName = rule.name,
                source = rule.source,
                disposition = disposition,
                summary = summary,
                payloadSnapshot = JSONObject().apply {
                    signal.payload.toSortedMap().forEach { (key, value) ->
                        put(key, value)
                    }
                }.toString(),
            ),
        )
    }

    private fun portalEventToSignal(source: TriggerSource, event: PortalEvent): TriggerSignal? {
        val payload = event.payload as? JSONObject ?: return null
        val flattened = mutableMapOf<String, String>()
        val iterator = payload.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            flattened[key] = payload.opt(key)?.toString().orEmpty()
        }
        return TriggerSignal(source = source, timestampMs = event.timestamp, payload = flattened)
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val percentage = ((level * 100f) / scale).toInt()
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                val previousLevel = lastBatteryLevel
                val previousCharging = lastCharging
                if (previousLevel != null && previousLevel != percentage) {
                    emitPortalEvent(
                        type = EventType.BATTERY_LEVEL_CHANGED,
                        payload = mapOf(
                            "battery_level" to percentage.toString(),
                            "is_charging" to charging.toString(),
                        ),
                    )
                    if (previousLevel > BATTERY_LOW_THRESHOLD && percentage <= BATTERY_LOW_THRESHOLD) {
                        emitPortalEvent(
                            EventType.BATTERY_LOW,
                            mapOf("battery_level" to percentage.toString()),
                        )
                    } else if (previousLevel <= BATTERY_LOW_THRESHOLD && percentage > BATTERY_LOW_THRESHOLD) {
                        emitPortalEvent(
                            EventType.BATTERY_OKAY,
                            mapOf("battery_level" to percentage.toString()),
                        )
                    }
                }

                if (previousCharging != null && previousCharging != charging) {
                    emitPortalEvent(
                        if (charging) EventType.POWER_CONNECTED else EventType.POWER_DISCONNECTED,
                        mapOf("battery_level" to percentage.toString()),
                    )
                }

                lastBatteryLevel = percentage
                lastCharging = charging
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> emitPortalEvent(EventType.USER_PRESENT)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            appContext,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerTaskStateReceiver() {
        if (taskStateReceiver != null) return
        taskStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == TaskPromptNotificationManager.ACTION_TASK_STATE_CHANGED) {
                    maybeDrainQueue()
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            taskStateReceiver,
            IntentFilter(TaskPromptNotificationManager.ACTION_TASK_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun maybeDrainQueue() {
        val activeTask = ConfigManager.getInstance(appContext).activePortalTask
        val deviceBusy = activeTask != null && PortalTaskTracking.isBlockingStatus(activeTask.lastStatus)
        if (deviceBusy) return

        val entry = busyQueue.popNext() ?: return

        taskLauncher.launchPrompt(
            prompt = entry.prompt,
            settings = entry.settings,
            triggerRuleId = entry.ruleId,
            returnToPortalOnTerminal = entry.returnToPortalOnTerminal,
            skipBusyCheck = false,
            memoryNamespace = entry.memoryNamespace,
        ) { result ->
            when (result) {
                is TriggerTaskLauncher.Result.Success -> {
                    val rule = repository.getRule(entry.ruleId)
                    if (rule != null) {
                        handleLaunchSuccess(rule, rule.lastMatchedAtMs, isTestRun = false)
                    }
                    logQueueEntry(
                        entry = entry,
                        disposition = TriggerRunDisposition.LAUNCHED,
                        summary = "Launched portal task ${result.record.taskId}",
                    )
                }

                TriggerTaskLauncher.Result.Busy -> {
                    busyQueue.pushFront(entry)
                }

                is TriggerTaskLauncher.Result.Error -> {
                    logQueueEntry(
                        entry = entry,
                        disposition = TriggerRunDisposition.LAUNCH_FAILED,
                        summary = result.message,
                    )
                }
            }
        }
    }

    private fun logQueueEntry(
        entry: TriggerQueueEntry,
        disposition: TriggerRunDisposition,
        summary: String,
    ) {
        repository.addRun(
            TriggerRunRecord(
                ruleId = entry.ruleId,
                ruleName = entry.ruleName,
                source = entry.source,
                disposition = disposition,
                summary = summary,
                payloadSnapshot = JSONObject().apply {
                    entry.signal.payload.toSortedMap().forEach { (key, value) ->
                        put(key, value)
                    }
                }.toString(),
            ),
        )
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        lastNetworkType = currentNetworkType(connectivityManager)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkStateChange(connectivityManager)
            }

            override fun onLost(network: Network) {
                handleNetworkStateChange(connectivityManager)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleNetworkStateChange(connectivityManager)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun handleNetworkStateChange(connectivityManager: ConnectivityManager) {
        val currentType = currentNetworkType(connectivityManager)
        val previousType = lastNetworkType
        if (previousType == null) {
            lastNetworkType = currentType
            return
        }

        if (previousType == TriggerNetworkType.NONE && currentType != TriggerNetworkType.NONE) {
            emitPortalEvent(
                EventType.NETWORK_CONNECTED,
                mapOf("network_type" to currentType.name),
            )
        } else if (previousType != currentType) {
            emitPortalEvent(
                EventType.NETWORK_TYPE_CHANGED,
                mapOf(
                    "network_type" to currentType.name,
                    "previous_network_type" to previousType.name,
                ),
            )
        }
        lastNetworkType = currentType
    }

    private fun currentNetworkType(connectivityManager: ConnectivityManager): TriggerNetworkType {
        val network = connectivityManager.activeNetwork ?: return TriggerNetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return TriggerNetworkType.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TriggerNetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TriggerNetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TriggerNetworkType.ETHERNET
            else -> TriggerNetworkType.OTHER
        }
    }

    private fun emitPortalEvent(type: EventType, payload: Map<String, String> = emptyMap()) {
        EventHub.emit(
            PortalEvent(
                type = type,
                payload = JSONObject(payload),
            ),
        )
    }

    private fun normalizeRule(rule: TriggerRule): TriggerRule {
        val sanitized = TriggerEditorSupport.sanitize(rule)
        val maxLaunchCount = sanitized.maxLaunchCount?.takeIf { it > 0 }
        val successfulLaunchCount = sanitized.successfulLaunchCount.coerceAtLeast(0)
        val normalized = sanitized.copy(
            maxLaunchCount = maxLaunchCount,
            successfulLaunchCount = successfulLaunchCount,
            enabled = sanitized.enabled && (maxLaunchCount == null || successfulLaunchCount < maxLaunchCount),
        )
        return if (normalized.source == TriggerSource.TIME_DELAY &&
            normalized.delayMinutes != null &&
            normalized.absoluteTimeMillis == null
        ) {
            normalized.copy(
                absoluteTimeMillis = System.currentTimeMillis() + normalized.delayMinutes * 60_000L,
            )
        } else {
            normalized
        }
    }

    private fun normalizePersistedRules() {
        val rules = repository.listRules()
        rules.forEach { rule ->
            val normalized = normalizeRule(rule)
            if (normalized != rule) {
                repository.saveRule(normalized)
            }
        }
    }

    private fun missingPermissions(rule: TriggerRule): List<String> {
        val missing = mutableListOf<String>()
        when (rule.source) {
            TriggerSource.SMS_RECEIVED -> {
                if (ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.RECEIVE_SMS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    missing += Manifest.permission.RECEIVE_SMS
                }
            }

            else -> Unit
        }
        return missing
    }

    private fun buildTestSignal(rule: TriggerRule): TriggerSignal {
        val payload = when (rule.source) {
            TriggerSource.NOTIFICATION_POSTED,
            TriggerSource.NOTIFICATION_REMOVED,
            -> mapOf(
                "package" to (rule.packageName ?: "com.whatsapp"),
                "title" to "Test Sender",
                "text" to "Test trigger notification",
            )

            TriggerSource.APP_ENTERED,
            TriggerSource.APP_EXITED,
            -> mapOf(
                "package" to (rule.packageName ?: "com.whatsapp"),
            )

            TriggerSource.BATTERY_LOW,
            TriggerSource.BATTERY_OKAY,
            TriggerSource.BATTERY_LEVEL_CHANGED,
            -> mapOf(
                "battery_level" to (rule.thresholdValue ?: 10).toString(),
                "is_charging" to "false",
            )

            TriggerSource.POWER_CONNECTED,
            TriggerSource.POWER_DISCONNECTED,
            -> mapOf("battery_level" to "52")

            TriggerSource.USER_PRESENT,
            -> mapOf("user_present" to "true")

            TriggerSource.NETWORK_CONNECTED,
            TriggerSource.NETWORK_TYPE_CHANGED,
            -> mapOf("network_type" to (rule.networkType ?: TriggerNetworkType.WIFI).name)

            TriggerSource.SMS_RECEIVED -> mapOf(
                "phone_number" to (rule.phoneNumberFilter ?: "+15550001111"),
                "message" to (rule.messageFilter ?: "Test SMS trigger"),
            )

            TriggerSource.TIME_DELAY,
            TriggerSource.TIME_ABSOLUTE,
            TriggerSource.TIME_DAILY,
            TriggerSource.TIME_WEEKLY,
            -> mapOf("schedule_kind" to rule.source.name)
        }
        return TriggerSignal(source = rule.source, payload = payload)
    }
}
