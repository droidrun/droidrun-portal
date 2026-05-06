package com.mobilerun.portal.ui.taskprompt

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.mobilerun.portal.R
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTaskHistoryBinding
import com.mobilerun.portal.databinding.ItemTaskHistoryBinding
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalTaskHistoryItem
import com.mobilerun.portal.taskprompt.PortalTaskHistoryResult
import com.mobilerun.portal.taskprompt.PortalTaskStatusAppearance
import com.mobilerun.portal.taskprompt.PortalTaskUiSupport
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class TaskHistoryActivity : AppCompatActivity() {
    companion object {
        private const val PAGE_SIZE = 20
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun createIntent(context: Context): Intent {
            return Intent(context, TaskHistoryActivity::class.java)
        }
    }

    private val portalCloudClient = PortalCloudClient()
    private val handler = Handler(Looper.getMainLooper())
    private val items = mutableListOf<PortalTaskHistoryItem>()

    private lateinit var binding: ActivityTaskHistoryBinding
    private lateinit var footerView: LinearLayout
    private lateinit var adapter: TaskHistoryAdapter

    private val searchInput: TextInputEditText
        get() = binding.taskHistorySearchInput
    private val listView: ListView
        get() = binding.taskHistoryList
    private val loadingView: View
        get() = binding.taskHistoryLoadingView
    private val emptyView: View
        get() = binding.taskHistoryEmptyView
    private val emptyText: TextView
        get() = binding.taskHistoryEmptyText
    private val retryButton: MaterialButton
        get() = binding.taskHistoryRetryButton

    private var searchRunnable: Runnable? = null
    private var currentPage = 0
    private var hasNextPage = false
    private var isInitialLoading = false
    private var isLoadingMore = false
    private var requestToken = 0
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.taskHistoryBackButton.setOnClickListener {
            finish()
        }

        footerView = buildFooterView()
        listView.addFooterView(footerView, null, false)
        footerView.visibility = View.GONE
        adapter = TaskHistoryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as? PortalTaskHistoryItem ?: return@setOnItemClickListener
            startActivity(TaskDetailsActivity.createIntent(this, item.taskId))
        }
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                if (!hasNextPage || isInitialLoading || isLoadingMore || totalItemCount == 0) {
                    return
                }
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 2) {
                    loadTasks(reset = false)
                }
            }
        })

        retryButton.setOnClickListener { loadTasks(reset = true) }
        searchInput.doAfterTextChanged {
            scheduleSearch()
        }

        if (!hasValidSession(showToast = true)) {
            finish()
            return
        }
        loadTasks(reset = true)
    }

    override fun onDestroy() {
        searchRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    private fun scheduleSearch() {
        searchRunnable?.let(handler::removeCallbacks)
        searchRunnable = Runnable { loadTasks(reset = true) }
        handler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
    }

    private fun loadTasks(reset: Boolean) {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            if (hasValidSession(showToast = true)) return
            finish()
            return
        }

        if (reset) {
            isInitialLoading = true
            isLoadingMore = false
            currentPage = 0
            hasNextPage = false
            errorMessage = null
        } else {
            if (!hasNextPage || isInitialLoading || isLoadingMore) return
            isLoadingMore = true
        }
        renderState()

        val nextPage = if (reset) 1 else currentPage + 1
        val query = searchInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        val localRequestToken = ++requestToken
        portalCloudClient.listTasks(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            query = query,
            page = nextPage,
            pageSize = PAGE_SIZE,
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed || localRequestToken != requestToken) {
                    return@runOnUiThread
                }

                isInitialLoading = false
                isLoadingMore = false
                when (result) {
                    is PortalTaskHistoryResult.Success -> {
                        errorMessage = null
                        if (reset) {
                            items.clear()
                        }
                        items.addAll(result.value.items)
                        currentPage = result.value.page
                        hasNextPage = result.value.hasNext
                    }

                    is PortalTaskHistoryResult.Error -> {
                        if (!reset) {
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        } else {
                            items.clear()
                            errorMessage = result.message
                            hasNextPage = false
                            currentPage = 0
                        }
                    }
                }

                adapter.notifyDataSetChanged()
                renderState()
            }
        }
    }

    private fun renderState() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val showLoading = isInitialLoading && items.isEmpty()
        val showList = items.isNotEmpty()
        val showEmpty = !showLoading && !showList

        loadingView.visibility = if (showLoading) View.VISIBLE else View.GONE
        listView.visibility = if (showList) View.VISIBLE else View.GONE
        emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        footerView.visibility = if (isLoadingMore) View.VISIBLE else View.GONE

        if (showEmpty) {
            emptyText.text = when {
                !errorMessage.isNullOrBlank() -> errorMessage
                query.isNotBlank() -> getString(R.string.task_history_empty_search)
                else -> getString(R.string.task_history_empty)
            }
            retryButton.visibility = if (errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            retryButton.visibility = View.GONE
        }
    }

    private fun hasValidSession(showToast: Boolean): Boolean {
        val authToken = currentAuthToken()
        if (authToken.isBlank()) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.task_prompt_missing_api_key), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (currentRestBaseUrl() == null) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.task_prompt_unsupported_custom_url), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun currentAuthToken(): String {
        return ConfigManager.getInstance(this).reverseConnectionToken.trim()
    }

    private fun currentRestBaseUrl(): String? {
        return PortalCloudClient.deriveRestBaseUrl(
            ConfigManager.getInstance(this).reverseConnectionUrlOrDefault,
        )
    }

    private fun buildFooterView(): LinearLayout {
        val padding = (12 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                text = getString(R.string.task_history_load_more)
                setTextColor(ContextCompat.getColor(context, R.color.task_prompt_text_secondary))
                textSize = 13f
                setPadding(padding / 2, 0, 0, 0)
            })
        }
    }

    private inner class TaskHistoryAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@TaskHistoryActivity)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView == null) {
                ItemTaskHistoryBinding.inflate(inflater, parent, false).also { binding ->
                    binding.root.tag = binding
                }
            } else {
                (convertView.tag as? ItemTaskHistoryBinding)
                    ?: ItemTaskHistoryBinding.bind(convertView).also { binding ->
                        binding.root.tag = binding
                    }
            }
            val item = items[position]

            itemBinding.taskHistoryItemStatusChip.text =
                PortalTaskUiSupport.statusLabel(this@TaskHistoryActivity, item.status)
            itemBinding.taskHistoryItemStatusChip.background =
                createChipBackground(PortalTaskUiSupport.statusAppearance(item.status))
            itemBinding.taskHistoryItemPrompt.text =
                item.promptPreview.ifBlank { item.prompt.ifBlank { item.taskId } }

            val metaParts = mutableListOf<String>()
            PortalTaskUiSupport.formatTimestamp(item.createdAt)?.let(metaParts::add)
            item.steps?.let { metaParts.add(getString(R.string.task_history_steps, it)) }
            itemBinding.taskHistoryItemMeta.text = metaParts.joinToString(" • ").ifBlank { item.taskId }

            val summary = PortalTaskUiSupport.buildSummary(
                context = this@TaskHistoryActivity,
                status = item.status,
                summary = item.summary,
                steps = item.steps,
            )
            itemBinding.taskHistoryItemSummary.text = summary.orEmpty()
            itemBinding.taskHistoryItemSummary.visibility =
                if (summary.isNullOrBlank()) View.GONE else View.VISIBLE
            return itemBinding.root
        }
    }

    private fun createChipBackground(appearance: PortalTaskStatusAppearance): GradientDrawable {
        val colorRes = when (appearance) {
            PortalTaskStatusAppearance.INFO -> R.color.task_prompt_chip_info_bg
            PortalTaskStatusAppearance.SUCCESS -> R.color.task_prompt_chip_success_bg
            PortalTaskStatusAppearance.ERROR -> R.color.task_prompt_chip_error_bg
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(this@TaskHistoryActivity, colorRes))
        }
    }
}
