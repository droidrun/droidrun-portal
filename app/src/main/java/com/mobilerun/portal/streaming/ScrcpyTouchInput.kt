package com.mobilerun.portal.streaming

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The web clients use scrcpy's touch packet shape, but encode the pointer id
 * in network byte order and the remaining numeric fields in little endian.
 */
internal data class ScrcpyTouchPacket(
    val action: Int,
    val pointerId: Long,
    val x: Int,
    val y: Int,
    val videoWidth: Int,
    val videoHeight: Int,
)

internal object ScrcpyTouchPacketDecoder {
    const val MESSAGE_TYPE = 2
    private const val PACKET_SIZE = 32

    fun decode(data: ByteArray): ScrcpyTouchPacket? {
        if (data.size < PACKET_SIZE || (data[0].toInt() and 0xFF) != MESSAGE_TYPE) {
            return null
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        val action = buffer.get().toInt() and 0xFF
        val pointerId = buffer.long

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val x = buffer.int
        val y = buffer.int
        val videoWidth = buffer.short.toInt() and 0xFFFF
        val videoHeight = buffer.short.toInt() and 0xFFFF

        return ScrcpyTouchPacket(
            action = action,
            pointerId = pointerId,
            x = x,
            y = y,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }
}

internal object ScrcpyTouchAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
}

internal data class ScreenPoint(
    val x: Float,
    val y: Float,
)

/**
 * Inverts MediaProjection's centered, uniform aspect-fit from the display
 * into the encoded frame. Points in letterbox/pillarbox bars have no display
 * coordinate and are rejected.
 */
internal object FrameToScreenMapper {
    fun map(
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): ScreenPoint? {
        if (videoWidth <= 0 || videoHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return null
        }

        val widthScaleProduct = videoWidth.toLong() * screenHeight.toLong()
        val heightScaleProduct = videoHeight.toLong() * screenWidth.toLong()

        val scale: Double
        val offsetX: Double
        val offsetY: Double
        when {
            widthScaleProduct == heightScaleProduct -> {
                scale = videoWidth.toDouble() / screenWidth.toDouble()
                offsetX = 0.0
                offsetY = 0.0
            }
            widthScaleProduct < heightScaleProduct -> {
                scale = videoWidth.toDouble() / screenWidth.toDouble()
                offsetX = 0.0
                offsetY = (videoHeight.toDouble() - screenHeight.toDouble() * scale) / 2.0
            }
            else -> {
                scale = videoHeight.toDouble() / screenHeight.toDouble()
                offsetX = (videoWidth.toDouble() - screenWidth.toDouble() * scale) / 2.0
                offsetY = 0.0
            }
        }

        val frameX = x.toDouble()
        val frameY = y.toDouble()
        if (frameX < offsetX ||
            frameX > videoWidth.toDouble() - offsetX ||
            frameY < offsetY ||
            frameY > videoHeight.toDouble() - offsetY
        ) {
            return null
        }

        return ScreenPoint(
            x = ((frameX - offsetX) / scale)
                .toFloat()
                .coerceIn(0f, (screenWidth - 1).toFloat()),
            y = ((frameY - offsetY) / scale)
                .toFloat()
                .coerceIn(0f, (screenHeight - 1).toFloat()),
        )
    }
}

internal sealed class TouchGestureUpdate {
    object Ignored : TouchGestureUpdate()

    data class Started(val point: ScreenPoint) : TouchGestureUpdate()

    data class Moved(val point: ScreenPoint) : TouchGestureUpdate()

    data class Finished(val point: ScreenPoint) : TouchGestureUpdate()

    object Cancelled : TouchGestureUpdate()
}

/**
 * Accessibility injection supports one stroke at a time. Latch its pointer id
 * so interleaved web pointer events cannot reset or splice that stroke.
 */
internal class SinglePointerGestureState {
    var activePointerId: Long? = null
        private set

    var lastValidPoint: ScreenPoint? = null
        private set

    fun consume(
        action: Int,
        pointerId: Long,
        mappedPoint: ScreenPoint?,
    ): TouchGestureUpdate =
        when (action) {
            ScrcpyTouchAction.DOWN -> consumeDown(pointerId, mappedPoint)
            ScrcpyTouchAction.MOVE -> {
                if (pointerId != activePointerId || mappedPoint == null) {
                    TouchGestureUpdate.Ignored
                } else {
                    lastValidPoint = mappedPoint
                    TouchGestureUpdate.Moved(mappedPoint)
                }
            }
            ScrcpyTouchAction.UP -> {
                if (pointerId != activePointerId) {
                    TouchGestureUpdate.Ignored
                } else {
                    val releasePoint = mappedPoint ?: lastValidPoint
                    clear()
                    if (releasePoint == null) {
                        TouchGestureUpdate.Cancelled
                    } else {
                        TouchGestureUpdate.Finished(releasePoint)
                    }
                }
            }
            ScrcpyTouchAction.CANCEL -> {
                if (pointerId != activePointerId) {
                    TouchGestureUpdate.Ignored
                } else {
                    clear()
                    TouchGestureUpdate.Cancelled
                }
            }
            else -> TouchGestureUpdate.Ignored
        }

    private fun consumeDown(
        pointerId: Long,
        mappedPoint: ScreenPoint?,
    ): TouchGestureUpdate {
        val currentPointerId = activePointerId
        if (currentPointerId != null && currentPointerId != pointerId) {
            return TouchGestureUpdate.Ignored
        }

        if (mappedPoint == null) {
            if (currentPointerId != null) {
                clear()
                return TouchGestureUpdate.Cancelled
            }
            return TouchGestureUpdate.Ignored
        }

        activePointerId = pointerId
        lastValidPoint = mappedPoint
        return TouchGestureUpdate.Started(mappedPoint)
    }

    private fun clear() {
        activePointerId = null
        lastValidPoint = null
    }
}
