package com.mobilerun.portal.taskprompt

object PortalTaskScreenshotUiSupport {
    fun shouldIgnorePreviewTap(
        requestedUrl: String,
        selectedUrl: String?,
        pendingUrl: String?,
        hasVisiblePreview: Boolean,
    ): Boolean {
        if (requestedUrl.isBlank()) return true
        if (requestedUrl != selectedUrl) return false
        return hasVisiblePreview || requestedUrl == pendingUrl
    }

    fun urlsToLoadForGallery(
        urls: List<String>,
        cachedUrls: Set<String>,
    ): List<String> {
        return urls.filter { it.isNotBlank() && !cachedUrls.contains(it) }
    }
}
