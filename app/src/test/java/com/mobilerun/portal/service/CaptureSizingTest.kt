package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSizingTest {
    @Test
    fun deriveAutoCaptureSize_aspectFitsCommonScreenSizes() {
        val cases =
            listOf(
                SizingCase("tall portrait", 1280, 2856, 572 to 1280),
                SizingCase("wide landscape", 2856, 1280, 720 to 322),
                SizingCase("two-to-one portrait", 1080, 2160, 640 to 1280),
            )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureSizing.deriveAutoCaptureSize(case.screenWidth, case.screenHeight),
            )
        }
    }

    @Test
    fun deriveAutoCaptureSize_handlesExactNineBySixteenWithoutRoundingLoss() {
        val cases =
            listOf(
                SizingCase("PR regression dimensions", 1386, 2464, 720 to 1280),
                SizingCase("standard FHD dimensions", 1080, 1920, 720 to 1280),
                SizingCase("QHD dimensions", 1440, 2560, 720 to 1280),
            )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureSizing.deriveAutoCaptureSize(case.screenWidth, case.screenHeight),
            )
        }
    }

    @Test
    fun deriveAutoCaptureSize_doesNotUpscaleAndFloorsToEven() {
        assertEquals(
            700 to 1000,
            CaptureSizing.deriveAutoCaptureSize(screenWidth = 701, screenHeight = 1001),
        )
    }

    @Test
    fun deriveAutoCaptureSize_fallsBackWholesaleForInvalidOrOutOfBoundsFits() {
        val fallback =
            CaptureSizing.DEFAULT_CAPTURE_WIDTH to CaptureSizing.DEFAULT_CAPTURE_HEIGHT
        val cases =
            listOf(
                SizingCase("zero width", 0, 1000, fallback),
                SizingCase("negative height", 1000, -1, fallback),
                SizingCase("undersized screen", 200, 200, fallback),
                SizingCase("too wide", 3000, 1000, fallback),
                SizingCase("too tall", 1000, 10000, fallback),
                SizingCase("extreme integer aspect", Int.MAX_VALUE, 1, fallback),
            )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureSizing.deriveAutoCaptureSize(case.screenWidth, case.screenHeight),
            )
        }
    }

    private data class SizingCase(
        val name: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val expected: Pair<Int, Int>,
    )
}
