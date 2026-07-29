package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun fitCaptureSizeToScreen_fitsIntoArbitraryBoxWithoutUpscale() {
        val cases =
            listOf(
                BoxCase("cap smaller than screen", 1080, 1920, 400, 800, 400 to 710),
                BoxCase("cap larger than screen, no upscale", 300, 500, 720, 1280, 300 to 500),
                BoxCase("landscape box", 1920, 1080, 1024, 600, 1024 to 576),
            )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureSizing.fitCaptureSizeToScreen(
                    case.screenWidth,
                    case.screenHeight,
                    case.boxWidth,
                    case.boxHeight,
                ),
            )
        }
    }

    @Test
    fun fitCaptureSizeToScreen_boundaryJustBelowHeightMinIsRejected() {
        // Landscape screen fitted height-limited into a 256-tall box lands
        // exactly on CAPTURE_HEIGHT_MIN: accepted.
        assertEquals(
            454 to 256,
            CaptureSizing.fitCaptureSizeToScreen(1920, 1080, 3840, 256),
        )

        // One pixel shorter drops the fitted height just below
        // CAPTURE_HEIGHT_MIN once floored to even: rejected wholesale.
        assertEquals(
            null,
            CaptureSizing.fitCaptureSizeToScreen(1920, 1080, 3840, 255),
        )
    }

    @Test
    fun fitCaptureSizeToScreen_boundaryJustBelowWidthMinIsRejected() {
        // Portrait screen width-limited into a 144-wide box lands exactly on
        // CAPTURE_WIDTH_MIN: accepted.
        assertEquals(
            144 to 256,
            CaptureSizing.fitCaptureSizeToScreen(1080, 1920, 144, 3840),
        )

        // One pixel narrower drops the fitted width just below
        // CAPTURE_WIDTH_MIN once floored to even: rejected wholesale.
        assertEquals(
            null,
            CaptureSizing.fitCaptureSizeToScreen(1080, 1920, 143, 3840),
        )
    }

    @Test
    fun fitCaptureSizeToScreen_rejectsWholesaleWhenFitFallsOutsideBounds() {
        assertEquals(
            null,
            CaptureSizing.fitCaptureSizeToScreen(1080, 1920, 100, 3840),
        )
        assertEquals(
            null,
            CaptureSizing.fitCaptureSizeToScreen(1080, 1920, 1920, 100),
        )
        assertEquals(
            null,
            CaptureSizing.fitCaptureSizeToScreen(1080, 1920, 0, 100),
        )
    }

    @Test
    fun fitCaptureSizeToScreen_resultNeverExceedsBoxAndIsEvenAndInBounds() {
        val boxes =
            listOf(
                Triple(1080, 1920, 400 to 800),
                Triple(1920, 1080, 1024 to 600),
                Triple(300, 500, 720 to 1280),
                Triple(2856, 1280, 1920 to 3840),
            )

        boxes.forEach { (screenWidth, screenHeight, box) ->
            val (boxWidth, boxHeight) = box
            val result =
                CaptureSizing.fitCaptureSizeToScreen(screenWidth, screenHeight, boxWidth, boxHeight)
            if (result != null) {
                val (width, height) = result
                assertTrue("width even: $result", width % 2 == 0)
                assertTrue("height even: $result", height % 2 == 0)
                assertTrue("width <= box: $result vs $box", width <= boxWidth)
                assertTrue("height <= box: $result vs $box", height <= boxHeight)
                assertTrue(
                    "width in bounds: $result",
                    width in CaptureSizing.CAPTURE_WIDTH_MIN..CaptureSizing.CAPTURE_WIDTH_MAX,
                )
                assertTrue(
                    "height in bounds: $result",
                    height in CaptureSizing.CAPTURE_HEIGHT_MIN..CaptureSizing.CAPTURE_HEIGHT_MAX,
                )
            }
        }
    }

    @Test
    fun fitCaptureSizeToScreen_defaultsToLegacyBoxWhenBoxOmitted() {
        assertEquals(
            CaptureSizing.deriveAutoCaptureSize(1080, 1920),
            CaptureSizing.fitCaptureSizeToScreen(1080, 1920),
        )
    }

    private data class SizingCase(
        val name: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val expected: Pair<Int, Int>,
    )

    private data class BoxCase(
        val name: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val boxWidth: Int,
        val boxHeight: Int,
        val expected: Pair<Int, Int>,
    )
}
