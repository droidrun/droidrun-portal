package com.mobilerun.portal.ui.taskprompt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mobilerun.portal.R
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTaskDetailsBinding
import com.mobilerun.portal.databinding.ItemTaskDetailsScreenshotOptionBinding
import com.mobilerun.portal.databinding.ItemTaskDetailsScreenshotPreviewBinding
import com.mobilerun.portal.databinding.ItemTaskDetailsTrajectoryEventBinding
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalTaskDetails
import com.mobilerun.portal.taskprompt.PortalTaskDetailsResult
import com.mobilerun.portal.taskprompt.PortalTaskScreenshotResult
import com.mobilerun.portal.taskprompt.PortalTaskTrajectoryEvent
import com.mobilerun.portal.taskprompt.PortalTaskTrajectoryResult
import com.mobilerun.portal.taskprompt.PortalTaskTrajectoryUiSupport
import com.mobilerun.portal.taskprompt.PortalTaskScreenshotUiSupport
import com.mobilerun.portal.taskprompt.PortalTaskStatusAppearance
import com.mobilerun.portal.taskprompt.PortalTaskUiSupport
import com.google.android.material.button.MaterialButton
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.LinkedHashMap

class TaskDetailsActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_TASK_ID = "extra_task_id"

        fun createIntent(context: Context, taskId: String): Intent {
            return Intent(context, TaskDetailsActivity::class.java).putExtra(EXTRA_TASK_ID, taskId)
        }
    }

    private data class GalleryPreviewItem(
        val url: String,
        val title: String,
        val bitmap: Bitmap? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    private val portalCloudClient = PortalCloudClient()
    private val screenshotClient = OkHttpClient()
    private val screenshotUrls = mutableListOf<String>()
    private val screenshotBitmapCache = LinkedHashMap<String, Bitmap>()

    private lateinit var binding: ActivityTaskDetailsBinding
    private lateinit var trajectoryAdapter: TrajectoryAdapter
    private lateinit var screenshotAdapter: ScreenshotAdapter
    private lateinit var screenshotGalleryAdapter: ScreenshotGalleryAdapter

    private val loadingView: View
        get() = binding.taskDetailsLoadingView
    private val errorView: View
        get() = binding.taskDetailsErrorView
    private val errorText: TextView
        get() = binding.taskDetailsErrorText
    private val contentView: NestedScrollView
        get() = binding.taskDetailsContentView
    private val statusChip: TextView
        get() = binding.taskDetailsStatusChip
    private val copyPromptButton: ImageView
        get() = binding.taskDetailsCopyPromptButton
    private val copyMetadataButton: ImageView
        get() = binding.taskDetailsCopyMetadataButton
    private val promptValue: TextView
        get() = binding.taskDetailsPromptValue
    private val resultLabel: TextView
        get() = binding.taskDetailsResultLabel
    private val resultValue: TextView
        get() = binding.taskDetailsResultValue
    private val metadataValue: TextView
        get() = binding.taskDetailsMetadataValue
    private val trajectorySection: View
        get() = binding.taskDetailsTrajectorySection
    private val trajectoryHeader: View
        get() = binding.taskDetailsTrajectoryHeader
    private val trajectorySubtitle: TextView
        get() = binding.taskDetailsTrajectorySubtitle
    private val trajectoryRefreshButton: MaterialButton
        get() = binding.taskDetailsTrajectoryRefreshButton
    private val trajectoryChevron: ImageView
        get() = binding.taskDetailsTrajectoryChevron
    private val trajectoryContent: View
        get() = binding.taskDetailsTrajectoryContent
    private val trajectoryLoading: ProgressBar
        get() = binding.taskDetailsTrajectoryLoading
    private val trajectoryMessage: TextView
        get() = binding.taskDetailsTrajectoryMessage
    private val trajectoryList: RecyclerView
        get() = binding.taskDetailsTrajectoryList
    private val screenshotSection: View
        get() = binding.taskDetailsScreenshotSection
    private val screenshotHeader: View
        get() = binding.taskDetailsScreenshotHeader
    private val screenshotSubtitle: TextView
        get() = binding.taskDetailsScreenshotSubtitle
    private val screenshotChevron: ImageView
        get() = binding.taskDetailsScreenshotChevron
    private val screenshotContent: View
        get() = binding.taskDetailsScreenshotContent
    private val screenshotLoading: ProgressBar
        get() = binding.taskDetailsScreenshotLoading
    private val screenshotMessage: TextView
        get() = binding.taskDetailsScreenshotMessage
    private val screenshotList: RecyclerView
        get() = binding.taskDetailsScreenshotList
    private val loadAllScreenshotsButton: MaterialButton
        get() = binding.taskDetailsLoadAllScreenshotsButton
    private val screenshotPreviewLoading: ProgressBar
        get() = binding.taskDetailsScreenshotPreviewLoading
    private val screenshotImage: ImageView
        get() = binding.taskDetailsScreenshotImage
    private val screenshotGalleryList: RecyclerView
        get() = binding.taskDetailsScreenshotGalleryList

    private lateinit var taskId: String
    private var currentTaskDetails: PortalTaskDetails? = null
    private var trajectoryEvents: List<PortalTaskTrajectoryEvent> = emptyList()
    private var isTrajectorySectionExpanded = false
    private var hasLoadedTrajectory = false
    private var isTrajectoryLoading = false
    private var trajectoryErrorMessage: String? = null
    private var isScreenshotSectionExpanded = false
    private var hasLoadedScreenshotUrls = false
    private var isScreenshotListLoading = false
    private var isScreenshotGalleryMode = false
    private var isLoadingAllScreenshots = false
    private var selectedScreenshotUrl: String? = null
    private var pendingScreenshotUrl: String? = null
    private var galleryLoadSessionId = 0
    private var galleryItems: List<GalleryPreviewItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        taskId = intent.getStringExtra(EXTRA_TASK_ID)?.trim().orEmpty()
        if (taskId.isBlank()) {
            finish()
            return
        }

        binding.taskDetailsBackButton.setOnClickListener {
            finish()
        }
        binding.taskDetailsRetryButton.setOnClickListener {
            loadTask()
        }

        trajectoryAdapter = TrajectoryAdapter()
        trajectoryList.layoutManager = LinearLayoutManager(this)
        trajectoryList.adapter = trajectoryAdapter
        trajectoryList.itemAnimator = null

        screenshotAdapter = ScreenshotAdapter { url ->
            selectScreenshot(url)
        }
        screenshotList.layoutManager = LinearLayoutManager(this)
        screenshotList.adapter = screenshotAdapter
        screenshotList.itemAnimator = null

        screenshotGalleryAdapter = ScreenshotGalleryAdapter()
        screenshotGalleryList.layoutManager = LinearLayoutManager(this)
        screenshotGalleryList.adapter = screenshotGalleryAdapter
        screenshotGalleryList.itemAnimator = null

        copyPromptButton.setOnClickListener {
            copyPromptToClipboard()
        }
        copyMetadataButton.setOnClickListener {
            copyMetadataToClipboard()
        }
        trajectoryHeader.setOnClickListener {
            toggleTrajectorySection()
        }
        trajectoryRefreshButton.setOnClickListener {
            refreshTrajectory()
        }
        screenshotHeader.setOnClickListener {
            toggleScreenshotSection()
        }
        loadAllScreenshotsButton.setOnClickListener {
            startLoadAllScreenshots()
        }

        if (!hasValidSession(showToast = true)) {
            finish()
            return
        }
        loadTask()
    }

    private fun loadTask() {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            if (!hasValidSession(showToast = true)) {
                finish()
            }
            return
        }

        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        contentView.visibility = View.GONE
        portalCloudClient.getTask(restBaseUrl, authToken, taskId) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                when (result) {
                    is PortalTaskDetailsResult.Success -> {
                        renderDetails(result.value)
                    }

                    is PortalTaskDetailsResult.Error -> {
                        loadingView.visibility = View.GONE
                        errorView.visibility = View.VISIBLE
                        contentView.visibility = View.GONE
                        errorText.text = result.message
                    }
                }
            }
        }
    }

    private fun renderDetails(details: PortalTaskDetails) {
        currentTaskDetails = details
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        contentView.visibility = View.VISIBLE

        statusChip.text = PortalTaskUiSupport.statusLabel(this, details.status)
        statusChip.background =
            createChipBackground(PortalTaskUiSupport.statusAppearance(details.status))
        promptValue.text = details.prompt.ifBlank { details.promptPreview.ifBlank { taskId } }

        val resultText = PortalTaskUiSupport.buildSummary(
            context = this,
            status = details.status,
            summary = details.summary,
            steps = details.steps,
        )
        resultLabel.visibility = if (resultText.isNullOrBlank()) View.GONE else View.VISIBLE
        resultValue.visibility = if (resultText.isNullOrBlank()) View.GONE else View.VISIBLE
        resultValue.text = resultText.orEmpty()

        metadataValue.text = buildMetadata(details)
        resetTrajectorySection()
        resetScreenshotSection()
    }

    private fun resetTrajectorySection() {
        trajectorySection.visibility = View.VISIBLE
        trajectoryEvents = emptyList()
        trajectoryAdapter.updateItems(emptyList())
        isTrajectorySectionExpanded = false
        hasLoadedTrajectory = false
        isTrajectoryLoading = false
        trajectoryErrorMessage = null
        trajectoryContent.visibility = View.GONE
        trajectoryLoading.visibility = View.GONE
        trajectoryMessage.visibility = View.GONE
        trajectoryList.visibility = View.GONE
        trajectorySubtitle.text = getString(R.string.task_details_trajectory_collapsed_hint)
        trajectoryRefreshButton.visibility = View.GONE
        trajectoryRefreshButton.isEnabled = true
        trajectoryChevron.rotation = 0f
    }

    private fun resetScreenshotSection() {
        screenshotSection.visibility = View.VISIBLE
        screenshotUrls.clear()
        screenshotBitmapCache.clear()
        galleryItems = emptyList()
        screenshotAdapter.updateItems(emptyList(), null)
        screenshotGalleryAdapter.updateItems(emptyList())
        hasLoadedScreenshotUrls = false
        isScreenshotListLoading = false
        isScreenshotGalleryMode = false
        isLoadingAllScreenshots = false
        selectedScreenshotUrl = null
        pendingScreenshotUrl = null
        galleryLoadSessionId = 0
        isScreenshotSectionExpanded = false
        screenshotContent.visibility = View.GONE
        screenshotLoading.visibility = View.GONE
        screenshotMessage.visibility = View.GONE
        screenshotList.visibility = View.GONE
        loadAllScreenshotsButton.visibility = View.GONE
        loadAllScreenshotsButton.isEnabled = true
        loadAllScreenshotsButton.text = getString(R.string.task_details_load_all_screenshots)
        screenshotPreviewLoading.visibility = View.GONE
        screenshotImage.visibility = View.GONE
        screenshotImage.setImageDrawable(null)
        screenshotGalleryList.visibility = View.GONE
        screenshotSubtitle.text = getString(R.string.task_details_screenshot_collapsed_hint)
        screenshotChevron.rotation = 0f
    }

    private fun toggleTrajectorySection() {
        if (!hasValidSession(showToast = true)) return

        isTrajectorySectionExpanded = !isTrajectorySectionExpanded
        trajectoryContent.visibility = if (isTrajectorySectionExpanded) View.VISIBLE else View.GONE
        trajectoryChevron.rotation = if (isTrajectorySectionExpanded) 90f else 0f

        if (!isTrajectorySectionExpanded) {
            return
        }

        if (!hasLoadedTrajectory && !isTrajectoryLoading) {
            loadTrajectory(forceRefresh = false)
        } else {
            renderTrajectorySectionState()
        }
    }

    private fun refreshTrajectory() {
        if (!hasValidSession(showToast = true) || isTrajectoryLoading || !hasLoadedTrajectory) {
            return
        }
        loadTrajectory(forceRefresh = true)
    }

    private fun loadTrajectory(forceRefresh: Boolean) {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            return
        }

        isTrajectoryLoading = true
        if (!forceRefresh) {
            trajectoryErrorMessage = null
        }
        renderTrajectorySectionState()
        portalCloudClient.getTaskTrajectory(restBaseUrl, authToken, taskId) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                isTrajectoryLoading = false
                when (result) {
                    is PortalTaskTrajectoryResult.Success -> {
                        hasLoadedTrajectory = true
                        trajectoryErrorMessage = null
                        trajectoryEvents = result.value.events
                        trajectoryAdapter.updateItems(trajectoryEvents)
                        renderTrajectorySectionState()
                    }

                    is PortalTaskTrajectoryResult.Error -> {
                        if (!hasLoadedTrajectory) {
                            trajectoryEvents = emptyList()
                            trajectoryAdapter.updateItems(emptyList())
                            trajectoryErrorMessage = result.message
                        } else {
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        }
                        renderTrajectorySectionState()
                    }
                }
            }
        }
    }

    private fun renderTrajectorySectionState() {
        trajectoryRefreshButton.visibility = if (
            PortalTaskTrajectoryUiSupport.shouldShowRefreshAction(
                status = currentTaskDetails?.status,
                hasLoadedTrajectory = hasLoadedTrajectory,
            )
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        trajectoryRefreshButton.isEnabled = !isTrajectoryLoading

        if (!isTrajectorySectionExpanded) {
            trajectorySubtitle.text = when {
                isTrajectoryLoading && !hasLoadedTrajectory ->
                    getString(R.string.task_details_trajectory_loading)

                trajectoryErrorMessage != null && !hasLoadedTrajectory ->
                    getString(R.string.task_details_trajectory_error)

                hasLoadedTrajectory && trajectoryEvents.isEmpty() ->
                    getString(R.string.task_details_trajectory_empty)

                hasLoadedTrajectory ->
                    getString(R.string.task_details_trajectory_count, trajectoryEvents.size)

                else ->
                    getString(R.string.task_details_trajectory_collapsed_hint)
            }
            return
        }

        trajectoryLoading.visibility = if (isTrajectoryLoading) View.VISIBLE else View.GONE
        trajectorySubtitle.text = when {
            isTrajectoryLoading && !hasLoadedTrajectory ->
                getString(R.string.task_details_trajectory_loading)

            trajectoryErrorMessage != null && !hasLoadedTrajectory ->
                getString(R.string.task_details_trajectory_error)

            hasLoadedTrajectory && trajectoryEvents.isEmpty() ->
                getString(R.string.task_details_trajectory_empty)

            hasLoadedTrajectory ->
                getString(R.string.task_details_trajectory_count, trajectoryEvents.size)

            else ->
                getString(R.string.task_details_trajectory_collapsed_hint)
        }

        if (trajectoryErrorMessage != null && !hasLoadedTrajectory) {
            trajectoryMessage.visibility = View.VISIBLE
            trajectoryMessage.text = trajectoryErrorMessage
            trajectoryList.visibility = View.GONE
            return
        }

        if (hasLoadedTrajectory && trajectoryEvents.isEmpty()) {
            trajectoryMessage.visibility = View.VISIBLE
            trajectoryMessage.text = getString(R.string.task_details_trajectory_empty)
            trajectoryList.visibility = View.GONE
            return
        }

        if (trajectoryEvents.isNotEmpty()) {
            trajectoryMessage.visibility = View.GONE
            trajectoryList.visibility = View.VISIBLE
            return
        }

        trajectoryMessage.visibility = View.GONE
        trajectoryList.visibility = View.GONE
    }

    private fun toggleScreenshotSection() {
        if (!hasValidSession(showToast = true)) return

        isScreenshotSectionExpanded = !isScreenshotSectionExpanded
        screenshotContent.visibility = if (isScreenshotSectionExpanded) View.VISIBLE else View.GONE
        screenshotChevron.rotation = if (isScreenshotSectionExpanded) 90f else 0f

        if (!isScreenshotSectionExpanded) {
            return
        }

        if (!hasLoadedScreenshotUrls && !isScreenshotListLoading) {
            loadScreenshotUrls()
        } else {
            renderScreenshotListState()
        }
    }

    private fun loadScreenshotUrls() {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            return
        }

        isScreenshotListLoading = true
        screenshotLoading.visibility = View.VISIBLE
        screenshotMessage.visibility = View.GONE
        screenshotList.visibility = View.GONE
        loadAllScreenshotsButton.visibility = View.GONE
        screenshotPreviewLoading.visibility = View.GONE
        screenshotImage.visibility = View.GONE
        screenshotImage.setImageDrawable(null)
        screenshotGalleryList.visibility =
            if (isScreenshotGalleryMode && galleryItems.isNotEmpty()) View.VISIBLE else View.GONE
        portalCloudClient.getTaskScreenshots(restBaseUrl, authToken, taskId) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                isScreenshotListLoading = false
                when (result) {
                    is PortalTaskScreenshotResult.Success -> {
                        hasLoadedScreenshotUrls = true
                        screenshotUrls.clear()
                        screenshotUrls.addAll(result.value.urls.reversed())
                        screenshotAdapter.updateItems(screenshotUrls, selectedScreenshotUrl)
                        renderScreenshotListState()
                    }

                    is PortalTaskScreenshotResult.Error -> {
                        screenshotLoading.visibility = View.GONE
                        screenshotList.visibility = View.GONE
                        loadAllScreenshotsButton.visibility = View.GONE
                        screenshotPreviewLoading.visibility = View.GONE
                        screenshotImage.visibility = View.GONE
                        screenshotGalleryList.visibility = View.GONE
                        screenshotMessage.visibility = View.VISIBLE
                        screenshotMessage.text = result.message
                    }
                }
            }
        }
    }

    private fun renderScreenshotListState() {
        screenshotLoading.visibility = View.GONE
        screenshotSubtitle.text = if (screenshotUrls.isEmpty()) {
            getString(R.string.task_details_screenshot_empty)
        } else {
            getString(R.string.task_details_screenshot_count, screenshotUrls.size)
        }

        if (screenshotUrls.isEmpty()) {
            screenshotList.visibility = View.GONE
            loadAllScreenshotsButton.visibility = View.GONE
            screenshotPreviewLoading.visibility = View.GONE
            screenshotImage.visibility = View.GONE
            screenshotImage.setImageDrawable(null)
            screenshotGalleryList.visibility = View.GONE
            screenshotMessage.visibility = View.VISIBLE
            screenshotMessage.text = getString(R.string.task_details_screenshot_empty)
            return
        }

        screenshotList.visibility = View.VISIBLE
        loadAllScreenshotsButton.visibility =
            if (isScreenshotGalleryMode) View.GONE else View.VISIBLE
        loadAllScreenshotsButton.isEnabled = !isLoadingAllScreenshots
        loadAllScreenshotsButton.text = getString(
            if (isLoadingAllScreenshots) {
                R.string.task_details_loading_all_screenshots
            } else {
                R.string.task_details_load_all_screenshots
            },
        )
        screenshotGalleryList.visibility =
            if (isScreenshotGalleryMode && galleryItems.isNotEmpty()) View.VISIBLE else View.GONE

        if (selectedScreenshotUrl == null) {
            screenshotImage.visibility = View.GONE
            screenshotPreviewLoading.visibility = View.GONE
            screenshotMessage.visibility = View.VISIBLE
            screenshotMessage.text = getString(R.string.task_details_screenshot_select_prompt)
        }
    }

    private fun selectScreenshot(url: String) {
        if (url.isBlank()) return
        if (isScreenshotGalleryMode) {
            exitScreenshotGalleryMode()
        }
        if (PortalTaskScreenshotUiSupport.shouldIgnorePreviewTap(
                requestedUrl = url,
                selectedUrl = selectedScreenshotUrl,
                pendingUrl = pendingScreenshotUrl,
                hasVisiblePreview = screenshotImage.visibility == View.VISIBLE,
            )
        ) {
            return
        }

        selectedScreenshotUrl = url
        screenshotAdapter.updateItems(screenshotUrls, selectedScreenshotUrl)

        screenshotBitmapCache[url]?.let { bitmap ->
            pendingScreenshotUrl = null
            showSinglePreview(bitmap)
            return
        }

        screenshotMessage.visibility = View.GONE
        screenshotImage.visibility = View.GONE
        screenshotImage.setImageDrawable(null)
        screenshotPreviewLoading.visibility = View.VISIBLE
        pendingScreenshotUrl = url
        downloadScreenshot(
            url = url,
            onSuccess = { bitmap ->
                screenshotBitmapCache[url] = bitmap
                if (pendingScreenshotUrl != url) return@downloadScreenshot
                pendingScreenshotUrl = null
                showSinglePreview(bitmap)
            },
            onError = {
                if (pendingScreenshotUrl != url) return@downloadScreenshot
                pendingScreenshotUrl = null
                screenshotPreviewLoading.visibility = View.GONE
                screenshotImage.visibility = View.GONE
                screenshotMessage.visibility = View.VISIBLE
                screenshotMessage.text = getString(R.string.task_details_screenshot_preview_error)
            },
        )
    }

    private fun showSinglePreview(bitmap: Bitmap) {
        screenshotPreviewLoading.visibility = View.GONE
        screenshotMessage.visibility = View.GONE
        screenshotImage.visibility = View.VISIBLE
        screenshotImage.setImageBitmap(bitmap)
    }

    private fun startLoadAllScreenshots() {
        if (isLoadingAllScreenshots || screenshotUrls.isEmpty()) return

        isScreenshotGalleryMode = true
        selectedScreenshotUrl = null
        pendingScreenshotUrl = null
        screenshotAdapter.updateItems(screenshotUrls, null)
        galleryItems = screenshotUrls.mapIndexed { index, url ->
            GalleryPreviewItem(
                url = url,
                title = buildScreenshotTitle(index, screenshotUrls.size),
                bitmap = screenshotBitmapCache[url],
            )
        }
        screenshotGalleryAdapter.updateItems(galleryItems)
        screenshotGalleryList.visibility = View.VISIBLE

        val urlsToLoad = PortalTaskScreenshotUiSupport.urlsToLoadForGallery(
            urls = screenshotUrls,
            cachedUrls = screenshotBitmapCache.keys,
        )
        renderScreenshotListState()
        if (urlsToLoad.isEmpty()) {
            return
        }

        isLoadingAllScreenshots = true
        val sessionId = ++galleryLoadSessionId
        renderScreenshotListState()
        loadGalleryPreviewSequentially(urlsToLoad, 0, sessionId)
    }

    private fun loadGalleryPreviewSequentially(
        urlsToLoad: List<String>,
        index: Int,
        sessionId: Int,
    ) {
        if (sessionId != galleryLoadSessionId || !isScreenshotGalleryMode) {
            return
        }
        if (index >= urlsToLoad.size) {
            isLoadingAllScreenshots = false
            renderScreenshotListState()
            screenshotGalleryAdapter.updateItems(galleryItems)
            return
        }

        val url = urlsToLoad[index]
        updateGalleryItem(
            url = url,
            bitmap = null,
            isLoading = true,
            errorMessage = null,
        )

        downloadScreenshot(
            url = url,
            onSuccess = { bitmap ->
                if (sessionId != galleryLoadSessionId || !isScreenshotGalleryMode) {
                    return@downloadScreenshot
                }
                screenshotBitmapCache[url] = bitmap
                updateGalleryItem(
                    url = url,
                    bitmap = bitmap,
                    isLoading = false,
                    errorMessage = null,
                )
                loadGalleryPreviewSequentially(urlsToLoad, index + 1, sessionId)
            },
            onError = {
                if (sessionId != galleryLoadSessionId || !isScreenshotGalleryMode) {
                    return@downloadScreenshot
                }
                updateGalleryItem(
                    url = url,
                    bitmap = null,
                    isLoading = false,
                    errorMessage = getString(R.string.task_details_screenshot_preview_error),
                )
                loadGalleryPreviewSequentially(urlsToLoad, index + 1, sessionId)
            },
        )
    }

    private fun exitScreenshotGalleryMode() {
        if (!isScreenshotGalleryMode && !isLoadingAllScreenshots) return

        isScreenshotGalleryMode = false
        isLoadingAllScreenshots = false
        galleryLoadSessionId += 1
        screenshotGalleryList.visibility = View.GONE
        renderScreenshotListState()
    }

    private fun updateGalleryItem(
        url: String,
        bitmap: Bitmap?,
        isLoading: Boolean,
        errorMessage: String?,
    ) {
        galleryItems = galleryItems.map { item ->
            if (item.url != url) {
                item
            } else {
                item.copy(
                    bitmap = bitmap ?: item.bitmap,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                )
            }
        }
        screenshotGalleryAdapter.updateItems(galleryItems)
    }

    private fun downloadScreenshot(
        url: String,
        onSuccess: (Bitmap) -> Unit,
        onError: () -> Unit,
    ) {
        val request = Request.Builder().url(url).get().build()
        screenshotClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    onError()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val bytes = response.body?.bytes()
                    val bitmap = if (response.isSuccessful && bytes != null) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        null
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (bitmap == null) {
                            onError()
                        } else {
                            onSuccess(bitmap)
                        }
                    }
                }
            }
        })
    }

    private fun copyPromptToClipboard() {
        val prompt = currentTaskDetails?.prompt?.ifBlank { promptValue.text?.toString().orEmpty() }
            ?: promptValue.text?.toString().orEmpty()
        if (prompt.isBlank()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Task prompt", prompt))
        Toast.makeText(this, getString(R.string.task_details_prompt_copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyMetadataToClipboard() {
        val metadata = metadataValue.text?.toString().orEmpty().trim()
        if (metadata.isBlank()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Task metadata", metadata))
        Toast.makeText(this, getString(R.string.task_details_metadata_copied), Toast.LENGTH_SHORT).show()
    }

    private fun buildMetadata(details: PortalTaskDetails): String {
        val unavailable = getString(R.string.task_details_value_unavailable)
        return listOf(
            getString(
                R.string.task_details_metadata_created,
                PortalTaskUiSupport.formatTimestamp(details.createdAt) ?: unavailable,
            ),
            getString(
                R.string.task_details_metadata_finished,
                PortalTaskUiSupport.formatTimestamp(details.finishedAt) ?: unavailable,
            ),
            getString(R.string.task_details_metadata_task_id, details.taskId),
            getString(R.string.task_details_metadata_model, details.llmModel ?: unavailable),
            getString(
                R.string.task_details_metadata_reasoning,
                PortalTaskUiSupport.booleanLabel(this, details.reasoning),
            ),
            getString(
                R.string.task_details_metadata_vision,
                PortalTaskUiSupport.booleanLabel(this, details.vision),
            ),
            getString(
                R.string.task_details_metadata_max_steps,
                details.maxSteps?.toString() ?: unavailable,
            ),
            getString(
                R.string.task_details_metadata_timeout,
                details.executionTimeout?.toString() ?: unavailable,
            ),
            getString(
                R.string.task_details_metadata_temperature,
                details.temperature?.toString() ?: unavailable,
            ),
            getString(
                R.string.task_details_metadata_steps,
                details.steps?.toString() ?: unavailable,
            ),
        ).joinToString("\n")
    }

    private fun buildScreenshotTitle(position: Int, total: Int): String {
        return if (position == 0) {
            getString(R.string.task_details_screenshot_item_latest_title)
        } else {
            getString(R.string.task_details_screenshot_item_title, total - position)
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

    private fun createChipBackground(appearance: PortalTaskStatusAppearance): GradientDrawable {
        val colorRes = when (appearance) {
            PortalTaskStatusAppearance.INFO -> R.color.task_prompt_chip_info_bg
            PortalTaskStatusAppearance.SUCCESS -> R.color.task_prompt_chip_success_bg
            PortalTaskStatusAppearance.ERROR -> R.color.task_prompt_chip_error_bg
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(this@TaskDetailsActivity, colorRes))
        }
    }

    private fun createPillBackground(colorResId: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(this@TaskDetailsActivity, colorResId))
        }
    }

    private fun createCircleBackground(colorResId: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@TaskDetailsActivity, colorResId))
        }
    }

    private inner class TrajectoryAdapter :
        RecyclerView.Adapter<TrajectoryAdapter.TrajectoryViewHolder>() {
        private val inflater = LayoutInflater.from(this@TaskDetailsActivity)
        private val items = mutableListOf<PortalTaskTrajectoryEvent>()
        private val expandedPositions = mutableSetOf<Int>()
        private val rawVisiblePositions = mutableSetOf<Int>()

        fun updateItems(newItems: List<PortalTaskTrajectoryEvent>) {
            items.clear()
            items.addAll(newItems)
            expandedPositions.clear()
            rawVisiblePositions.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrajectoryViewHolder {
            val binding = ItemTaskDetailsTrajectoryEventBinding.inflate(inflater, parent, false)
            return TrajectoryViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: TrajectoryViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        private fun toggleExpanded(position: Int) {
            if (expandedPositions.contains(position)) {
                expandedPositions.remove(position)
                rawVisiblePositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }

        private fun toggleRaw(position: Int) {
            if (rawVisiblePositions.contains(position)) {
                rawVisiblePositions.remove(position)
            } else {
                rawVisiblePositions.add(position)
            }
            notifyItemChanged(position)
        }

        inner class TrajectoryViewHolder(
            private val itemBinding: ItemTaskDetailsTrajectoryEventBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: PortalTaskTrajectoryEvent, position: Int) {
                val isExpanded = expandedPositions.contains(position)
                val isRawVisible = rawVisiblePositions.contains(position)
                val appearance = PortalTaskTrajectoryUiSupport.iconAppearance(item)
                val detailText = PortalTaskTrajectoryUiSupport.detailText(item)
                val summaryText = PortalTaskTrajectoryUiSupport.previewSummary(item)
                    ?: getString(R.string.task_details_trajectory_summary_fallback)

                itemBinding.taskDetailsTrajectoryEventTitle.text =
                    PortalTaskTrajectoryUiSupport.eventLabel(item)
                itemBinding.taskDetailsTrajectoryEventNumber.text =
                    getString(R.string.task_details_trajectory_event_number, position + 1)
                itemBinding.taskDetailsTrajectoryEventNumber.background =
                    createPillBackground(R.color.task_prompt_chip_info_bg)
                itemBinding.taskDetailsTrajectoryEventSummary.text = summaryText
                itemBinding.taskDetailsTrajectoryEventIconContainer.background =
                    createCircleBackground(appearance.backgroundColorResId)
                itemBinding.taskDetailsTrajectoryEventIcon.setImageResource(appearance.iconResId)
                itemBinding.taskDetailsTrajectoryEventIcon.setColorFilter(
                    ContextCompat.getColor(this@TaskDetailsActivity, appearance.iconTintResId),
                )

                itemBinding.taskDetailsTrajectoryEventDetailsContainer.visibility =
                    if (isExpanded) View.VISIBLE else View.GONE
                itemBinding.taskDetailsTrajectoryEventDetails.visibility =
                    if (detailText.isNullOrBlank()) View.GONE else View.VISIBLE
                itemBinding.taskDetailsTrajectoryEventDetails.text = detailText.orEmpty()
                itemBinding.taskDetailsTrajectoryEventRawToggle.text = getString(
                    if (isRawVisible) {
                        R.string.task_details_trajectory_hide_raw
                    } else {
                        R.string.task_details_trajectory_show_raw
                    },
                )
                itemBinding.taskDetailsTrajectoryEventRawJson.visibility =
                    if (isRawVisible) View.VISIBLE else View.GONE
                itemBinding.taskDetailsTrajectoryEventRawJson.text = item.rawJson
                itemBinding.taskDetailsTrajectoryEventChevron.rotation = if (isExpanded) 90f else 0f

                itemBinding.taskDetailsTrajectoryEventCard.setOnClickListener {
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        toggleExpanded(adapterPosition)
                    }
                }
                itemBinding.taskDetailsTrajectoryEventRawToggle.setOnClickListener {
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        toggleRaw(adapterPosition)
                    }
                }
            }
        }
    }

    private inner class ScreenshotAdapter(
        private val onItemSelected: (String) -> Unit,
    ) : RecyclerView.Adapter<ScreenshotAdapter.ScreenshotViewHolder>() {
        private val inflater = LayoutInflater.from(this@TaskDetailsActivity)
        private val items = mutableListOf<String>()
        private var selectedUrl: String? = null

        fun updateItems(newItems: List<String>, selectedUrl: String?) {
            items.clear()
            items.addAll(newItems)
            this.selectedUrl = selectedUrl
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScreenshotViewHolder {
            val binding = ItemTaskDetailsScreenshotOptionBinding.inflate(inflater, parent, false)
            return ScreenshotViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ScreenshotViewHolder, position: Int) {
            holder.bind(items[position], position, items.size)
        }

        inner class ScreenshotViewHolder(
            private val itemBinding: ItemTaskDetailsScreenshotOptionBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(url: String, position: Int, total: Int) {
                itemBinding.taskDetailsScreenshotOptionTitle.text =
                    buildScreenshotTitle(position, total)
                itemBinding.taskDetailsScreenshotOptionSubtitle.text =
                    getString(R.string.task_details_screenshot_item_subtitle)

                val isSelected = url == selectedUrl
                itemBinding.taskDetailsScreenshotOptionSelectedIcon.visibility =
                    if (isSelected) View.VISIBLE else View.GONE
                itemBinding.taskDetailsScreenshotOptionCard.strokeColor = ContextCompat.getColor(
                    this@TaskDetailsActivity,
                    if (isSelected) R.color.task_prompt_accent else R.color.task_prompt_stroke,
                )
                itemBinding.taskDetailsScreenshotOptionCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        this@TaskDetailsActivity,
                        if (isSelected) R.color.task_prompt_chip_info_bg else R.color.task_prompt_card_surface,
                    ),
                )
                itemBinding.taskDetailsScreenshotOptionCard.setOnClickListener { onItemSelected(url) }
            }
        }
    }

    private inner class ScreenshotGalleryAdapter :
        RecyclerView.Adapter<ScreenshotGalleryAdapter.GalleryViewHolder>() {
        private val inflater = LayoutInflater.from(this@TaskDetailsActivity)
        private val items = mutableListOf<GalleryPreviewItem>()

        fun updateItems(newItems: List<GalleryPreviewItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            val binding = ItemTaskDetailsScreenshotPreviewBinding.inflate(inflater, parent, false)
            return GalleryViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class GalleryViewHolder(
            private val itemBinding: ItemTaskDetailsScreenshotPreviewBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: GalleryPreviewItem) {
                itemBinding.taskDetailsScreenshotPreviewTitle.text = item.title
                itemBinding.taskDetailsScreenshotPreviewItemLoading.visibility =
                    if (item.isLoading) View.VISIBLE else View.GONE
                itemBinding.taskDetailsScreenshotPreviewError.visibility =
                    if (item.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
                itemBinding.taskDetailsScreenshotPreviewError.text = item.errorMessage.orEmpty()
                if (item.bitmap == null) {
                    itemBinding.taskDetailsScreenshotPreviewImage.visibility = View.GONE
                    itemBinding.taskDetailsScreenshotPreviewImage.setImageDrawable(null)
                } else {
                    itemBinding.taskDetailsScreenshotPreviewImage.visibility = View.VISIBLE
                    itemBinding.taskDetailsScreenshotPreviewImage.setImageBitmap(item.bitmap)
                }
            }
        }
    }
}
