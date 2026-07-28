package com.mobilerun.portal.streaming

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScrcpyTouchInputTest {
    @Test
    fun decoder_readsMixedEndianPointerAndPositionFields() {
        val packet =
            touchPacket(
                action = ScrcpyTouchAction.CANCEL,
                pointerId = 0x0102030405060708L,
                x = -123_456_789,
                y = 987_654_321,
                videoWidth = 65_535,
                videoHeight = 3_840,
            )

        assertEquals(
            ScrcpyTouchPacket(
                action = ScrcpyTouchAction.CANCEL,
                pointerId = 0x0102030405060708L,
                x = -123_456_789,
                y = 987_654_321,
                videoWidth = 65_535,
                videoHeight = 3_840,
            ),
            ScrcpyTouchPacketDecoder.decode(packet),
        )
    }

    @Test
    fun decoder_preservesMousePointerIdAndIgnoresTrailingFields() {
        val decoded =
            ScrcpyTouchPacketDecoder.decode(
                touchPacket(
                    action = ScrcpyTouchAction.DOWN,
                    pointerId = -1L,
                    x = 17,
                    y = 29,
                    videoWidth = 720,
                    videoHeight = 1_280,
                ),
            )

        assertNotNull(decoded)
        assertEquals(-1L, decoded?.pointerId)
    }

    @Test
    fun decoder_rejectsWrongMessageTypeAndTruncatedPackets() {
        val wrongType =
            touchPacket(
                action = ScrcpyTouchAction.DOWN,
                pointerId = 1L,
                x = 0,
                y = 0,
                videoWidth = 720,
                videoHeight = 1_280,
            ).also {
                it[0] = 3
            }

        assertNull(ScrcpyTouchPacketDecoder.decode(wrongType))
        assertNull(ScrcpyTouchPacketDecoder.decode(ByteArray(0)))
        assertNull(ScrcpyTouchPacketDecoder.decode(ByteArray(31)))
    }

    @Test
    fun mapper_handlesPillarboxContentBarsAndBoundaries() {
        val center =
            FrameToScreenMapper.map(
                x = 360,
                y = 640,
                videoWidth = 720,
                videoHeight = 1_280,
                screenWidth = 1_080,
                screenHeight = 2_400,
            )
        val topLeft =
            FrameToScreenMapper.map(
                x = 72,
                y = 0,
                videoWidth = 720,
                videoHeight = 1_280,
                screenWidth = 1_080,
                screenHeight = 2_400,
            )
        val bottomRight =
            FrameToScreenMapper.map(
                x = 648,
                y = 1_280,
                videoWidth = 720,
                videoHeight = 1_280,
                screenWidth = 1_080,
                screenHeight = 2_400,
            )

        assertPoint(center, 540f, 1_200f)
        assertPoint(topLeft, 0f, 0f)
        assertPoint(bottomRight, 1_079f, 2_399f)
        assertNull(
            FrameToScreenMapper.map(71, 640, 720, 1_280, 1_080, 2_400),
        )
        assertNull(
            FrameToScreenMapper.map(649, 640, 720, 1_280, 1_080, 2_400),
        )
    }

    @Test
    fun mapper_handlesApi29PillarboxGeometry() {
        assertPoint(
            FrameToScreenMapper.map(40, 0, 720, 1_280, 1_080, 2_160),
            expectedX = 0f,
            expectedY = 0f,
        )
        assertPoint(
            FrameToScreenMapper.map(680, 1_280, 720, 1_280, 1_080, 2_160),
            expectedX = 1_079f,
            expectedY = 2_159f,
        )
        assertNull(FrameToScreenMapper.map(39, 640, 720, 1_280, 1_080, 2_160))
    }

    @Test
    fun mapper_handlesLandscapeLetterboxGeometry() {
        assertPoint(
            FrameToScreenMapper.map(640, 72, 1_280, 720, 2_400, 1_080),
            expectedX = 1_200f,
            expectedY = 0f,
        )
        assertPoint(
            FrameToScreenMapper.map(1_280, 648, 1_280, 720, 2_400, 1_080),
            expectedX = 2_399f,
            expectedY = 1_079f,
        )
        assertNull(FrameToScreenMapper.map(640, 71, 1_280, 720, 2_400, 1_080))
        assertNull(FrameToScreenMapper.map(640, 649, 1_280, 720, 2_400, 1_080))
    }

    @Test
    fun mapper_exactAspectAndLimitingAxesHaveExactZeroOffset() {
        assertPoint(
            FrameToScreenMapper.map(0, 0, 720, 1_280, 1_080, 1_920),
            expectedX = 0f,
            expectedY = 0f,
        )
        assertNotNull(
            FrameToScreenMapper.map(360, 0, 720, 1_280, 1_080, 2_408),
        )
        assertNotNull(
            FrameToScreenMapper.map(0, 360, 1_280, 720, 2_408, 1_080),
        )
    }

    @Test
    fun mapper_clampsInclusiveFrameEdges() {
        assertPoint(
            FrameToScreenMapper.map(720, 1_280, 720, 1_280, 720, 1_280),
            expectedX = 719f,
            expectedY = 1_279f,
        )
    }

    @Test
    fun mapper_rejectsInvalidDimensionsAndOutOfFrameCoordinates() {
        val invalidDimensions =
            listOf(
                intArrayOf(0, 1_280, 720, 1_280),
                intArrayOf(720, 0, 720, 1_280),
                intArrayOf(720, 1_280, 0, 1_280),
                intArrayOf(720, 1_280, 720, 0),
                intArrayOf(-720, 1_280, 720, 1_280),
                intArrayOf(720, -1_280, 720, 1_280),
                intArrayOf(720, 1_280, -720, 1_280),
                intArrayOf(720, 1_280, 720, -1_280),
            )

        for ((videoWidth, videoHeight, screenWidth, screenHeight) in invalidDimensions) {
            assertNull(
                FrameToScreenMapper.map(
                    x = 0,
                    y = 0,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                ),
            )
        }

        assertNull(FrameToScreenMapper.map(-1, 0, 720, 1_280, 720, 1_280))
        assertNull(FrameToScreenMapper.map(0, -1, 720, 1_280, 720, 1_280))
        assertNull(FrameToScreenMapper.map(721, 0, 720, 1_280, 720, 1_280))
        assertNull(FrameToScreenMapper.map(0, 1_281, 720, 1_280, 720, 1_280))
    }

    @Test
    fun mapper_crossProductsDoNotOverflowForExtremePositiveDimensions() {
        assertNotNull(
            FrameToScreenMapper.map(
                x = 1_000_000_000,
                y = 1_000_000_000,
                videoWidth = Int.MAX_VALUE,
                videoHeight = Int.MAX_VALUE,
                screenWidth = Int.MAX_VALUE,
                screenHeight = Int.MAX_VALUE,
            ),
        )
    }

    @Test
    fun pointerState_tracksNormalGestureAndReleasesOwnership() {
        val state = SinglePointerGestureState()
        val down = ScreenPoint(10f, 20f)
        val move = ScreenPoint(30f, 40f)
        val up = ScreenPoint(50f, 60f)

        assertEquals(TouchGestureUpdate.Started(down), state.consume(0, 11L, down))
        assertEquals(11L, state.activePointerId)
        assertEquals(TouchGestureUpdate.Moved(move), state.consume(2, 11L, move))
        assertEquals(move, state.lastValidPoint)
        assertEquals(TouchGestureUpdate.Finished(up), state.consume(1, 11L, up))
        assertNull(state.activePointerId)
        assertNull(state.lastValidPoint)
    }

    @Test
    fun pointerState_ignoresForeignPointerWithoutCorruptingOwner() {
        val state = SinglePointerGestureState()
        val ownerDown = ScreenPoint(10f, 20f)
        val ownerMove = ScreenPoint(30f, 40f)
        val foreignPoint = ScreenPoint(100f, 200f)

        state.consume(ScrcpyTouchAction.DOWN, 1L, ownerDown)
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.DOWN, 2L, foreignPoint),
        )
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.MOVE, 2L, foreignPoint),
        )
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.UP, 2L, foreignPoint),
        )
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.CANCEL, 2L, null),
        )

        assertEquals(1L, state.activePointerId)
        assertEquals(
            TouchGestureUpdate.Moved(ownerMove),
            state.consume(ScrcpyTouchAction.MOVE, 1L, ownerMove),
        )
        assertEquals(
            TouchGestureUpdate.Finished(ownerMove),
            state.consume(ScrcpyTouchAction.UP, 1L, null),
        )
    }

    @Test
    fun pointerState_dropsBarDownAndSkipsBarMove() {
        val state = SinglePointerGestureState()
        val down = ScreenPoint(10f, 20f)

        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.DOWN, 1L, null),
        )
        assertNull(state.activePointerId)

        state.consume(ScrcpyTouchAction.DOWN, 1L, down)
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.MOVE, 1L, null),
        )
        assertEquals(down, state.lastValidPoint)
    }

    @Test
    fun pointerState_barUpReleasesAtLastValidPoint() {
        val state = SinglePointerGestureState()
        val lastInside = ScreenPoint(300f, 400f)

        state.consume(ScrcpyTouchAction.DOWN, 1L, ScreenPoint(10f, 20f))
        state.consume(ScrcpyTouchAction.MOVE, 1L, lastInside)

        assertEquals(
            TouchGestureUpdate.Finished(lastInside),
            state.consume(ScrcpyTouchAction.UP, 1L, null),
        )
        assertNull(state.activePointerId)
    }

    @Test
    fun pointerState_matchingCancelClearsGestureAndAllowsNewDown() {
        val state = SinglePointerGestureState()
        val first = ScreenPoint(10f, 20f)
        val second = ScreenPoint(30f, 40f)

        state.consume(ScrcpyTouchAction.DOWN, 1L, first)
        assertSame(
            TouchGestureUpdate.Cancelled,
            state.consume(ScrcpyTouchAction.CANCEL, 1L, null),
        )
        assertNull(state.activePointerId)
        assertEquals(
            TouchGestureUpdate.Started(second),
            state.consume(ScrcpyTouchAction.DOWN, 2L, second),
        )
    }

    @Test
    fun pointerState_samePointerDownRestartsOrCancelsItsGesture() {
        val state = SinglePointerGestureState()
        val first = ScreenPoint(10f, 20f)
        val replacement = ScreenPoint(30f, 40f)

        state.consume(ScrcpyTouchAction.DOWN, 1L, first)
        assertEquals(
            TouchGestureUpdate.Started(replacement),
            state.consume(ScrcpyTouchAction.DOWN, 1L, replacement),
        )
        assertEquals(replacement, state.lastValidPoint)
        assertSame(
            TouchGestureUpdate.Cancelled,
            state.consume(ScrcpyTouchAction.DOWN, 1L, null),
        )
        assertNull(state.activePointerId)
    }

    @Test
    fun pointerState_ignoresOrphanAndUnknownEvents() {
        val state = SinglePointerGestureState()
        val point = ScreenPoint(10f, 20f)

        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.MOVE, 1L, point),
        )
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.UP, 1L, point),
        )
        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.CANCEL, 1L, null),
        )
        assertSame(TouchGestureUpdate.Ignored, state.consume(99, 1L, point))
    }

    @Test
    fun invalidFrameDimensionsNeverLatchPointer() {
        val state = SinglePointerGestureState()
        val mapped =
            FrameToScreenMapper.map(
                x = 10,
                y = 20,
                videoWidth = 0,
                videoHeight = 1_280,
                screenWidth = 720,
                screenHeight = 1_280,
            )

        assertSame(
            TouchGestureUpdate.Ignored,
            state.consume(ScrcpyTouchAction.DOWN, 1L, mapped),
        )
        assertNull(state.activePointerId)
    }

    private fun assertPoint(
        actual: ScreenPoint?,
        expectedX: Float,
        expectedY: Float,
        tolerance: Float = 0.01f,
    ) {
        assertNotNull(actual)
        requireNotNull(actual)
        assertEquals(expectedX, actual.x, tolerance)
        assertEquals(expectedY, actual.y, tolerance)
    }

    private fun touchPacket(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(32)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(ScrcpyTouchPacketDecoder.MESSAGE_TYPE.toByte())
        buffer.put(action.toByte())
        buffer.putLong(pointerId)

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(0xFFFF.toShort())
        buffer.putInt(0x01020304)
        buffer.putInt(0x05060708)
        return buffer.array()
    }
}
