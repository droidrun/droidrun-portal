package com.mobilerun.portal.api

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHandlerCaptureParamTest {
    @Test
    fun isNumericCaptureParam_trueForNumbersAndNumericStrings() {
        val params = JSONObject().apply {
            put("width", 800)
            put("widthString", "800")
            put("heightNull", JSONObject.NULL)
            put("heightString", "tall")
        }

        assertTrue(ApiHandler.isNumericCaptureParam(params, "width"))
        // Legacy `optInt` parsed numeric strings too, so a string value must
        // still count as present.
        assertTrue(ApiHandler.isNumericCaptureParam(params, "widthString"))
        assertFalse(ApiHandler.isNumericCaptureParam(params, "heightNull"))
        assertFalse(ApiHandler.isNumericCaptureParam(params, "heightString"))
        assertFalse(ApiHandler.isNumericCaptureParam(params, "missing"))
    }
}
