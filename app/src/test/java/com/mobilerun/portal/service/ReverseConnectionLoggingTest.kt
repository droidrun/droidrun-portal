package com.mobilerun.portal.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseConnectionLoggingTest {
    @Test
    fun requestRedaction_coversSensitiveWritePayloads() {
        assertTrue(shouldRedactReverseRequestPayload("clipboard/set"))
        assertTrue(shouldRedactReverseRequestPayload("app/deep-link"))
        assertFalse(shouldRedactReverseRequestPayload("clipboard/get"))
        assertFalse(shouldRedactReverseRequestPayload("app"))
    }

    @Test
    fun responseRedaction_coversSensitiveReadAndDeepLinkPayloads() {
        assertTrue(shouldRedactReverseResponsePayload("clipboard/get"))
        assertTrue(shouldRedactReverseResponsePayload("app/deep-link"))
        assertFalse(shouldRedactReverseResponsePayload("clipboard/set"))
        assertFalse(shouldRedactReverseResponsePayload("app"))
    }
}
