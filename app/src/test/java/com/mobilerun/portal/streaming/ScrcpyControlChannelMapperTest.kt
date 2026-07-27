package com.mobilerun.portal.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScrcpyControlChannelMapperTest {
    private data class MapperCase(
        val name: String,
        val x: Int,
        val y: Int,
        val videoW: Int,
        val videoH: Int,
        val screenW: Int,
        val screenH: Int,
        val expectNull: Boolean = false,
        val expectedX: Float? = null,
        val expectedY: Float? = null,
        val tolerance: Float = 0.01f,
    )

    @Test
    fun mapFrameToScreen_coversBarPointsAndEdgeCases() {
        val cases =
            listOf(
                // video 720x1280, screen 1080x2408: height-constrained fit
                // leaves a left/right bar of ~72.9px in frame coordinates.
                MapperCase(
                    name = "pillarbox bar point returns null",
                    x = 10, y = 640,
                    videoW = 720, videoH = 1280, screenW = 1080, screenH = 2408,
                    expectNull = true,
                ),
                MapperCase(
                    name = "pillarbox frame center maps to screen center",
                    x = 360, y = 640,
                    videoW = 720, videoH = 1280, screenW = 1080, screenH = 2408,
                    expectedX = 540f, expectedY = 1204f, tolerance = 1f,
                ),
                // video 720x1280, screen 720x1000: width-constrained fit
                // leaves a top/bottom bar of 140px in frame coordinates.
                MapperCase(
                    name = "letterbox bar point returns null",
                    x = 360, y = 50,
                    videoW = 720, videoH = 1280, screenW = 720, screenH = 1000,
                    expectNull = true,
                ),
                MapperCase(
                    name = "exact aspect match passes through unscaled",
                    x = 360, y = 640,
                    videoW = 720, videoH = 1280, screenW = 720, screenH = 1280,
                    expectedX = 360f, expectedY = 640f,
                ),
                MapperCase(
                    name = "x == videoW edge clamps to screenW - 1",
                    x = 720, y = 0,
                    videoW = 720, videoH = 1280, screenW = 720, screenH = 1280,
                    expectedX = 719f, expectedY = 0f,
                ),
                MapperCase(
                    name = "zero videoW returns null",
                    x = 0, y = 0,
                    videoW = 0, videoH = 1280, screenW = 720, screenH = 1280,
                    expectNull = true,
                ),
                MapperCase(
                    name = "zero videoH returns null",
                    x = 0, y = 0,
                    videoW = 720, videoH = 0, screenW = 720, screenH = 1280,
                    expectNull = true,
                ),
                MapperCase(
                    name = "negative screenW returns null",
                    x = 0, y = 0,
                    videoW = 720, videoH = 1280, screenW = -1, screenH = 1280,
                    expectNull = true,
                ),
                MapperCase(
                    name = "zero screenH returns null",
                    x = 0, y = 0,
                    videoW = 720, videoH = 1280, screenW = 720, screenH = 0,
                    expectNull = true,
                ),
                // Regression: video 720x1280 vs screen 1080x2408 is
                // height-limited, so offsetY must be exactly 0.0. Float math
                // previously computed offsetY as a tiny positive residual
                // (~0.000061), which pushed a legitimate y=0 edge tap outside
                // the bounds check and dropped it.
                MapperCase(
                    name = "flush y=0 on height-limited fit maps non-null",
                    x = 360, y = 0,
                    videoW = 720, videoH = 1280, screenW = 1080, screenH = 2408,
                ),
                // Landscape/width-limited analogue: video 1280x720 vs screen
                // 2408x1080 is width-limited, so offsetX must be exactly 0.0
                // and a legitimate x=0 edge tap must not be misclassified as
                // a bar point.
                MapperCase(
                    name = "flush x=0 on width-limited fit maps non-null",
                    x = 0, y = 360,
                    videoW = 1280, videoH = 720, screenW = 2408, screenH = 1080,
                ),
            )

        for (case in cases) {
            val mapped =
                ScrcpyControlChannel.mapFrameToScreen(
                    x = case.x,
                    y = case.y,
                    videoW = case.videoW,
                    videoH = case.videoH,
                    screenW = case.screenW,
                    screenH = case.screenH,
                )

            if (case.expectNull) {
                assertNull("${case.name}: expected null mapping", mapped)
            } else {
                assertNotNull("${case.name}: expected non-null mapping", mapped)
                requireNotNull(mapped)
                if (case.expectedX != null) {
                    assertEquals("${case.name}: screenX", case.expectedX, mapped.first, case.tolerance)
                }
                if (case.expectedY != null) {
                    assertEquals("${case.name}: screenY", case.expectedY, mapped.second, case.tolerance)
                }
            }
        }
    }
}
