package com.mobilerun.portal.ui.triggers

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.mobilerun.portal.databinding.ActivityTriggerRulesBinding
import com.mobilerun.portal.databinding.ItemTriggerQueueEntryBinding
import com.mobilerun.portal.databinding.ItemTriggerRuleBinding
import com.mobilerun.portal.databinding.ItemTriggerRunRecordBinding
import com.mobilerun.portal.service.MobilerunNotificationListener
import com.mobilerun.portal.triggers.TriggerQueueEntry
import com.mobilerun.portal.triggers.TriggerRule
import com.mobilerun.portal.triggers.TriggerRunRecord
import com.mobilerun.portal.triggers.TriggerRuntime
import com.mobilerun.portal.triggers.TriggerUiSupport
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class TriggerRulesActivity : AppCompatActivity() {

    companion object {
        fun createIntent(context: android.content.Context): Intent {
            return Intent(context, TriggerRulesActivity::class.java)
        }
    }

    private lateinit var binding: ActivityTriggerRulesBinding
    private lateinit var ruleAdapter: RuleAdapter
    private lateinit var runAdapter: RunAdapter
    private lateinit var queueAdapter: QueueAdapter
    private var lastExactAlarmAvailable: Boolean? = null

    private enum class HistoryView { RUNS, QUEUED }
    private var currentView: HistoryView = HistoryView.RUNS

    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionSummary()
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TriggerRuntime.initialize(this)

        binding = ActivityTriggerRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lastExactAlarmAvailable = hasExactAlarmAccess()

        binding.topAppBar.setNavigationOnClickListener { finish() }
        setupPermissionButtons()
        setupLists()
        binding.addRuleButton.setOnClickListener {
            startActivity(TriggerRuleEditorActivity.createIntent(this))
        }
        binding.clearRunsButton.setOnClickListener {
            confirmClearRuns()
        }

        refreshPermissionSummary()
        reloadData()
    }

    override fun onResume() {
        super.onResume()
        val exactAlarmAvailable = hasExactAlarmAccess()
        if (lastExactAlarmAvailable != null && lastExactAlarmAvailable != exactAlarmAvailable) {
            TriggerRuntime.onRulesChanged()
        }
        lastExactAlarmAvailable = exactAlarmAvailable
        refreshPermissionSummary()
        reloadData()
    }

    private fun setupPermissionButtons() {
        binding.buttonNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.buttonSmsPermission.setOnClickListener {
            if (hasPermission(Manifest.permission.RECEIVE_SMS)) {
                Toast.makeText(this, "SMS permission already granted", Toast.LENGTH_SHORT).show()
            } else {
                requestSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
            }
        }

        binding.buttonContactsPermission.setOnClickListener {
            if (hasPermission(Manifest.permission.READ_CONTACTS)) {
                Toast.makeText(this, "Contacts permission already granted", Toast.LENGTH_SHORT).show()
            } else {
                requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        binding.buttonExactAlarmAccess.setOnClickListener {
            requestExactAlarmAccess()
        }
    }

    private fun setupLists() {
        ruleAdapter = RuleAdapter(
            onToggle = { rule, enabled ->
                TriggerRuntime.setRuleEnabled(rule.id, enabled)
                reloadData()
            },
            onOpen = { rule ->
                startActivity(TriggerRuleEditorActivity.createIntent(this, rule.id))
            },
        )
        binding.rulesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.rulesRecyclerView.adapter = ruleAdapter

        runAdapter = RunAdapter()
        binding.runsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.runsRecyclerView.adapter = runAdapter
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                0,
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    handleRunSwiped(viewHolder.bindingAdapterPosition)
                }
            },
        ).attachToRecyclerView(binding.runsRecyclerView)

        queueAdapter = QueueAdapter(
            onCancel = { entry ->
                TriggerRuntime.cancelQueued(entry.id)
                reloadData()
            },
        )
        binding.queuedRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.queuedRecyclerView.adapter = queueAdapter

        binding.buttonShowRuns.isChecked = true
        binding.historyToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentView = when (checkedId) {
                binding.buttonShowQueued.id -> HistoryView.QUEUED
                else -> HistoryView.RUNS
            }
            reloadData()
        }
    }

    private fun refreshPermissionSummary() {
        val notificationStatus = if (isNotificationAccessEnabled()) "granted" else "missing"
        val smsStatus = if (hasPermission(Manifest.permission.RECEIVE_SMS)) "granted" else "missing"
        val contactsStatus = if (hasPermission(Manifest.permission.READ_CONTACTS)) "granted" else "missing"

        binding.permissionsSummaryText.text =
            "Notification: $notificationStatus | SMS: $smsStatus | Contacts: $contactsStatus"
        binding.notificationAccessStatusText.text = notificationStatus.replaceFirstChar { it.uppercase() }
        binding.smsPermissionStatusText.text = smsStatus.replaceFirstChar { it.uppercase() }
        binding.contactsPermissionStatusText.text = contactsStatus.replaceFirstChar { it.uppercase() }

        val showExactAlarmCard = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        binding.exactAlarmCard.visibility = if (showExactAlarmCard) View.VISIBLE else View.GONE
        if (!showExactAlarmCard) return

        val exactAlarmAvailable = hasExactAlarmAccess()
        binding.exactAlarmSummaryText.text = if (exactAlarmAvailable) {
            "Exact alarms are available. Minute-level time rules should run on time."
        } else {
            "Exact alarms are unavailable, so time rules can drift by about a minute until access is granted."
        }
        binding.buttonExactAlarmAccess.visibility = if (exactAlarmAvailable) View.GONE else View.VISIBLE
    }

    private fun reloadData() {
        val rules = TriggerRuntime.listRules()
        val runs = TriggerRuntime.listRuns()
        val queued = TriggerRuntime.listQueued()
        ruleAdapter.submitList(rules)
        runAdapter.submitList(runs)
        queueAdapter.submitList(queued)
        binding.emptyRulesText.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

        val showRuns = currentView == HistoryView.RUNS
        binding.runsRecyclerView.visibility = if (showRuns) View.VISIBLE else View.GONE
        binding.queuedRecyclerView.visibility = if (showRuns) View.GONE else View.VISIBLE
        binding.emptyRunsText.visibility = if (showRuns && runs.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyQueuedText.visibility = if (!showRuns && queued.isEmpty()) View.VISIBLE else View.GONE
        binding.clearRunsButton.visibility = if (showRuns && runs.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleRunSwiped(position: Int) {
        val record = runAdapter.getItemOrNull(position)
        if (record == null) {
            runAdapter.notifyDataSetChanged()
            return
        }
        TriggerRuntime.deleteRun(record.id)
        reloadData()
        Snackbar.make(binding.root, "Recent run removed", Snackbar.LENGTH_SHORT)
            .setAction("Undo") {
                TriggerRuntime.restoreRun(record)
                reloadData()
            }
            .show()
    }

    private fun confirmClearRuns() {
        if (runAdapter.itemCount == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear recent runs")
            .setMessage("Remove all recent trigger run records?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                TriggerRuntime.clearRuns()
                reloadData()
            }
            .show()
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val componentName = ComponentName(this, MobilerunNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(componentName.flattenToString()) == true
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasExactAlarmAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "Exact alarms do not need extra access on this Android version", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasExactAlarmAccess()) {
            Toast.makeText(this, "Exact alarms are already enabled", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                "package:$packageName".toUri(),
            ),
        )
    }

    private class RuleAdapter(
        private val onToggle: (TriggerRule, Boolean) -> Unit,
        private val onOpen: (TriggerRule) -> Unit,
    ) : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {
        private val items = mutableListOf<TriggerRule>()

        fun submitList(rules: List<TriggerRule>) {
            items.clear()
            items.addAll(rules)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
            val binding = ItemTriggerRuleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return RuleViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
            holder.bind(items[position], onToggle, onOpen)
        }

        override fun getItemCount(): Int = items.size

        class RuleViewHolder(
            private val binding: ItemTriggerRuleBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(
                rule: TriggerRule,
                onToggle: (TriggerRule, Boolean) -> Unit,
                onOpen: (TriggerRule) -> Unit,
            ) {
                binding.ruleNameText.text = rule.name
                binding.ruleSummaryText.text = TriggerUiSupport.summary(rule)
                binding.ruleStatusText.text = buildString {
                    append(if (rule.enabled) "Enabled" else "Disabled")
                    rule.maxLaunchCount?.let { maxCount ->
                        append(" • ")
                        append(rule.successfulLaunchCount)
                        append("/")
                        append(maxCount)
                        append(" launches used")
                    }
                    if (rule.lastMatchedAtMs > 0) {
                        append(" • matched ")
                        append(TriggerUiSupport.formatTimestamp(rule.lastMatchedAtMs))
                    }
                    if (rule.lastLaunchedAtMs > 0) {
                        append(" • launched ")
                        append(TriggerUiSupport.formatTimestamp(rule.lastLaunchedAtMs))
                    }
                }
                binding.ruleEnabledSwitch.setOnCheckedChangeListener(null)
                binding.ruleEnabledSwitch.isChecked = rule.enabled
                binding.ruleEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(rule, isChecked)
                }
                binding.ruleEditButton.setOnClickListener { onOpen(rule) }
                binding.root.setOnClickListener { onOpen(rule) }
            }
        }
    }

    private class QueueAdapter(
        private val onCancel: (TriggerQueueEntry) -> Unit,
    ) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {
        private val items = mutableListOf<TriggerQueueEntry>()

        fun submitList(entries: List<TriggerQueueEntry>) {
            items.clear()
            items.addAll(entries)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
            val binding = ItemTriggerQueueEntryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return QueueViewHolder(binding)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            holder.bind(items[position], onCancel)
        }

        override fun getItemCount(): Int = items.size

        class QueueViewHolder(
            private val binding: ItemTriggerQueueEntryBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(entry: TriggerQueueEntry, onCancel: (TriggerQueueEntry) -> Unit) {
                binding.queueRuleNameText.text = entry.ruleName
                binding.queueMetaText.text = buildString {
                    append(entry.source.name.lowercase().replace('_', ' '))
                    val sender = entry.signal.payload["title"]
                    if (!sender.isNullOrBlank()) {
                        append(" • ")
                        append(sender)
                    }
                    append(" • queued ")
                    append(TriggerUiSupport.formatTimestamp(entry.enqueuedAtMs))
                }
                binding.queueCancelButton.setOnClickListener { onCancel(entry) }
            }
        }
    }

    private class RunAdapter : RecyclerView.Adapter<RunAdapter.RunViewHolder>() {
        private val items = mutableListOf<TriggerRunRecord>()

        fun submitList(records: List<TriggerRunRecord>) {
            items.clear()
            items.addAll(records)
            notifyDataSetChanged()
        }

        fun getItemOrNull(position: Int): TriggerRunRecord? {
            return items.getOrNull(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
            val binding = ItemTriggerRunRecordBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return RunViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class RunViewHolder(
            private val binding: ItemTriggerRunRecordBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(record: TriggerRunRecord) {
                binding.runSummaryText.text = record.summary
                binding.runMetaText.text = buildString {
                    append(TriggerUiSupport.formatTimestamp(record.timestampMs))
                    append(" • ")
                    append(record.ruleName)
                    append(" • ")
                    append(TriggerUiSupport.dispositionLabel(record.disposition))
                }
            }
        }
    }
}
