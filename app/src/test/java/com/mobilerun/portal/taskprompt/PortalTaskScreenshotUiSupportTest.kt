package com.mobilerun.portal.taskprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTaskScreenshotUiSupportTest {

    @Test
    fun shouldIgnorePreviewTap_returnsTrueForActivePreviewOrPendingLoad() {
        assertTrue(
            PortalTaskScreenshotUiSupport.shouldIgnorePreviewTap(
                requestedUrl = "https://example.com/one.png",
                selectedUrl = "https://example.com/one.png",
                pendingUrl = null,
                hasVisiblePreview = true,
            ),
        )
        assertTrue(
            PortalTaskScreenshotUiSupport.shouldIgnorePreviewTap(
                requestedUrl = "https://example.com/one.png",
                selectedUrl = "https://example.com/one.png",
                pendingUrl = "https://example.com/one.png",
                hasVisiblePreview = false,
            ),
        )
        assertFalse(
            PortalTaskScreenshotUiSupport.shouldIgnorePreviewTap(
                requestedUrl = "https://example.com/two.png",
                selectedUrl = "https://example.com/one.png",
                pendingUrl = null,
                hasVisiblePreview = true,
            ),
        )
    }

    @Test
    fun urlsToLoadForGallery_skipsCachedUrlsAndKeepsOrder() {
        val result = PortalTaskScreenshotUiSupport.urlsToLoadForGallery(
            urls = listOf(
                "https://example.com/one.png",
                "https://example.com/two.png",
                "https://example.com/three.png",
            ),
            cachedUrls = setOf("https://example.com/two.png"),
        )

        assertEquals(
            listOf(
                "https://example.com/one.png",
                "https://example.com/three.png",
            ),
            result,
        )
    }
}
