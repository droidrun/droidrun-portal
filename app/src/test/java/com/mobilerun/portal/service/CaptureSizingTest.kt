package com.mobilerun.portal.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSizingTest {
    private data class SizingCase(
        val name: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val expected: Pair<Int, Int>?,
    )

    @Test
    fun fitCaptureSizeToScreen_coversAspectFitAndRejectionCases() {
        val cases =
            listOf(
                // 1080x2408 is height-constrained; 574.09 rounds down to
                // even 574, height lands exactly at the 1280 box limit.
                SizingCase("portrait height-constrained", 1080, 2408, Pair(574, 1280)),
                SizingCase("landscape width-constrained", 2408, 1080, Pair(720, 322)),
                // Same aspect ratio as the legacy 720x1280 box.
                SizingCase("exact aspect match", 1080, 1920, Pair(720, 1280)),
                // 1080x2139 is height-constrained; scale*screenHeight
                // undershoots 1280.0 by a hair (1279.9999...), which used to
                // floor-even to 1278. The limiting axis (height) must land
                // on the box value exactly.
                SizingCase("height-limited axis is exact, not float undershoot", 1080, 2139, Pair(646, 1280)),
                // 3000x1000 fits to 720x240, but 240 is below the legacy
                // height minimum (256); clamping it up would distort the
                // aspect ratio, so the fit is rejected rather than blessed.
                SizingCase("extreme aspect rejected instead of distorted", 3000, 1000, null),
                // Screen smaller than the 720x1280 box in both dimensions:
                // scale is capped at 1.0 (200x200), but 200 is below the
                // legacy height minimum (256); clamping would change the
                // fitted height, so the fit is rejected instead of
                // returning a distorted 200x256.
                SizingCase("smaller than box rejected when clamp would distort", 200, 200, null),
                SizingCase("zero width returns null", 0, 100, null),
                SizingCase("negative height returns null", 100, -5, null),
            )

        for (case in cases) {
            val actual = CaptureSizing.fitCaptureSizeToScreen(case.screenWidth, case.screenHeight)
            assertEquals("${case.name}: fitCaptureSizeToScreen result", case.expected, actual)
        }
    }
}
