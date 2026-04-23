package com.mobilerun.portal.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import com.mobilerun.portal.service.ReverseConnectionService
import com.mobilerun.portal.service.ScreenCaptureService
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import org.json.JSONObject
import org.webrtc.*

/**
 * Manages WebRTC PeerConnection, Signaling, and Stream lifecycle. Singleton to ensure only one
 * active stream manager exists.
 */
class WebRtcManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val H264_CODEC_NAME = "H264/90000"
        private const val STATS_INTERVAL_MS = 5000L
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val SCREENSHOT_CAPTURE_IDLE_TIMEOUT_MS = 60_000L
        private const val KEEP_ALIVE_TIMEOUT_MS = 30_000L
        private const val KEYFRAME_REQUEST_MIN_INTERVAL_MS = 250L
        private const val MAX_OUTGOING_ICE_CANDIDATES = 50
        private const val MAX_ICE_RESTARTS = 1
        private const val MAX_CONCURRENT_SESSIONS = 3
        private const val FRAME_TAP_TIMEOUT_MS = 2_000L

        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager {
            return instance
                ?: synchronized(this) {
                    instance ?: WebRtcManager(context.applicationContext).also { instance = it }
                }
        }

        fun getExistingInstance(): WebRtcManager? = instance

        fun shutdown() {
            synchronized(this) {
                instance?.releaseResources()
                instance = null
            }
        }

        internal fun maybeSnapshotPendingPrimarySignalingForReset(
            sessionId: String?,
            pendingSessionId: String?,
            offer: SessionDescription?,
            ice: List<IceCandidate>,
            hasLiveNegotiatedPeer: Boolean,
        ): PendingPrimarySignalingSnapshot? {
            val normalizedSessionId = sessionId?.trim().orEmpty()
            if (normalizedSessionId.isEmpty()) return null
            if (pendingSessionId != normalizedSessionId) return null
            if (hasLiveNegotiatedPeer) return null
            if (offer == null && ice.isEmpty()) return null
            return PendingPrimarySignalingSnapshot(
                sessionId = normalizedSessionId,
                offer = offer,
                ice = ice.toList(),
            )
        }

        internal fun hasQueuedPrimaryOfferForSession(
            sessionId: String,
            pendingSessionId: String?,
            offer: SessionDescription?,
        ): Boolean = pendingSessionId == sessionId && offer != null

        internal fun isPeerHealthyForLiveness(
            iceState: PeerConnection.IceConnectionState?,
            controlState: DataChannel.State?,
        ): Boolean {
            return iceState == PeerConnection.IceConnectionState.CONNECTED ||
                    iceState == PeerConnection.IceConnectionState.COMPLETED ||
                    controlState == DataChannel.State.OPEN
        }

        internal fun evaluateSessionLivenessTimeout(peerHealthy: Boolean): SessionLivenessTimeoutDecision =
            SessionLivenessTimeoutDecision(shouldStop = !peerHealthy)

        internal fun lastSessionCaptureAction(
            reason: String,
            captureActive: Boolean,
        ): LastSessionCaptureAction {
            if (!captureActive) return LastSessionCaptureAction.NONE
            return when (reason) {
                // Keep capture alive until idle timeout so a later cloud signal can reuse it.
                "keep_alive_timeout" -> LastSessionCaptureAction.SCHEDULE_IDLE_STOP
                else -> LastSessionCaptureAction.SCHEDULE_IDLE_STOP
            }
        }

        internal fun resolveIncomingSessionRoute(
            currentPrimarySessionId: String?,
            incomingSessionId: String,
            primaryHasPeerResources: Boolean,
            primaryLastLivenessAtMs: Long?,
            nowMs: Long,
            livenessStaleAfterMs: Long,
        ): IncomingSessionRoute {
            if (currentPrimarySessionId == null || currentPrimarySessionId == incomingSessionId) {
                return IncomingSessionRoute.PRIMARY
            }
            if (!primaryHasPeerResources) {
                return IncomingSessionRoute.TAKEOVER_STALE_PRIMARY
            }
            if (
                primaryLastLivenessAtMs == null ||
                nowMs - primaryLastLivenessAtMs >= livenessStaleAfterMs
            ) {
                return IncomingSessionRoute.TAKEOVER_STALE_PRIMARY
            }
            return IncomingSessionRoute.SECONDARY
        }

        internal enum class KeyframeRequestScheduleDisposition {
            SKIP_NO_STREAM,
            COALESCE,
            SCHEDULE,
        }

        internal data class KeyframeRequestScheduleDecision(
            val disposition: KeyframeRequestScheduleDisposition,
            val delayMs: Long,
            val replacesPending: Boolean,
        )

        internal fun planKeyframeRequestSchedule(
            streamActive: Boolean,
            pendingGeneration: Int?,
            pendingSessionId: String?,
            currentGeneration: Int,
            requestedSessionId: String,
            nowMs: Long,
            lastKeyframeRequestStartedAtMs: Long,
            minIntervalMs: Long,
        ): KeyframeRequestScheduleDecision {
            if (!streamActive) {
                return KeyframeRequestScheduleDecision(
                    disposition = KeyframeRequestScheduleDisposition.SKIP_NO_STREAM,
                    delayMs = 0L,
                    replacesPending = false,
                )
            }
            if (
                pendingGeneration == currentGeneration &&
                pendingSessionId == requestedSessionId
            ) {
                return KeyframeRequestScheduleDecision(
                    disposition = KeyframeRequestScheduleDisposition.COALESCE,
                    delayMs = 0L,
                    replacesPending = false,
                )
            }
            val sinceLastRequestMs = (nowMs - lastKeyframeRequestStartedAtMs).coerceAtLeast(0L)
            return KeyframeRequestScheduleDecision(
                disposition = KeyframeRequestScheduleDisposition.SCHEDULE,
                delayMs = (minIntervalMs - sinceLastRequestMs).coerceAtLeast(0L),
                replacesPending = pendingGeneration != null && pendingSessionId != null,
            )
        }

        internal enum class KeyframeExecutionDisposition {
            EXECUTE,
            SKIP_REPLACED,
            SKIP_STALE,
        }

        internal fun planKeyframeExecution(
            pendingGeneration: Int?,
            pendingSessionId: String?,
            expectedGeneration: Int,
            expectedSessionId: String,
            activeGeneration: Int,
            trackedSession: Boolean,
            streamActive: Boolean,
        ): KeyframeExecutionDisposition {
            if (
                pendingGeneration != expectedGeneration ||
                pendingSessionId != expectedSessionId
            ) {
                return KeyframeExecutionDisposition.SKIP_REPLACED
            }
            if (!streamActive || !trackedSession || activeGeneration != expectedGeneration) {
                return KeyframeExecutionDisposition.SKIP_STALE
            }
            return KeyframeExecutionDisposition.EXECUTE
        }

        internal data class KeyframeTargetAction(
            val label: String,
            val action: () -> Unit,
        )

        internal data class KeyframeExecutionSummary(
            val attempted: Int,
            val succeeded: Int,
            val failed: Int,
            val failedLabels: List<String>,
        )

        internal fun executeKeyframeTargetActions(
            targets: List<KeyframeTargetAction>,
        ): KeyframeExecutionSummary {
            var succeeded = 0
            var failed = 0
            val failedLabels = mutableListOf<String>()
            targets.forEach { target ->
                try {
                    target.action()
                    succeeded += 1
                } catch (t: Throwable) {
                    failed += 1
                    failedLabels += target.label
                }
            }
            return KeyframeExecutionSummary(
                attempted = targets.size,
                succeeded = succeeded,
                failed = failed,
                failedLabels = failedLabels,
            )
        }

        internal fun buildCpuFrameSnapshot(frame: VideoFrame): CapturedFrameSnapshot? {
            val i420 = frame.buffer.toI420() ?: return null
            return try {
                CapturedFrameSnapshot(
                    width = i420.width,
                    height = i420.height,
                    rotation = frame.rotation,
                    nv21 = copyI420ToNv21(i420, i420.width, i420.height),
                )
            } finally {
                i420.release()
            }
        }

        internal fun completeFrameCaptureWaiters(
            waiters: List<CompletableFuture<String>>,
            result: String,
        ): Int {
            var completed = 0
            waiters.forEach { waiter ->
                if (waiter.complete(result)) {
                    completed += 1
                }
            }
            return completed
        }

        internal fun failFrameCaptureWaiters(
            waiters: List<CompletableFuture<String>>,
            reason: String,
        ): Int = completeFrameCaptureWaiters(waiters, "error: $reason")

        internal fun nextZeroSentStatsIntervalCount(
            currentCount: Int,
            iceState: PeerConnection.IceConnectionState?,
            framesSent: Long?,
        ): Int {
            val connected =
                iceState == PeerConnection.IceConnectionState.CONNECTED ||
                        iceState == PeerConnection.IceConnectionState.COMPLETED
            if (!connected || framesSent != 0L) return 0
            return currentCount + 1
        }

        internal fun shouldWarnForZeroSentStats(intervalCount: Int): Boolean = intervalCount == 2

        internal fun shouldArmCaptureOnlyIdleStop(
            usedSharedCaptureSession: Boolean,
            captureSessionMode: CaptureSessionMode,
            captureActive: Boolean,
            streamActive: Boolean,
        ): Boolean {
            return usedSharedCaptureSession &&
                    captureSessionMode == CaptureSessionMode.CAPTURE_ONLY &&
                    captureActive &&
                    !streamActive
        }

        internal data class CaptureFastState(
            val captureActive: Boolean,
            val reusableFrameSource: Boolean,
            val captureSessionMode: CaptureSessionMode,
            val generation: Int,
        )

        internal data class CaptureFrameRequestPlan(
            val reusableCaptureAvailable: Boolean,
            val shouldCancelIdleStop: Boolean,
        )

        internal fun buildCaptureFastState(
            captureActive: Boolean,
            videoTrackReady: Boolean,
            captureSessionMode: CaptureSessionMode,
            generation: Int,
        ): CaptureFastState {
            if (!captureActive) {
                return CaptureFastState(
                    captureActive = false,
                    reusableFrameSource = false,
                    captureSessionMode = CaptureSessionMode.NONE,
                    generation = 0,
                )
            }
            return CaptureFastState(
                captureActive = true,
                reusableFrameSource = videoTrackReady,
                captureSessionMode = captureSessionMode,
                generation = generation,
            )
        }

        internal fun planCaptureFrameRequest(
            captureFastState: CaptureFastState,
        ): CaptureFrameRequestPlan {
            return CaptureFrameRequestPlan(
                reusableCaptureAvailable = captureFastState.reusableFrameSource,
                shouldCancelIdleStop =
                    captureFastState.reusableFrameSource &&
                            captureFastState.captureSessionMode == CaptureSessionMode.CAPTURE_ONLY,
            )
        }

        private fun copyI420ToNv21(
            i420: VideoFrame.I420Buffer,
            width: Int,
            height: Int,
        ): ByteArray {
            val nv21 = ByteArray(width * height * 3 / 2)

            val yBuf = i420.dataY
            val strideY = i420.strideY
            for (row in 0 until height) {
                yBuf.position(row * strideY)
                yBuf.get(nv21, row * width, width)
            }

            val uBuf = i420.dataU
            val vBuf = i420.dataV
            val strideU = i420.strideU
            val strideV = i420.strideV
            val chromaH = height / 2
            val chromaW = width / 2
            val uvStart = width * height
            for (row in 0 until chromaH) {
                val dstBase = uvStart + row * width
                val srcBaseU = row * strideU
                val srcBaseV = row * strideV
                for (col in 0 until chromaW) {
                    nv21[dstBase + col * 2] = vBuf.get(srcBaseV + col)
                    nv21[dstBase + col * 2 + 1] = uBuf.get(srcBaseU + col)
                }
            }
            return nv21
        }
    }

    private data class PeerResources(
        val peerConnection: PeerConnection?,
        val controlChannel: DataChannel?
    )

    private data class PrimaryPeerResources(
        val peerConnection: PeerConnection,
        val controlChannel: DataChannel?,
        val scrcpyControlHandler: ScrcpyControlChannel?,
    )

    private data class StreamResources(
        val screenCapturer: VideoCapturer?,
        val videoSource: VideoSource?,
        val videoTrack: VideoTrack?,
        val surfaceTextureHelper: SurfaceTextureHelper?,
        val peerConnection: PeerConnection?,
        val controlChannel: DataChannel?
    )

    private data class PendingStreamStop(
        val reason: String?,
        val sessionId: Any?,
    )

    private data class PendingFrameCapture(
        val future: CompletableFuture<String>,
        val timeoutRunnable: Runnable,
    )

    internal data class CapturedFrameSnapshot(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val nv21: ByteArray,
    )

    internal data class SessionLivenessTimeoutDecision(
        val shouldStop: Boolean,
    )

    private data class SessionLivenessState(
        var lastLivenessAtMs: Long,
        var iceState: PeerConnection.IceConnectionState? = null,
        var controlState: DataChannel.State? = null,
        var runnable: Runnable? = null,
    )

    private data class PendingKeyframeRequest(
        val sessionId: String,
        val generation: Int,
        val runnable: Runnable,
    )

    internal data class PendingPrimarySignalingSnapshot(
        val sessionId: String,
        val offer: SessionDescription?,
        val ice: List<IceCandidate>,
    )

    internal enum class LastSessionCaptureAction {
        NONE,
        SCHEDULE_IDLE_STOP,
    }

    internal enum class IncomingSessionRoute {
        PRIMARY,
        SECONDARY,
        TAKEOVER_STALE_PRIMARY,
    }

    internal enum class CaptureSessionMode {
        NONE,
        STREAM,
        CAPTURE_ONLY,
    }

    private data class SecondarySession(
        val sessionId: String,
        val peerConnection: PeerConnection,
        val controlChannel: DataChannel?,
        val controlHandler: ScrcpyControlChannel?,
        val pendingIceCandidates: ConcurrentLinkedQueue<IceCandidate> = ConcurrentLinkedQueue(),
        val pendingOutgoingIceCandidates: ConcurrentLinkedQueue<IceCandidate> = ConcurrentLinkedQueue(),
        var canSendOutgoingIceCandidates: Boolean = false,
        var isRemoteDescriptionSet: Boolean = false,
        var waitingForOffer: Boolean = false,
        var iceRestartAttempts: Int = 0,
        var streamId: Int = 0,
    )

    private var peerConnectionFactory: PeerConnectionFactory? = null

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var primarySessionId: String? = null
    private val secondarySessions = LinkedHashMap<String, SecondarySession>()
    private val eglBase: EglBase by lazy { EglBase.create() }

    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    @Volatile
    private var reverseConnectionService: ReverseConnectionService? = null
    private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
    private val pendingOutgoingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
    private var canSendOutgoingIceCandidates = false

    @Volatile
    private var isRemoteDescriptionSet = false
    private var iceRestartAttempts = 0
    private var waitingForOffer = false
    private val outgoingMessageId = AtomicInteger(0)

    // for all peerConnection lifecycle operations
    private val streamLock = Any()

    private val stopThread = HandlerThread("WebRtcStop").apply { start() }
    private val stopHandler = Handler(stopThread.looper)
    private val keyframeThread = HandlerThread("WebRtcKeyframe").apply { start() }
    private val keyframeHandler = Handler(keyframeThread.looper)
    private val statsThread = HandlerThread("WebRtcStats").apply { start() }
    private val statsHandler = Handler(statsThread.looper)
    private val frameTapThread = HandlerThread("WebRtcFrameTap").apply { start() }
    private val frameTapHandler = Handler(frameTapThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamGeneration = AtomicInteger(0)
    private val frameTapLock = Any()

    @Volatile
    private var streamRequestId: String? = null

    @Volatile
    private var pendingIceServers: List<PeerConnection.IceServer>? = null
    private var pendingPrimaryOffer: SessionDescription? = null
    private var pendingPrimaryOfferSessionId: String? = null
    private val pendingPrimaryIceBeforePeer = mutableListOf<IceCandidate>()
    private val requestFrameLoggedSessions = mutableSetOf<String>()
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureFps = 0
    private var connectionToastStreamId = 0
    private var statsRunnable: Runnable? = null
    private var lastStatsBytesSent: Long? = null
    private var lastStatsTimestampMs: Long? = null
    private var consecutiveZeroSentStatsIntervals = 0
    private var captureLogStreamId = 0
    private var firstCaptureFrameLoggedStreamId = 0
    private var captureSessionMode = CaptureSessionMode.NONE
    @Volatile
    private var captureFastState =
        buildCaptureFastState(
            captureActive = false,
            videoTrackReady = false,
            captureSessionMode = CaptureSessionMode.NONE,
            generation = 0,
        )

    @Volatile
    private var controlChannel: DataChannel? = null
    private var scrcpyControlHandler: ScrcpyControlChannel? = null
    private var idleStopRunnable: Runnable? = null

    @Volatile
    private var pendingStreamStop: PendingStreamStop? = null
    private val sessionLivenessStates = mutableMapOf<String, SessionLivenessState>()
    private var pendingKeyframeRequest: PendingKeyframeRequest? = null
    private var lastKeyframeRequestStartedAtMs = 0L
    private val pendingFrameCaptures = mutableListOf<PendingFrameCapture>()

    init {
        initializePeerConnectionFactory()
    }

    fun setReverseConnectionService(service: ReverseConnectionService) {
        this.reverseConnectionService = service
    }

    fun onReverseConnectionOpen() {
        flushPendingStreamStopped()
    }

    fun setStreamRequestId(requestId: String?) {
        streamRequestId = requestId
    }

    fun getStreamRequestId(): String? = streamRequestId

    fun setPendingIceServers(servers: List<PeerConnection.IceServer>?) {
        pendingIceServers = servers
    }

    fun consumePendingIceServers(): List<PeerConnection.IceServer>? {
        val servers = pendingIceServers
        pendingIceServers = null
        return servers
    }

    fun isStreamActive(): Boolean =
        synchronized(streamLock) { peerConnection != null || secondarySessions.isNotEmpty() }

    fun hasReusableCaptureFrameSource(): Boolean = captureFastState.reusableFrameSource

    internal fun getCaptureFastStateSnapshot(): CaptureFastState = captureFastState

    /**
     * Tap the next frame from the active screen-capture VideoTrack and return it
     * as a base64-encoded PNG. Used as the screenshot source on pre-API-30 devices
     * while an existing MediaProjection capture is already running. This covers
     * both actively streaming sessions and reconnect windows where capture is
     * still alive but the peer connection has already dropped.
     */
    fun captureStreamFrame(): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val captureState = getCaptureFastStateSnapshot()
        val requestPlan = planCaptureFrameRequest(captureState)
        Log.d(
            TAG,
            "captureStreamFrame: reusable=${requestPlan.reusableCaptureAvailable} mode=${captureState.captureSessionMode} gen=${captureState.generation}",
        )
        if (!requestPlan.reusableCaptureAvailable) {
            Log.w(TAG, "captureStreamFrame: no reusable shared capture is available")
            future.complete("error: no_active_capture")
            return future
        }
        if (requestPlan.shouldCancelIdleStop) {
            cancelIdleStop()
        }

        val pending =
            PendingFrameCapture(
                future = future,
                timeoutRunnable = Runnable {
                    val removed =
                        synchronized(frameTapLock) {
                            pendingFrameCaptures.removeAll { it.future === future }
                        }
                    if (removed) {
                        val timeoutState = getCaptureFastStateSnapshot()
                        Log.w(
                            TAG,
                            "Screenshot waiter timed out waiting for next shared capture frame (reusable=${timeoutState.reusableFrameSource}, mode=${timeoutState.captureSessionMode}, gen=${timeoutState.generation})",
                        )
                        future.complete("error: frame_timeout")
                    }
                },
            )
        val pendingCount =
            synchronized(frameTapLock) {
                pendingFrameCaptures.add(pending)
                pendingFrameCaptures.size
            }
        Log.d(TAG, "Armed screenshot waiter for next shared capture frame (pending=$pendingCount)")
        frameTapHandler.postDelayed(pending.timeoutRunnable, FRAME_TAP_TIMEOUT_MS)
        return future
    }

    private fun encodeSnapshotAsync(
        snapshot: CapturedFrameSnapshot,
        waiters: List<PendingFrameCapture>,
    ) {
        frameTapHandler.post {
            val waiterFutures = waiters.map { it.future }
            try {
                val result = encodeFrameSnapshot(snapshot)
                val completed = completeFrameCaptureWaiters(waiterFutures, result)
                if (result.startsWith("error:")) {
                    Log.w(
                        TAG,
                        "Failed completing $completed screenshot waiters from shared capture: ${
                            result.removePrefix(
                                "error: "
                            )
                        }",
                    )
                } else {
                    Log.d(
                        TAG,
                        "Completed $completed screenshot waiters from next shared capture frame"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "frame snapshot encode failed", e)
                val completed =
                    failFrameCaptureWaiters(
                        waiterFutures,
                        e.message ?: "frame_encode_failed",
                    )
                Log.w(
                    TAG,
                    "Failed $completed screenshot waiters due to frame snapshot encode error"
                )
            }
        }
    }

    private fun onCaptureFrameObserved(frame: VideoFrame) {
        logFirstCaptureFrameIfNeeded()

        val waiters =
            synchronized(frameTapLock) {
                if (pendingFrameCaptures.isEmpty()) {
                    emptyList()
                } else {
                    ArrayList(pendingFrameCaptures).also { pendingFrameCaptures.clear() }
                }
            }
        if (waiters.isEmpty()) {
            return
        }
        waiters.forEach { pending ->
            frameTapHandler.removeCallbacks(pending.timeoutRunnable)
        }

        val snapshot =
            try {
                buildCpuFrameSnapshot(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build CPU-owned frame snapshot", e)
                null
            }
        if (snapshot == null) {
            val failed = failFrameCaptureWaiters(waiters.map { it.future }, "frame_snapshot_failed")
            Log.w(
                TAG,
                "Failed $failed screenshot waiters because the capture frame could not be cloned"
            )
            return
        }

        Log.d(TAG, "Cloned next shared capture frame for ${waiters.size} screenshot waiters")
        encodeSnapshotAsync(snapshot, waiters)
    }

    private fun clearCaptureFrameTapState(reason: String) {
        val waiters: List<PendingFrameCapture>
        synchronized(frameTapLock) {
            waiters = ArrayList(pendingFrameCaptures)
            pendingFrameCaptures.clear()
        }
        waiters.forEach { pending ->
            frameTapHandler.removeCallbacks(pending.timeoutRunnable)
        }
        val failed = failFrameCaptureWaiters(waiters.map { it.future }, reason)
        Log.d(TAG, "Capture cleanup: reason=$reason failedWaiters=$failed")
    }

    private fun logFirstCaptureFrameIfNeeded() {
        val streamId =
            synchronized(streamLock) {
                captureLogStreamId
            }
        if (streamId == 0) return
        val shouldLog =
            synchronized(streamLock) {
                if (firstCaptureFrameLoggedStreamId == streamId) {
                    false
                } else {
                    firstCaptureFrameLoggedStreamId = streamId
                    true
                }
            }
        if (shouldLog) {
            Log.i(TAG, "First capture frame observed for streamId=$streamId")
        }
    }

    private fun createCaptureFrameObserver(delegate: CapturerObserver): CapturerObserver {
        return object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                delegate.onCapturerStarted(success)
            }

            override fun onCapturerStopped() {
                clearCaptureFrameTapState("capture_stopped")
                delegate.onCapturerStopped()
            }

            override fun onFrameCaptured(frame: VideoFrame) {
                delegate.onFrameCaptured(frame)
                onCaptureFrameObserved(frame)
            }
        }
    }

    private fun encodeFrameSnapshot(snapshot: CapturedFrameSnapshot): String {
        val yuv =
            YuvImage(
                snapshot.nv21,
                ImageFormat.NV21,
                snapshot.width,
                snapshot.height,
                null,
            )
        val jpeg = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, snapshot.width, snapshot.height), 92, jpeg)) {
            jpeg.close()
            return "error: jpeg_encode_failed"
        }
        val jpegBytes = jpeg.toByteArray()
        jpeg.close()

        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return "error: jpeg_decode_failed"
        val oriented =
            if (snapshot.rotation == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(snapshot.rotation.toFloat()) }
                val rotated =
                    Bitmap.createBitmap(
                        decoded,
                        0,
                        0,
                        decoded.width,
                        decoded.height,
                        matrix,
                        false,
                    )
                if (rotated !== decoded) {
                    decoded.recycle()
                }
                rotated
            }

        val png = ByteArrayOutputStream()
        val ok = oriented.compress(Bitmap.CompressFormat.PNG, 100, png)
        oriented.recycle()
        if (!ok) {
            png.close()
            return "error: png_encode_failed"
        }
        val bytes = png.toByteArray()
        png.close()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun isCurrentSession(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        return synchronized(streamLock) {
            primarySessionId == sessionId ||
                    streamRequestId == sessionId ||
                    secondarySessions.containsKey(sessionId)
        }
    }

    fun getActiveSessionIds(): List<String> =
        synchronized(streamLock) {
            buildList {
                primarySessionId?.let { add(it) }
                addAll(secondarySessions.keys)
            }
        }

    fun isCaptureActive(): Boolean = captureFastState.captureActive

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        peerConnectionFactory =
            run {
                val encoderFactory =
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                val filteredEncoderFactory =
                    FilteringVideoEncoderFactory(
                        encoderFactory,
                        setOf("H264", "VP8"),
                    )
                Log.i(TAG, "Enabled video encoders: ${filteredEncoderFactory.codecListLabel()}")
                PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .setVideoEncoderFactory(filteredEncoderFactory)
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()
            }
    }

    fun startStream(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
        iceServers: List<PeerConnection.IceServer>? = null,
        waitForOffer: Boolean = false
    ) {
        val sessionId =
            streamRequestId?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing required param: 'sessionId'")
        startStream(
            permissionResultData = permissionResultData,
            width = width,
            height = height,
            fps = fps,
            sessionId = sessionId,
            iceServers = iceServers,
            waitForOffer = waitForOffer,
        )
    }

    fun startStream(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
        sessionId: String,
        iceServers: List<PeerConnection.IceServer>? = null,
        waitForOffer: Boolean = false
    ) {
        require(sessionId.isNotBlank()) { "Missing required param: 'sessionId'" }
        cancelIdleStop()
        Log.i(
            TAG,
            "Starting WebRTC Stream: session=$sessionId ${width}x${height} @ $fps fps, waitForOffer=$waitForOffer"
        )
        val effectiveIceServers = iceServers ?: consumePendingIceServers()
        var takeoverPrimarySessionId: String? = null
        val route =
            synchronized(streamLock) {
                val resolvedRoute = resolveIncomingSessionRouteLocked(sessionId)
                when (resolvedRoute) {
                    IncomingSessionRoute.TAKEOVER_STALE_PRIMARY -> {
                        takeoverPrimarySessionId = clearPrimarySessionForTakeoverLocked()
                        IncomingSessionRoute.PRIMARY
                    }

                    else -> resolvedRoute
                }
            }
        if (route == IncomingSessionRoute.SECONDARY) {
            startSecondaryStreamWithExistingCapture(
                sessionId = sessionId,
                width = width,
                height = height,
                fps = fps,
                customIceServers = effectiveIceServers,
                waitForOffer = waitForOffer,
            )
            return
        }
        val streamId = streamGeneration.incrementAndGet()
        val staleResources =
            synchronized(streamLock) {
                val pendingPrimarySignaling =
                    snapshotPendingPrimarySignalingForResetLocked(
                        sessionId = sessionId,
                        reason = "start_stream",
                    )
                detachStreamResourcesLocked().also {
                    restorePendingPrimarySignalingAfterResetLocked(
                        snapshot = pendingPrimarySignaling,
                        reason = "start_stream",
                    )
                }
            }
        cleanupStreamResources(staleResources)
        synchronized(streamLock) {
            primarySessionId = sessionId
            streamRequestId = sessionId
            waitingForOffer = waitForOffer
            iceRestartAttempts = 0
            captureSessionMode = CaptureSessionMode.STREAM
            refreshCaptureFastStateLocked()
            primePrimarySessionLivenessLocked(sessionId, SystemClock.elapsedRealtime())
        }
        val newResources = createPeerConnection(effectiveIceServers, streamId, sessionId)
        val activeTrack =
            createVideoTrack(permissionResultData, width, height, fps, streamId) ?: return
        val sendReadyFor: String?
        val shouldCreateOffer: Boolean
        val connectionForSender: PeerConnection?
        synchronized(streamLock) {
            if (streamGeneration.get() != streamId) {
                newResources?.let {
                    cleanupPeerResources(PeerResources(it.peerConnection, it.controlChannel))
                }
                return
            }
            peerConnection = newResources?.peerConnection
            controlChannel = newResources?.controlChannel
            scrcpyControlHandler = newResources?.scrcpyControlHandler
            connectionForSender = peerConnection
            if (!waitForOffer) {
                shouldCreateOffer = true
                sendReadyFor = null
            } else {
                shouldCreateOffer = false
                if (!hasQueuedPrimaryOfferLocked(sessionId)) {
                    sendReadyFor = sessionId
                } else {
                    sendReadyFor = null
                    Log.i(
                        TAG,
                        "Skipping stream/ready because a primary offer is already queued (sessionId=$sessionId)",
                    )
                }
            }
        }
        try {
            if (isCurrentStream(streamId)) {
                val sender = connectionForSender?.addTrack(activeTrack, listOf(VIDEO_TRACK_ID))
                configureVideoSender(sender, width, height, fps)
                startStatsLogging(streamId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed attaching primary video sender", e)
            stopStream()
            throw e
        }
        if (shouldCreateOffer) createOffer(streamId)
        sendReadyFor?.let { sendStreamReady(it) }
        takeoverPrimarySessionId?.let { notifyStreamStoppedAsync(sessionId = it) }
        flushPendingPrimarySignaling(sessionId)
    }

    fun startSharedCapture(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
    ) {
        cancelIdleStop()
        if (hasReusableCaptureFrameSource()) {
            updateCaptureFormat(width, height, fps)
            return
        }

        val streamId = streamGeneration.incrementAndGet()
        val staleResources = synchronized(streamLock) { detachStreamResourcesLocked() }
        cleanupStreamResources(staleResources)
        synchronized(streamLock) {
            captureSessionMode = CaptureSessionMode.CAPTURE_ONLY
            refreshCaptureFastStateLocked()
        }
        createVideoTrack(permissionResultData, width, height, fps, streamId)
    }

    fun startStreamWithExistingCapture(
        width: Int,
        height: Int,
        fps: Int,
        waitForOffer: Boolean = false
    ) {
        val sessionId =
            streamRequestId?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing required param: 'sessionId'")
        startStreamWithExistingCapture(
            width = width,
            height = height,
            fps = fps,
            sessionId = sessionId,
            waitForOffer = waitForOffer,
        )
    }

    fun startStreamWithExistingCapture(
        width: Int,
        height: Int,
        fps: Int,
        sessionId: String,
        waitForOffer: Boolean = false
    ) {
        require(sessionId.isNotBlank()) { "Missing required param: 'sessionId'" }
        cancelIdleStop()
        val effectiveIceServers = consumePendingIceServers()
        var takeoverPrimarySessionId: String? = null
        val route =
            synchronized(streamLock) {
                val resolvedRoute = resolveIncomingSessionRouteLocked(sessionId)
                when (resolvedRoute) {
                    IncomingSessionRoute.TAKEOVER_STALE_PRIMARY -> {
                        takeoverPrimarySessionId = clearPrimarySessionForTakeoverLocked()
                        IncomingSessionRoute.PRIMARY
                    }

                    else -> resolvedRoute
                }
            }
        if (route == IncomingSessionRoute.SECONDARY) {
            startSecondaryStreamWithExistingCapture(
                sessionId = sessionId,
                width = width,
                height = height,
                fps = fps,
                customIceServers = effectiveIceServers,
                waitForOffer = waitForOffer,
            )
            return
        }
        val streamId = streamGeneration.incrementAndGet()
        val stalePeer =
            synchronized(streamLock) {
                val pendingPrimarySignaling =
                    snapshotPendingPrimarySignalingForResetLocked(
                        sessionId = sessionId,
                        reason = "reuse_capture",
                    )
                detachPeerResourcesLocked().also {
                    restorePendingPrimarySignalingAfterResetLocked(
                        snapshot = pendingPrimarySignaling,
                        reason = "reuse_capture",
                    )
                }
            }
        cleanupPeerResources(stalePeer)
        updateCaptureFormat(width, height, fps)
        synchronized(streamLock) {
            primarySessionId = sessionId
            streamRequestId = sessionId
            waitingForOffer = waitForOffer
            iceRestartAttempts = 0
            captureLogStreamId = streamId
            captureSessionMode = CaptureSessionMode.STREAM
            refreshCaptureFastStateLocked()
            primePrimarySessionLivenessLocked(sessionId, SystemClock.elapsedRealtime())
            if (videoTrack == null) {
                throw IllegalStateException("No active capture to reuse")
            }
        }
        val newResources = createPeerConnection(effectiveIceServers, streamId, sessionId)
        val sendReadyFor: String?
        val shouldCreateOffer: Boolean
        val activeTrack: VideoTrack
        val connectionForSender: PeerConnection?
        synchronized(streamLock) {
            if (streamGeneration.get() != streamId) {
                newResources?.let {
                    cleanupPeerResources(PeerResources(it.peerConnection, it.controlChannel))
                }
                return
            }
            peerConnection = newResources?.peerConnection
            controlChannel = newResources?.controlChannel
            scrcpyControlHandler = newResources?.scrcpyControlHandler
            activeTrack = videoTrack ?: throw IllegalStateException("No active capture to reuse")
            connectionForSender = peerConnection
            if (!waitForOffer) {
                shouldCreateOffer = true
                sendReadyFor = null
            } else {
                shouldCreateOffer = false
                if (!hasQueuedPrimaryOfferLocked(sessionId)) {
                    sendReadyFor = sessionId
                } else {
                    sendReadyFor = null
                    Log.i(
                        TAG,
                        "Skipping stream/ready because a primary offer is already queued (sessionId=$sessionId)",
                    )
                }
            }
        }
        try {
            if (isCurrentStream(streamId)) {
                val sender = connectionForSender?.addTrack(activeTrack, listOf(VIDEO_TRACK_ID))
                configureVideoSender(sender, width, height, fps)
                startStatsLogging(streamId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed attaching reused-capture video sender", e)
            stopStream()
            throw e
        }
        if (shouldCreateOffer) createOffer(streamId)
        sendReadyFor?.let { sendStreamReady(it) }
        takeoverPrimarySessionId?.let { notifyStreamStoppedAsync(sessionId = it) }
        flushPendingPrimarySignaling(sessionId)
    }

    private fun startSecondaryStreamWithExistingCapture(
        sessionId: String,
        width: Int,
        height: Int,
        fps: Int,
        customIceServers: List<PeerConnection.IceServer>?,
        waitForOffer: Boolean,
    ) {
        cancelIdleStop()
        val staleSessions: List<SecondarySession>
        val staleSession =
            synchronized(streamLock) {
                staleSessions = reapDisconnectedSecondarySessionsLocked()
                val hasPrimary = peerConnection != null && !primarySessionId.isNullOrBlank()
                val existing = secondarySessions[sessionId]
                val activeCount =
                    (if (hasPrimary) 1 else 0) + secondarySessions.size - if (existing != null) 1 else 0
                if (existing == null && activeCount >= MAX_CONCURRENT_SESSIONS) {
                    Log.w(
                        TAG,
                        "Rejecting secondary session $sessionId: max concurrent sessions reached (primary=$primarySessionId secondary=${secondarySessions.keys})",
                    )
                    throw IllegalStateException("max_concurrent_stream_sessions_reached")
                }
                secondarySessions.remove(sessionId)
            }
        staleSessions.forEach { cleanupSecondarySession(it) }
        staleSession?.let { cleanupSecondarySession(it) }

        val streamId = streamGeneration.incrementAndGet()
        synchronized(streamLock) {
            val activeTrack =
                videoTrack ?: throw IllegalStateException("No active capture to reuse")
            val session =
                createSecondaryPeerConnectionLocked(
                    sessionId = sessionId,
                    customIceServers = customIceServers,
                    waitForOffer = waitForOffer,
                    streamId = streamId,
                )
            val sender = session.peerConnection.addTrack(activeTrack, listOf(VIDEO_TRACK_ID))
            val effectiveWidth = if (captureWidth > 0) captureWidth else width
            val effectiveHeight = if (captureHeight > 0) captureHeight else height
            val effectiveFps = if (captureFps > 0) captureFps else fps
            configureVideoSender(sender, effectiveWidth, effectiveHeight, effectiveFps)
            secondarySessions[sessionId] = session
            if (!waitForOffer) {
                createOfferForSecondary(sessionId, streamId)
            } else {
                sendStreamReady(sessionId)
            }
        }
    }

    private fun createSecondaryPeerConnectionLocked(
        sessionId: String,
        customIceServers: List<PeerConnection.IceServer>?,
        waitForOffer: Boolean,
        streamId: Int,
    ): SecondarySession {
        val iceServers = ArrayList<PeerConnection.IceServer>()
        if (customIceServers != null && customIceServers.isNotEmpty()) {
            iceServers.addAll(customIceServers)
        } else {
            iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        }

        val hasTurn = hasTurnServer(iceServers)
        val isCellular = isCellularNetwork()
        val rtcConfig =
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            }
        if (isCellular && hasTurn) {
            rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }

        val connection =
            peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : CustomPeerConnectionObserver() {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        var toSend: IceCandidate? = null
                        synchronized(streamLock) {
                            val session = secondarySessions[sessionId]
                            if (session == null || session.streamId != streamId) return
                            if (session.canSendOutgoingIceCandidates) {
                                toSend = candidate
                            } else {
                                queueOutgoingIceCandidateLocked(session, candidate)
                            }
                        }
                        toSend?.let { sendIceCandidate(it, sessionId) }
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        var shouldRestart = false
                        var restartWaitForOffer = false
                        var activeConnection: PeerConnection? = null
                        synchronized(streamLock) {
                            val session = secondarySessions[sessionId]
                            if (session == null || session.streamId != streamId) return
                            updateSessionIceStateLocked(sessionId, state)
                            when (state) {
                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    cancelIdleStop()
                                    showCloudStreamConnectedToastOnce(streamId)
                                }

                                PeerConnection.IceConnectionState.FAILED -> {
                                    if (session.iceRestartAttempts < MAX_ICE_RESTARTS) {
                                        session.iceRestartAttempts += 1
                                        session.pendingIceCandidates.clear()
                                        session.pendingOutgoingIceCandidates.clear()
                                        session.canSendOutgoingIceCandidates = false
                                        session.isRemoteDescriptionSet = false
                                        shouldRestart = true
                                        restartWaitForOffer = session.waitingForOffer
                                        activeConnection = session.peerConnection
                                    } else {
                                        clearSessionLivenessStateLocked(sessionId)
                                        secondarySessions.remove(sessionId)
                                        activeConnection = session.peerConnection
                                    }
                                }

                                PeerConnection.IceConnectionState.DISCONNECTED,
                                PeerConnection.IceConnectionState.CLOSED -> {
                                    clearSessionLivenessStateLocked(sessionId)
                                    secondarySessions.remove(sessionId)
                                    activeConnection = session.peerConnection
                                }

                                else -> {}
                            }
                        }
                        if (shouldRestart) {
                            activeConnection?.restartIce()
                            if (restartWaitForOffer) {
                                sendStreamReady(sessionId)
                            } else {
                                createOfferForSecondary(sessionId, streamId)
                            }
                            return
                        }
                        if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                            state == PeerConnection.IceConnectionState.CLOSED ||
                            state == PeerConnection.IceConnectionState.FAILED
                        ) {
                            activeConnection?.close()
                            if (!isStreamActive()) {
                                scheduleIdleStop(state?.name?.lowercase(Locale.US) ?: "peer_closed")
                            }
                        }
                    }
                }
            )
                ?: throw IllegalStateException("Failed to create PeerConnection for session $sessionId")

        val dcInit =
            DataChannel.Init().apply {
                ordered = true
                negotiated = true
                id = 1
            }
        val dataChannel = connection.createDataChannel("control", dcInit)
        val controlHandler = ScrcpyControlChannel()
        dataChannel.registerObserver(
            LoggingDataChannelObserver(
                channel = dataChannel,
                label = "secondary:$sessionId:$streamId",
                delegate = controlHandler,
                onStateChanged = { state ->
                    synchronized(streamLock) {
                        updateSessionControlStateLocked(sessionId, state)
                    }
                },
            ),
        )

        return SecondarySession(
            sessionId = sessionId,
            peerConnection = connection,
            controlChannel = dataChannel,
            controlHandler = controlHandler,
            waitingForOffer = waitForOffer,
            streamId = streamId,
        )
    }

    private fun createOfferForSecondary(sessionId: String, streamId: Int) {
        val session =
            synchronized(streamLock) {
                val current = secondarySessions[sessionId]
                if (current == null || current.streamId != streamId) null else current
            }
                ?: return
        val constraints = MediaConstraints()
        session.peerConnection.createOffer(
            object : SimpleSdpObserver("createOffer-secondary") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    super.onCreateSuccess(desc)
                    val activeSession =
                        synchronized(streamLock) {
                            val current = secondarySessions[sessionId]
                            if (current == null || current.streamId != streamId) null else current
                        }
                            ?: return
                    val mungedSdp = preferH264(desc.description)
                    val offer =
                        if (mungedSdp == desc.description) desc
                        else SessionDescription(desc.type, mungedSdp)
                    activeSession.peerConnection.setLocalDescription(
                        object : SimpleSdpObserver("setLocalDescription-secondary") {
                            override fun onSetSuccess() {
                                sendOffer(offer.description, sessionId)
                            }
                        },
                        offer,
                    )
                }
            },
            constraints,
        )
    }

    private fun cleanupSecondarySession(session: SecondarySession) {
        cancelKeepAlive(session.sessionId)
        try {
            session.controlChannel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing secondary control channel", e)
        }
        try {
            session.peerConnection.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing secondary peer connection", e)
        }
    }

    private fun reapDisconnectedSecondarySessionsLocked(): List<SecondarySession> {
        if (secondarySessions.isEmpty()) return emptyList()
        val staleSessions = mutableListOf<SecondarySession>()
        val iterator = secondarySessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            val state = session.peerConnection.iceConnectionState()
            if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                state == PeerConnection.IceConnectionState.CLOSED ||
                state == PeerConnection.IceConnectionState.FAILED
            ) {
                staleSessions += session
                iterator.remove()
            }
        }
        return staleSessions
    }

    private fun showCloudStreamConnectedToastOnce(streamId: Int) {
        val shouldShow =
            synchronized(streamLock) {
                if (!isCurrentStreamLocked(streamId)) {
                    false
                } else if (connectionToastStreamId == streamId) {
                    false
                } else {
                    connectionToastStreamId = streamId
                    true
                }
            }
        if (!shouldShow) return
        mainHandler.post {
            Toast.makeText(context, "Cloud stream connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCaptureFormat(width: Int, height: Int, fps: Int) {
        val capturer = synchronized(streamLock) { screenCapturer }
        if (capturer == null)
            throw IllegalStateException("No active capture to reconfigure")

        val needsUpdate = synchronized(streamLock) {
            width != captureWidth || height != captureHeight || fps != captureFps
        }
        if (!needsUpdate) return
        try {
            capturer.changeCaptureFormat(width, height, fps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change capture format", e)
            throw e
        }
        synchronized(streamLock) {
            captureWidth = width
            captureHeight = height
            captureFps = fps
        }
    }

    private fun sendStreamReady(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val json =
            JSONObject().apply {
                put("method", "stream/ready")
                put("params", JSONObject().apply { put("sessionId", sessionId) })
            }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun executeQueuedKeyframeRequest(sessionId: String, generation: Int) {
        var targets = emptyList<KeyframeTargetAction>()
        val executionDisposition =
            synchronized(streamLock) {
                val pendingRequest = pendingKeyframeRequest
                val disposition =
                    planKeyframeExecution(
                        pendingGeneration = pendingRequest?.generation,
                        pendingSessionId = pendingRequest?.sessionId,
                        expectedGeneration = generation,
                        expectedSessionId = sessionId,
                        activeGeneration = streamGeneration.get(),
                        trackedSession = isTrackedSessionLocked(sessionId),
                        streamActive = peerConnection != null || secondarySessions.isNotEmpty(),
                    )
                if (disposition == KeyframeExecutionDisposition.EXECUTE) {
                    pendingKeyframeRequest = null
                    lastKeyframeRequestStartedAtMs = SystemClock.elapsedRealtime()
                    targets = buildKeyframeTargetActionsLocked()
                } else {
                    if (
                        pendingRequest?.generation == generation &&
                        pendingRequest?.sessionId == sessionId
                    ) {
                        pendingKeyframeRequest = null
                    }
                    targets = emptyList()
                }
                disposition
            }

        when (executionDisposition) {
            KeyframeExecutionDisposition.SKIP_REPLACED -> {
                Log.d(
                    TAG,
                    "Skipping queued keyframe request for session=$sessionId generation=$generation because it was replaced",
                )
                return
            }

            KeyframeExecutionDisposition.SKIP_STALE -> {
                Log.d(
                    TAG,
                    "Skipping queued keyframe request for session=$sessionId generation=$generation because the stream is stale",
                )
                return
            }

            KeyframeExecutionDisposition.EXECUTE -> Unit
        }

        if (targets.isEmpty()) {
            Log.d(
                TAG,
                "Skipping queued keyframe request for session=$sessionId generation=$generation because no video senders are active",
            )
            return
        }

        val summary = executeKeyframeTargetActions(targets)
        val message =
            "Executed queued keyframe request for session=$sessionId generation=$generation attempted=${summary.attempted} succeeded=${summary.succeeded} failed=${summary.failed} failedLabels=${summary.failedLabels.joinToString(",")}"
        if (summary.failed > 0) {
            Log.w(TAG, message)
        } else {
            Log.i(TAG, message)
        }
    }

    private fun buildKeyframeTargetActionsLocked(): List<KeyframeTargetAction> {
        val targets = mutableListOf<KeyframeTargetAction>()

        peerConnection?.senders?.forEach { sender ->
            if (sender.track()?.kind() != "video") return@forEach
            targets +=
                KeyframeTargetAction(label = "primary") {
                    requestKeyframeOnSender(sender)
                }
        }

        secondarySessions.values.forEach { session ->
            session.peerConnection.senders?.forEach { sender ->
                if (sender.track()?.kind() != "video") return@forEach
                targets +=
                    KeyframeTargetAction(label = "secondary:${session.sessionId}") {
                        requestKeyframeOnSender(sender)
                    }
            }
        }

        return targets
    }

    private fun requestKeyframeOnSender(sender: RtpSender) {
        val params = sender.parameters
        params.encodings?.forEach { encoding ->
            val original = encoding.maxBitrateBps
            encoding.maxBitrateBps = (original ?: 2_000_000) + 1
            sender.setParameters(params)
            encoding.maxBitrateBps = original
            sender.setParameters(params)
        }
    }

    fun stopStream() {
        stopAllSessions()
    }

    fun stopStream(sessionId: String) {
        stopStream(sessionId, "session_stop")
    }

    private fun stopStream(sessionId: String, reason: String) {
        require(sessionId.isNotBlank()) { "Missing required param: 'sessionId'" }
        cancelIdleStop()
        cancelKeepAlive(sessionId)
        val primaryResources: PeerResources?
        val secondaryResources: SecondarySession?
        val hasRemaining =
            synchronized(streamLock) {
                if (primarySessionId == sessionId) {
                    streamGeneration.incrementAndGet()
                    primarySessionId = null
                    if (streamRequestId == sessionId) {
                        streamRequestId = null
                    }
                    primaryResources = detachPeerResourcesLocked()
                    secondaryResources = null
                } else {
                    primaryResources = null
                    secondaryResources = secondarySessions.remove(sessionId)
                }
                peerConnection != null || secondarySessions.isNotEmpty()
            }
        primaryResources?.let { cleanupPeerResources(it) }
        secondaryResources?.let { cleanupSecondarySession(it) }
        if (reason == "keep_alive_timeout") {
            notifyStreamStoppedAsync(reason, sessionId)
        }
        if (!hasRemaining) {
            when (lastSessionCaptureAction(reason, isCaptureActive())) {
                LastSessionCaptureAction.NONE -> Unit
                LastSessionCaptureAction.SCHEDULE_IDLE_STOP -> scheduleIdleStop("session_stop")
            }
        }
    }

    fun stopAllSessions(reason: String = "cloud_stop") {
        cancelIdleStop()
        cancelAllKeepAlives()
        val primaryResources: PeerResources?
        val staleSecondary: List<SecondarySession>
        synchronized(streamLock) {
            streamGeneration.incrementAndGet()
            primarySessionId = null
            streamRequestId = null
            primaryResources = detachPeerResourcesLocked()
            staleSecondary = secondarySessions.values.toList()
            secondarySessions.clear()
        }
        primaryResources?.let { cleanupPeerResources(it) }
        staleSecondary.forEach { cleanupSecondarySession(it) }
        scheduleIdleStop(reason)
    }

    fun requestGracefulStop(reason: String = "cloud_stop") {
        stopAllSessions(reason)
    }

    internal fun onSharedCaptureScreenshotCompleted(usedSharedCaptureSession: Boolean) {
        val captureState = getCaptureFastStateSnapshot()
        val streamActive = isStreamActive()
        if (
            !shouldArmCaptureOnlyIdleStop(
                usedSharedCaptureSession = usedSharedCaptureSession,
                captureSessionMode = captureState.captureSessionMode,
                captureActive = captureState.captureActive,
                streamActive = streamActive,
            )
        ) {
            return
        }
        Log.i(
            TAG,
            "Arming capture-only idle stop for shared screenshot reuse (${SCREENSHOT_CAPTURE_IDLE_TIMEOUT_MS}ms)",
        )
        scheduleIdleStop(
            reason = "screenshot_capture_only",
            timeoutMs = SCREENSHOT_CAPTURE_IDLE_TIMEOUT_MS,
        )
    }

    fun stopStreamAsync(onStopped: (() -> Unit)? = null) {
        cancelIdleStop()
        cancelAllKeepAlives()
        val stopGeneration = streamGeneration.get()
        stopHandler.post {
            var invokeCallback = false
            var staleSecondary: List<SecondarySession> = emptyList()
            val resources =
                synchronized(streamLock) {
                    val currentGeneration = streamGeneration.get()
                    if (stopGeneration != currentGeneration) {
                        invokeCallback = true
                        null
                    } else {
                        streamGeneration.incrementAndGet()
                        primarySessionId = null
                        staleSecondary = secondarySessions.values.toList()
                        secondarySessions.clear()
                        streamRequestId = null
                        invokeCallback = true
                        detachStreamResourcesLocked()
                    }
                }
            staleSecondary.forEach { cleanupSecondarySession(it) }
            if (resources != null) {
                cleanupStreamResources(resources)
            }
            if (invokeCallback && onStopped != null) {
                mainHandler.post { onStopped() }
            }
        }
    }

    private fun scheduleIdleStop(reason: String, timeoutMs: Long = IDLE_TIMEOUT_MS) {
        if (!isCaptureActive()) return
        idleStopRunnable?.let { stopHandler.removeCallbacks(it) }
        val runnable = Runnable {
            idleStopRunnable = null
            if (isCaptureActive() && !isStreamActive()) {
                Log.i(TAG, "Idle timeout reached ($reason) - stopping capture")
                ScreenCaptureService.requestStop("idle_timeout")
            }
        }
        idleStopRunnable = runnable
        stopHandler.postDelayed(runnable, timeoutMs)
    }

    private fun cancelIdleStop() {
        idleStopRunnable?.let { stopHandler.removeCallbacks(it) }
        idleStopRunnable = null
    }

    private fun hasTurnServer(iceServers: List<PeerConnection.IceServer>): Boolean {
        return iceServers.any { server ->
            server.urls.any { url ->
                val normalized = url.lowercase(Locale.US)
                normalized.startsWith("turn:") || normalized.startsWith("turns:")
            }
        }
    }

    private fun isCellularNetwork(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun isTrackedSessionLocked(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        return primarySessionId == sessionId ||
                streamRequestId == sessionId ||
                secondarySessions.containsKey(sessionId)
    }

    private fun ensureSessionLivenessStateLocked(sessionId: String): SessionLivenessState {
        return sessionLivenessStates.getOrPut(sessionId) {
            SessionLivenessState(lastLivenessAtMs = 0L)
        }
    }

    private fun clearSessionLivenessStateLocked(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        requestFrameLoggedSessions.remove(sessionId)
        sessionLivenessStates.remove(sessionId)?.runnable?.let { stopHandler.removeCallbacks(it) }
    }

    private fun primePrimarySessionLivenessLocked(sessionId: String, nowMs: Long) {
        val state = ensureSessionLivenessStateLocked(sessionId)
        state.lastLivenessAtMs = nowMs
    }

    private fun resolveIncomingSessionRouteLocked(sessionId: String): IncomingSessionRoute {
        val currentPrimarySessionId = primarySessionId
        val primaryState = currentPrimarySessionId?.let { sessionLivenessStates[it] }
        val route =
            resolveIncomingSessionRoute(
            currentPrimarySessionId = currentPrimarySessionId,
            incomingSessionId = sessionId,
            primaryHasPeerResources = peerConnection != null || controlChannel != null,
            primaryLastLivenessAtMs = primaryState?.lastLivenessAtMs?.takeIf { it > 0L },
            nowMs = SystemClock.elapsedRealtime(),
            livenessStaleAfterMs = KEEP_ALIVE_TIMEOUT_MS,
        )
        if (
            route == IncomingSessionRoute.TAKEOVER_STALE_PRIMARY &&
            currentPrimarySessionId != null &&
            currentPrimarySessionId != sessionId
        ) {
            val lastLivenessAgeMs =
                primaryState?.lastLivenessAtMs
                    ?.takeIf { it > 0L }
                    ?.let { SystemClock.elapsedRealtime() - it }
            Log.i(
                TAG,
                "Incoming session $sessionId taking over stale primary $currentPrimarySessionId (lastLivenessAgeMs=${lastLivenessAgeMs ?: -1L})",
            )
        }
        return route
    }

    private fun clearPrimarySessionForTakeoverLocked(): String? {
        val currentPrimarySessionId = primarySessionId ?: return null
        clearSessionLivenessStateLocked(currentPrimarySessionId)
        if (streamRequestId == currentPrimarySessionId) {
            streamRequestId = null
        }
        primarySessionId = null
        return currentPrimarySessionId
    }

    private fun updateSessionIceStateLocked(
        sessionId: String?,
        state: PeerConnection.IceConnectionState?,
    ) {
        if (sessionId.isNullOrBlank() || !isTrackedSessionLocked(sessionId)) return
        ensureSessionLivenessStateLocked(sessionId).iceState = state
    }

    private fun updateSessionControlStateLocked(
        sessionId: String?,
        state: DataChannel.State?,
    ) {
        if (sessionId.isNullOrBlank() || !isTrackedSessionLocked(sessionId)) return
        ensureSessionLivenessStateLocked(sessionId).controlState = state
    }

    private fun detachStreamResourcesLocked(): StreamResources {
        val peerResources = detachPeerResourcesLocked()
        val resources =
            StreamResources(
                screenCapturer = screenCapturer,
                videoSource = videoSource,
                videoTrack = videoTrack,
                surfaceTextureHelper = surfaceTextureHelper,
                peerConnection = peerResources.peerConnection,
                controlChannel = peerResources.controlChannel
            )
        screenCapturer = null
        videoSource = null
        videoTrack = null
        surfaceTextureHelper = null
        captureLogStreamId = 0
        captureWidth = 0
        captureHeight = 0
        captureFps = 0
        captureSessionMode = CaptureSessionMode.NONE
        refreshCaptureFastStateLocked()
        return resources
    }

    private fun snapshotPendingPrimarySignalingForResetLocked(
        sessionId: String?,
        reason: String,
    ): PendingPrimarySignalingSnapshot? {
        val snapshot =
            maybeSnapshotPendingPrimarySignalingForReset(
                sessionId = sessionId,
                pendingSessionId = pendingPrimaryOfferSessionId,
                offer = pendingPrimaryOffer,
                ice = pendingPrimaryIceBeforePeer,
                hasLiveNegotiatedPeer = peerConnection != null && isRemoteDescriptionSet,
            )
        if (snapshot != null) {
            Log.i(
                TAG,
                "Preserving queued primary signaling across reset: reason=$reason session=${snapshot.sessionId} offer=${snapshot.offer != null} ice=${snapshot.ice.size}",
            )
        }
        return snapshot
    }

    private fun restorePendingPrimarySignalingAfterResetLocked(
        snapshot: PendingPrimarySignalingSnapshot?,
        reason: String,
    ) {
        if (snapshot == null) return
        pendingPrimaryOfferSessionId = snapshot.sessionId
        pendingPrimaryOffer = snapshot.offer
        pendingPrimaryIceBeforePeer.clear()
        pendingPrimaryIceBeforePeer.addAll(snapshot.ice)
        Log.i(
            TAG,
            "Restored queued primary signaling after reset: reason=$reason session=${snapshot.sessionId} offer=${snapshot.offer != null} ice=${snapshot.ice.size}",
        )
    }

    private fun hasQueuedPrimaryOfferLocked(sessionId: String): Boolean =
        hasQueuedPrimaryOfferForSession(
            sessionId = sessionId,
            pendingSessionId = pendingPrimaryOfferSessionId,
            offer = pendingPrimaryOffer,
        )

    private fun detachPeerResourcesLocked(): PeerResources {
        val resources = PeerResources(peerConnection, controlChannel)
        clearSessionLivenessStateLocked(primarySessionId)
        if (streamRequestId != primarySessionId) {
            clearSessionLivenessStateLocked(streamRequestId)
        }
        peerConnection = null
        controlChannel = null
        scrcpyControlHandler = null
        stopStatsLogging()
        pendingIceCandidates.clear()
        pendingOutgoingIceCandidates.clear()
        canSendOutgoingIceCandidates = false
        isRemoteDescriptionSet = false
        pendingPrimaryOffer = null
        pendingPrimaryOfferSessionId = null
        pendingPrimaryIceBeforePeer.clear()
        iceRestartAttempts = 0
        waitingForOffer = false
        return resources
    }

    private fun cleanupStreamResources(resources: StreamResources) {
        clearCaptureFrameTapState("capture_stopped")
        try {
            resources.screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer", e)
        }

        try {
            resources.screenCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer", e)
        }

        try {
            resources.videoSource?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing videoSource", e)
        }

        try {
            resources.videoTrack?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing videoTrack", e)
        }

        try {
            resources.surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing surfaceTextureHelper", e)
        }

        cleanupPeerResources(PeerResources(resources.peerConnection, resources.controlChannel))
    }

    private fun cleanupPeerResources(resources: PeerResources) {
        try {
            resources.controlChannel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing controlChannel", e)
        }

        try {
            resources.peerConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing peerConnection", e)
        }
    }

    fun handleAnswer(sdp: String) {
        val sessionId = primarySessionId ?: streamRequestId
        if (sessionId.isNullOrBlank()) {
            Log.w(TAG, "handleAnswer called without active session")
            return
        }
        handleAnswer(sdp, sessionId)
    }

    fun handleAnswer(sdp: String, sessionId: String) {
        if (sessionId.isBlank()) {
            Log.w(TAG, "handleAnswer called without sessionId")
            return
        }
        val isPrimary =
            synchronized(streamLock) {
                primarySessionId == sessionId
            }
        if (!isPrimary) {
            handleAnswerForSecondary(sdp, sessionId)
            return
        }
        var connection: PeerConnection? = null
        var generation = 0
        synchronized(streamLock) {
            if (peerConnection == null) {
                Log.w(TAG, "handleAnswer called but no active PeerConnection")
            } else {
                connection = peerConnection
                generation = streamGeneration.get()
            }
        }
        val activeConnection = connection ?: return
        activeConnection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    var pending: List<IceCandidate>? = null
                    var pendingOutgoing: List<IceCandidate>? = null
                    var shouldReturn = false
                    synchronized(streamLock) {
                        if (peerConnection !== activeConnection ||
                            streamGeneration.get() != generation
                        ) {
                            Log.w(TAG, "Remote description set on stale PeerConnection")
                            shouldReturn = true
                        } else {
                            isRemoteDescriptionSet = true
                            pending = drainPendingIceCandidatesLocked()
                            pendingOutgoing = enableOutgoingIceAndDrainLocked()
                        }
                    }
                    if (shouldReturn) return
                    pending?.let { candidates ->
                        for (candidate in candidates) {
                            activeConnection.addIceCandidate(candidate)
                        }
                    }
                    pendingOutgoing?.forEach { sendIceCandidate(it, sessionId) }
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleOffer(sdp: String, sessionId: String) {
        if (sessionId.isBlank()) {
            Log.w(TAG, "handleOffer called without sessionId")
            return
        }
        val isPrimary =
            synchronized(streamLock) {
                primarySessionId == sessionId ||
                        (primarySessionId == null && streamRequestId == sessionId)
            }
        if (!isPrimary) {
            handleOfferForSecondary(sdp, sessionId)
            return
        }
        var connection: PeerConnection? = null
        var generation = 0
        synchronized(streamLock) {
            if (peerConnection == null) {
                val pendingSessionId = pendingPrimaryOfferSessionId
                when {
                    pendingSessionId == null -> {
                        pendingPrimaryOfferSessionId = sessionId
                        pendingPrimaryOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                        Log.i(
                            TAG,
                            "Queued primary offer until peer is ready (sessionId=$sessionId)"
                        )
                    }

                    pendingSessionId == sessionId -> {
                        pendingPrimaryOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                        Log.i(
                            TAG,
                            "Replaced queued primary offer until peer is ready (sessionId=$sessionId)"
                        )
                    }

                    else -> {
                        Log.w(
                            TAG,
                            "Ignoring competing primary offer while waiting for peer (pendingSessionId=$pendingSessionId, incomingSessionId=$sessionId)",
                        )
                    }
                }
            } else {
                connection = peerConnection
                generation = streamGeneration.get()
            }
        }
        val activeConnection = connection ?: return
        applyPrimaryOffer(
            connection = activeConnection,
            generation = generation,
            sessionId = sessionId,
            offer = SessionDescription(SessionDescription.Type.OFFER, sdp),
            source = "live",
        )
    }

    private fun applyPrimaryOffer(
        connection: PeerConnection,
        generation: Int,
        sessionId: String,
        offer: SessionDescription,
        source: String,
    ) {
        Log.i(TAG, "Applying primary offer ($source): session=$sessionId gen=$generation")
        connection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription-offer-$source") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    synchronized(streamLock) {
                        if (peerConnection !== connection ||
                            streamGeneration.get() != generation
                        ) {
                            Log.w(TAG, "Remote offer set on stale PeerConnection")
                            return
                        }
                        isRemoteDescriptionSet = true
                    }
                    createAnswer(connection, generation, sessionId)
                }
            },
            offer,
        )
    }

    private fun createAnswer(connection: PeerConnection, generation: Int, sessionId: String) {
        val constraints = MediaConstraints()
        connection.createAnswer(
            object : SimpleSdpObserver("createAnswer") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    super.onCreateSuccess(desc)
                    synchronized(streamLock) {
                        if (peerConnection !== connection ||
                            streamGeneration.get() != generation
                        ) {
                            Log.w(TAG, "Answer created on stale PeerConnection")
                            return
                        }
                    }
                    Log.i(TAG, "Created primary answer: session=$sessionId gen=$generation")
                    val forcedH264 = forceH264(desc.description)
                    val answer =
                        if (forcedH264 == null) {
                            desc
                        } else {
                            Log.i(TAG, "Forcing H264 in answer SDP")
                            SessionDescription(desc.type, forcedH264)
                        }
                    setLocalDescriptionAndSendAnswer(
                        connection,
                        generation,
                        sessionId,
                        answer,
                        desc,
                        answer != desc,
                    )
                }
            },
            constraints
        )
    }

    private fun setLocalDescriptionAndSendAnswer(
        connection: PeerConnection,
        generation: Int,
        sessionId: String,
        answer: SessionDescription,
        fallback: SessionDescription,
        allowFallback: Boolean,
    ) {
        connection.setLocalDescription(
            object : SimpleSdpObserver("setLocalDescription-answer") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    synchronized(streamLock) {
                        if (peerConnection !== connection ||
                            streamGeneration.get() != generation
                        )
                            return
                    }
                    val pending =
                        synchronized(streamLock) {
                            drainPendingIceCandidatesLocked()
                        }
                    pending.forEach { connection.addIceCandidate(it) }
                    sendAnswer(answer, sessionId)
                    val outgoing =
                        synchronized(streamLock) {
                            if (peerConnection !== connection ||
                                streamGeneration.get() != generation
                            ) {
                                emptyList()
                            } else {
                                enableOutgoingIceAndDrainLocked()
                            }
                        }
                    outgoing.forEach { sendIceCandidate(it, sessionId) }
                }

                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "setLocalDescription (answer) failed: $p0")
                    if (!allowFallback ||
                        peerConnection !== connection ||
                        streamGeneration.get() != generation
                    ) {
                        return
                    }
                    Log.w(TAG, "Retrying setLocalDescription with original answer SDP")
                    setLocalDescriptionAndSendAnswer(
                        connection,
                        generation,
                        sessionId,
                        fallback,
                        fallback,
                        false,
                    )
                }
            },
            answer
        )
    }

    private fun sendAnswer(desc: SessionDescription, sessionId: String) {
        val json =
            JSONObject().apply {
                put("id", outgoingMessageId.getAndIncrement())
                put("method", "webrtc/answer")
                put(
                    "params",
                    JSONObject().apply {
                        put("sessionId", sessionId)
                        put("sdp", desc.description)
                    }
                )
            }
        Log.i(
            TAG,
            "Sending primary answer: session=$sessionId sdpLength=${desc.description.length}"
        )
        reverseConnectionService?.sendText(json.toString())
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        val sessionId = primarySessionId ?: streamRequestId
        if (sessionId.isNullOrBlank()) {
            Log.w(TAG, "handleIceCandidate called without active session")
            return
        }
        handleIceCandidate(candidate, sessionId)
    }

    fun handleIceCandidate(candidate: IceCandidate, sessionId: String) {
        if (sessionId.isBlank()) {
            Log.w(TAG, "handleIceCandidate called without sessionId")
            return
        }
        val isPrimary =
            synchronized(streamLock) {
                primarySessionId == sessionId ||
                        (primarySessionId == null && streamRequestId == sessionId)
            }
        if (!isPrimary) {
            handleIceCandidateForSecondary(candidate, sessionId)
            return
        }
        var connection: PeerConnection? = null
        var shouldReturn = false
        synchronized(streamLock) {
            if (peerConnection == null) {
                val pendingSessionId = pendingPrimaryOfferSessionId
                if (pendingSessionId == null) {
                    pendingPrimaryOfferSessionId = sessionId
                    pendingPrimaryIceBeforePeer.add(candidate)
                    Log.i(TAG, "Queued ICE before peer ready (sessionId=$sessionId)")
                } else if (pendingSessionId == sessionId) {
                    pendingPrimaryIceBeforePeer.add(candidate)
                    Log.i(TAG, "Queued ICE before peer ready (sessionId=$sessionId)")
                } else {
                    Log.w(
                        TAG,
                        "Ignoring ICE for competing pending primary session (pendingSessionId=$pendingSessionId, incomingSessionId=$sessionId)",
                    )
                }
                shouldReturn = true
            } else if (!isRemoteDescriptionSet) {
                pendingIceCandidates.add(candidate)
                shouldReturn = true
            } else {
                connection = peerConnection
            }
        }
        if (shouldReturn) return
        connection?.addIceCandidate(candidate)
    }

    private fun flushPendingPrimarySignaling(sessionId: String) {
        val offer: SessionDescription?
        val ice: List<IceCandidate>
        val connection: PeerConnection
        val generation: Int
        synchronized(streamLock) {
            if (pendingPrimaryOfferSessionId != sessionId) return
            connection = peerConnection ?: return
            generation = streamGeneration.get()
            offer = pendingPrimaryOffer
            ice = pendingPrimaryIceBeforePeer.toList()
            pendingPrimaryOffer = null
            pendingPrimaryIceBeforePeer.clear()
            pendingPrimaryOfferSessionId = null
        }
        if (offer != null || ice.isNotEmpty()) {
            Log.i(
                TAG,
                "Replaying queued primary signaling: session=$sessionId gen=$generation offer=${offer != null} ice=${ice.size}",
            )
        }
        offer?.let {
            applyPrimaryOffer(
                connection = connection,
                generation = generation,
                sessionId = sessionId,
                offer = it,
                source = "replay",
            )
        }
        ice.forEach { handleIceCandidate(it, sessionId) }
    }

    private fun handleOfferForSecondary(sdp: String, sessionId: String) {
        val session =
            synchronized(streamLock) {
                secondarySessions[sessionId]
            }
                ?: run {
                    Log.w(TAG, "handleOffer called for unknown secondary session: $sessionId")
                    return
                }
        val activeConnection = session.peerConnection
        val streamId = session.streamId
        activeConnection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription-offer-secondary") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    synchronized(streamLock) {
                        val current = secondarySessions[sessionId]
                        if (current == null || current.streamId != streamId) return
                        current.isRemoteDescriptionSet = true
                    }
                    createAnswerForSecondary(activeConnection, streamId, sessionId)
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun createAnswerForSecondary(
        connection: PeerConnection,
        streamId: Int,
        sessionId: String,
    ) {
        val constraints = MediaConstraints()
        connection.createAnswer(
            object : SimpleSdpObserver("createAnswer-secondary") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    super.onCreateSuccess(desc)
                    val forcedH264 = forceH264(desc.description)
                    val answer =
                        if (forcedH264 == null) desc
                        else SessionDescription(desc.type, forcedH264)
                    connection.setLocalDescription(
                        object : SimpleSdpObserver("setLocalDescription-answer-secondary") {
                            override fun onSetSuccess() {
                                super.onSetSuccess()
                                val pending: List<IceCandidate>
                                val outgoing: List<IceCandidate>
                                synchronized(streamLock) {
                                    val current = secondarySessions[sessionId]
                                    if (current == null || current.streamId != streamId) return
                                    pending = drainPendingIceCandidatesLocked(current)
                                    outgoing = enableOutgoingIceAndDrainLocked(current)
                                }
                                pending.forEach { connection.addIceCandidate(it) }
                                sendAnswer(answer, sessionId)
                                outgoing.forEach { sendIceCandidate(it, sessionId) }
                            }
                        },
                        answer,
                    )
                }
            },
            constraints,
        )
    }

    private fun handleAnswerForSecondary(sdp: String, sessionId: String) {
        val session =
            synchronized(streamLock) {
                secondarySessions[sessionId]
            }
                ?: run {
                    Log.w(TAG, "handleAnswer called for unknown secondary session: $sessionId")
                    return
                }
        val connection = session.peerConnection
        val streamId = session.streamId
        connection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription-secondary") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    val pending: List<IceCandidate>
                    val outgoing: List<IceCandidate>
                    synchronized(streamLock) {
                        val current = secondarySessions[sessionId]
                        if (current == null || current.streamId != streamId) return
                        current.isRemoteDescriptionSet = true
                        pending = drainPendingIceCandidatesLocked(current)
                        outgoing = enableOutgoingIceAndDrainLocked(current)
                    }
                    pending.forEach { connection.addIceCandidate(it) }
                    outgoing.forEach { sendIceCandidate(it, sessionId) }
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    private fun handleIceCandidateForSecondary(candidate: IceCandidate, sessionId: String) {
        val session =
            synchronized(streamLock) {
                secondarySessions[sessionId]
            }
                ?: run {
                    Log.w(
                        TAG,
                        "handleIceCandidate called for unknown secondary session: $sessionId"
                    )
                    return
                }
        if (!session.isRemoteDescriptionSet) {
            session.pendingIceCandidates.add(candidate)
            return
        }
        session.peerConnection.addIceCandidate(candidate)
    }

    private fun drainPendingIceCandidatesLocked(): List<IceCandidate> {
        if (pendingIceCandidates.isEmpty()) return emptyList()
        val drained = ArrayList<IceCandidate>()
        while (true) {
            val candidate = pendingIceCandidates.poll() ?: break
            drained.add(candidate)
        }
        return drained
    }

    private fun drainPendingIceCandidatesLocked(session: SecondarySession): List<IceCandidate> {
        if (session.pendingIceCandidates.isEmpty()) return emptyList()
        val drained = ArrayList<IceCandidate>()
        while (true) {
            val candidate = session.pendingIceCandidates.poll() ?: break
            drained.add(candidate)
        }
        return drained
    }

    private fun isCurrentStreamLocked(streamId: Int): Boolean {
        return streamId == streamGeneration.get() && peerConnection != null && !primarySessionId.isNullOrBlank()
    }

    private fun isCurrentStream(streamId: Int): Boolean {
        return synchronized(streamLock) { isCurrentStreamLocked(streamId) }
    }

    private fun postStopStreamIfCurrent(streamId: Int) {
        cancelIdleStop()
        stopHandler.post {
            var staleSecondary: List<SecondarySession> = emptyList()
            val resources =
                synchronized(streamLock) {
                    if (!isCurrentStreamLocked(streamId)) {
                        null
                    } else {
                        streamGeneration.incrementAndGet()
                        primarySessionId = null
                        staleSecondary = secondarySessions.values.toList()
                        secondarySessions.clear()
                        streamRequestId = null
                        detachStreamResourcesLocked()
                    }
                }
                    ?: return@post
            staleSecondary.forEach { cleanupSecondarySession(it) }
            cleanupStreamResources(resources)
        }
    }

    private fun handlePeerDisconnected(streamId: Int, reason: String) {
        var disconnectedSessionId: String? = null
        val peerResources = synchronized(streamLock) {
            if (!isCurrentStreamLocked(streamId)) {
                null
            } else {
                streamGeneration.incrementAndGet()
                disconnectedSessionId = primarySessionId
                if (streamRequestId == primarySessionId) {
                    streamRequestId = null
                }
                primarySessionId = null
                detachPeerResourcesLocked()
            }
        }
        if (peerResources != null) {
            disconnectedSessionId?.let { cancelKeepAlive(it) }
            cleanupPeerResources(peerResources)
            val hasActiveSessions = isStreamActive()
            if (!hasActiveSessions) {
                scheduleIdleStop(reason)
            }
        }
    }

    private fun createPeerConnection(
        customIceServers: List<PeerConnection.IceServer>?,
        streamId: Int,
        sessionId: String,
    ): PrimaryPeerResources? {
        val iceServers = ArrayList<PeerConnection.IceServer>()
        if (customIceServers != null && customIceServers.isNotEmpty()) {
            iceServers.addAll(customIceServers)
        } else {
            // Default google STUN
            iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        }

        val hasTurn = hasTurnServer(iceServers)
        val isCellular = isCellularNetwork()
        Log.i(
            TAG,
            "ICE config: servers=${iceServers.size} hasTurn=$hasTurn cellular=$isCellular"
        )
        val rtcConfig =
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            }
        if (isCellular && hasTurn) {
            Log.i(TAG, "Cellular network detected - forcing TURN relay")
            rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        } else if (isCellular) {
            Log.w(TAG, "Cellular network without TURN - ICE may fail")
        }

        val newPeerConnection =
            peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : CustomPeerConnectionObserver() {
                    override fun onIceCandidate(p0: IceCandidate) {
                        var toSend: IceCandidate? = null
                        var sessionIdToSend: String? = null
                        synchronized(streamLock) {
                            if (!isCurrentStreamLocked(streamId)) return
                            if (canSendOutgoingIceCandidates) {
                                toSend = p0
                                sessionIdToSend = primarySessionId
                            } else {
                                queueOutgoingIceCandidateLocked(p0)
                            }
                        }
                        toSend?.let { candidate ->
                            val sessionId = sessionIdToSend
                            if (!sessionId.isNullOrBlank()) {
                                sendIceCandidate(candidate, sessionId)
                            }
                        }
                    }

                    override fun onIceConnectionChange(
                        p0: PeerConnection.IceConnectionState?
                    ) {
                        if (!isCurrentStream(streamId)) return
                        synchronized(streamLock) {
                            updateSessionIceStateLocked(sessionId, p0)
                        }
                        Log.i(
                            TAG,
                            "Primary ICE state changed: session=$sessionId state=$p0 streamId=$streamId",
                        )
                        when (p0) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                cancelIdleStop()
                                showCloudStreamConnectedToastOnce(streamId)
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.CLOSED -> {
                                Log.w(
                                    TAG,
                                    "ICE connection ${p0.name} - keeping capture, resetting peer"
                                )
                                handlePeerDisconnected(
                                    streamId,
                                    p0.name.lowercase(Locale.US)
                                )
                            }

                            PeerConnection.IceConnectionState.FAILED -> {
                                var shouldRestart = false
                                var restartConnection: PeerConnection? = null
                                var restartWaitForOffer = false
                                synchronized(streamLock) {
                                    if (!isCurrentStreamLocked(streamId)) return
                                    if (iceRestartAttempts < MAX_ICE_RESTARTS) {
                                        iceRestartAttempts += 1
                                        shouldRestart = true
                                        restartConnection = peerConnection
                                        restartWaitForOffer = waitingForOffer
                                        pendingIceCandidates.clear()
                                        pendingOutgoingIceCandidates.clear()
                                        canSendOutgoingIceCandidates = false
                                        isRemoteDescriptionSet = false
                                    }
                                }
                                if (shouldRestart) {
                                    Log.w(TAG, "ICE connection FAILED - attempting ICE restart")
                                    restartConnection?.restartIce()
                                    if (restartWaitForOffer) {
                                        sendStreamReady(primarySessionId)
                                    } else {
                                        createOffer(streamId)
                                    }
                                    return
                                }
                                Log.w(
                                    TAG,
                                    "ICE connection FAILED - keeping capture, resetting peer"
                                )
                                handlePeerDisconnected(
                                    streamId,
                                    p0.name.lowercase(Locale.US)
                                )
                            }

                            else -> {}
                        }
                    }
                }
            )

        if (newPeerConnection == null) return null

        val dcInit =
            DataChannel.Init().apply {
                ordered = true
                negotiated = true
                id = 1
            }
        val newControlChannel = newPeerConnection.createDataChannel("control", dcInit)
        val newScrcpyControlHandler = newControlChannel?.let { dc ->
            val handler = ScrcpyControlChannel()
            dc.registerObserver(
                LoggingDataChannelObserver(
                    channel = dc,
                    label = "primary:$streamId",
                    delegate = handler,
                    onStateChanged = { state ->
                        synchronized(streamLock) {
                            updateSessionControlStateLocked(sessionId, state)
                        }
                    },
                ),
            )
            handler
        }
        return PrimaryPeerResources(
            peerConnection = newPeerConnection,
            controlChannel = newControlChannel,
            scrcpyControlHandler = newScrcpyControlHandler,
        )
    }

    private fun createVideoTrack(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
        streamId: Int,
    ): VideoTrack? {
        val capturer =
            ScreenCapturerAndroid(
                permissionResultData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        postStopStreamIfCurrent(streamId)
                        ScreenCaptureService.requestStop("projection_stopped")
                    }
                },
            )

        val source =
            peerConnectionFactory?.createVideoSource(capturer.isScreencast)
                ?: throw IllegalStateException("PeerConnectionFactory unavailable")
        val captureObserver = createCaptureFrameObserver(source.capturerObserver)
        val textureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        // Start Capture
        capturer.initialize(textureHelper, context, captureObserver)
        try {
            capturer.startCapture(width, height, fps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            cleanupStreamResources(
                StreamResources(
                    screenCapturer = capturer,
                    videoSource = source,
                    videoTrack = null,
                    surfaceTextureHelper = textureHelper,
                    peerConnection = null,
                    controlChannel = null,
                ),
            )
            stopStream()
            throw RuntimeException("Failed to start screen capture: ${e.message}", e)
        }

        val track =
            peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, source)
                ?: run {
                    cleanupStreamResources(
                        StreamResources(
                            screenCapturer = capturer,
                            videoSource = source,
                            videoTrack = null,
                            surfaceTextureHelper = textureHelper,
                            peerConnection = null,
                            controlChannel = null,
                        ),
                    )
                    throw IllegalStateException("Failed to create video track")
                }

        var published = false
        synchronized(streamLock) {
            if (streamGeneration.get() == streamId) {
                screenCapturer = capturer
                videoSource = source
                surfaceTextureHelper = textureHelper
                captureLogStreamId = streamId
                captureWidth = width
                captureHeight = height
                captureFps = fps
                videoTrack = track
                refreshCaptureFastStateLocked()
                published = true
            }
        }
        if (!published) {
            Log.w(TAG, "Discarding stale capture resources for streamId=$streamId")
            cleanupStreamResources(
                StreamResources(
                    screenCapturer = capturer,
                    videoSource = source,
                    videoTrack = track,
                    surfaceTextureHelper = textureHelper,
                    peerConnection = null,
                    controlChannel = null,
                ),
            )
            return null
        }
        return track
    }

    private fun createOffer(streamId: Int) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    super.onCreateSuccess(desc)
                    val connection =
                        synchronized(streamLock) {
                            if (!isCurrentStreamLocked(streamId)) null else peerConnection
                        }
                            ?: return

                    val mungedSdp = preferH264(desc.description)
                    val offer =
                        if (mungedSdp == desc.description) {
                            desc
                        } else {
                            SessionDescription(desc.type, mungedSdp)
                        }
                    setLocalDescriptionAndSendOffer(
                        connection,
                        streamId,
                        offer,
                        desc,
                        offer != desc
                    )
                }
            },
            constraints
        )
    }

    private fun setLocalDescriptionAndSendOffer(
        connection: PeerConnection,
        streamId: Int,
        offer: SessionDescription,
        fallback: SessionDescription,
        allowFallback: Boolean,
    ) {
        connection.setLocalDescription(
            object : SimpleSdpObserver("setLocalDescription") {
                override fun onSetSuccess() {
                    if (!isCurrentStream(streamId)) {
                        Log.w(TAG, "Offer set for stale stream; ignoring")
                        return
                    }
                    val sessionId =
                        synchronized(streamLock) {
                            primarySessionId
                        }
                    if (!sessionId.isNullOrBlank()) {
                        sendOffer(offer.description, sessionId)
                    }
                }

                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "setLocalDescription failed: $p0")
                    if (!allowFallback || !isCurrentStream(streamId)) {
                        return
                    }
                    Log.w(TAG, "Retrying setLocalDescription with original SDP")
                    setLocalDescriptionAndSendOffer(
                        connection,
                        streamId,
                        fallback,
                        fallback,
                        false
                    )
                }
            },
            offer
        )
    }

    private fun configureVideoSender(sender: RtpSender?, width: Int, height: Int, fps: Int) {
        if (sender == null) return
        val parameters = sender.parameters
        var updated = false
        val maxBitrateBps = computeMaxBitrateBps(width, height, fps)
        for (encoding in parameters.encodings) {
            val currentMax = encoding.maxBitrateBps
            if (currentMax == null || currentMax > maxBitrateBps) {
                encoding.maxBitrateBps = maxBitrateBps
                updated = true
            }
            val currentFps = encoding.maxFramerate
            if (currentFps == null || currentFps > fps) {
                encoding.maxFramerate = fps
                updated = true
            }
        }
        if (parameters.degradationPreference != RtpParameters.DegradationPreference.BALANCED) {
            parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            updated = true
        }
        if (updated) {
            val success = sender.setParameters(parameters)
            Log.i(
                TAG,
                "Applied sender params (maxBitrate=$maxBitrateBps, maxFps=$fps, success=$success)"
            )
        }
    }

    private fun computeMaxBitrateBps(width: Int, height: Int, fps: Int): Int {
        val maxDim = max(width, height)
        val base =
            when {
                maxDim >= 1080 -> 4_000_000
                maxDim >= 720 -> 1_500_000
                maxDim >= 480 -> 1_200_000
                else -> 800_000
            }
        return when {
            fps <= 10 -> base * 3 / 5
            fps <= 15 -> base * 3 / 4
            else -> base
        }
    }

    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video ") }
        if (mLineIndex == -1) return sdp

        val h264Payloads = mutableSetOf<String>()
        val rtxAptMap = mutableMapOf<String, String>()

        // Regex to parse a=rtpmap:<payload> <codec>/<clock>...
        val rtpmapRegex = Regex("^a=rtpmap:(\\d+) ([a-zA-Z0-9-]+)/\\d+.*")
        // Regex to parse a=fmtp:<payload> apt=<apt-payload>...
        val fmtpRegex = Regex("^a=fmtp:(\\d+) apt=(\\d+).*")

        for (line in lines) {
            val rtpmapMatch = rtpmapRegex.find(line)
            if (rtpmapMatch != null) {
                val payload = rtpmapMatch.groupValues[1]
                val codec = rtpmapMatch.groupValues[2]
                if (codec.equals("H264", ignoreCase = true)) {
                    h264Payloads.add(payload)
                }
            } else {
                val fmtpMatch = fmtpRegex.find(line)
                if (fmtpMatch != null) {
                    val payload = fmtpMatch.groupValues[1]
                    val apt = fmtpMatch.groupValues[2]
                    rtxAptMap[payload] = apt
                }
            }
        }

        if (h264Payloads.isEmpty()) return sdp

        val rtxPayloads = rtxAptMap.filter { it.value in h264Payloads }.keys
        val parts = lines[mLineIndex].split(" ").filter { it.isNotBlank() }
        if (parts.size <= 3) return sdp

        val header = parts.take(3)
        val payloads = parts.drop(3)

        val preferred = payloads.filter { it in h264Payloads || it in rtxPayloads }
        if (preferred.isEmpty()) return sdp

        val remaining = payloads.filter { it !in h264Payloads && it !in rtxPayloads }
        val newLine = (header + preferred + remaining).joinToString(" ")

        if (newLine == lines[mLineIndex]) return sdp

        lines[mLineIndex] = newLine

        return lines.joinToString("\r\n")
    }

    private fun forceH264(sdp: String): String? {
        val lines = sdp.split("\r\n").toMutableList()
        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video ") }
        if (mLineIndex == -1) return null

        val rtpmapRegex = Regex("^a=rtpmap:(\\d+) ([a-zA-Z0-9-]+)/\\d+.*")
        val fmtpRegex = Regex("^a=fmtp:(\\d+) .*")
        val rtcpFbRegex = Regex("^a=rtcp-fb:(\\d+) .*")
        val aptRegex = Regex("\\bapt=(\\d+)\\b")

        val h264Payloads = mutableSetOf<String>()
        val rtxAptMap = mutableMapOf<String, String>()

        for (line in lines) {
            val rtpmapMatch = rtpmapRegex.find(line)
            if (rtpmapMatch != null) {
                val payload = rtpmapMatch.groupValues[1]
                val codec = rtpmapMatch.groupValues[2]
                if (codec.equals("H264", ignoreCase = true)) {
                    h264Payloads.add(payload)
                }
                continue
            }

            val fmtpMatch = fmtpRegex.find(line)
            if (fmtpMatch != null) {
                val payload = fmtpMatch.groupValues[1]
                val aptMatch = aptRegex.find(line)
                if (aptMatch != null) {
                    rtxAptMap[payload] = aptMatch.groupValues[1]
                }
            }
        }

        if (h264Payloads.isEmpty()) return null

        val rtxPayloads = rtxAptMap.filter { it.value in h264Payloads }.keys
        val allowed = (h264Payloads + rtxPayloads).toSet()

        val parts = lines[mLineIndex].split(" ").filter { it.isNotBlank() }
        if (parts.size <= 3) return null
        val header = parts.take(3)
        val payloads = parts.drop(3)
        val filteredPayloads = payloads.filter { it in allowed }
        if (filteredPayloads.isEmpty()) return null
        lines[mLineIndex] = (header + filteredPayloads).joinToString(" ")

        val filteredLines =
            lines.filter { line ->
                val rtpmapMatch = rtpmapRegex.find(line)
                if (rtpmapMatch != null) {
                    return@filter allowed.contains(rtpmapMatch.groupValues[1])
                }
                val fmtpMatch = fmtpRegex.find(line)
                if (fmtpMatch != null) {
                    return@filter allowed.contains(fmtpMatch.groupValues[1])
                }
                val rtcpFbMatch = rtcpFbRegex.find(line)
                if (rtcpFbMatch != null) {
                    return@filter allowed.contains(rtcpFbMatch.groupValues[1])
                }
                true
            }

        return filteredLines.joinToString("\r\n")
    }

    private fun startStatsLogging(streamId: Int) {
        stopStatsLogging()
        lastStatsBytesSent = null
        lastStatsTimestampMs = null
        val runnable =
            object : Runnable {
                override fun run() {
                    val connection =
                        synchronized(streamLock) {
                            if (isCurrentStreamLocked(streamId)) peerConnection else null
                        }
                            ?: return
                    connection.getStats { report ->
                        if (!isCurrentStream(streamId)) return@getStats
                        logStats(report)
                        statsHandler.postDelayed(this, STATS_INTERVAL_MS)
                    }
                }
            }
        statsRunnable = runnable
        statsHandler.postDelayed(runnable, STATS_INTERVAL_MS)
    }

    private fun stopStatsLogging() {
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        statsRunnable = null
        lastStatsBytesSent = null
        lastStatsTimestampMs = null
        consecutiveZeroSentStatsIntervals = 0
    }

    private fun logStats(report: RTCStatsReport) {
        val statsMap = report.statsMap
        val outboundVideo =
            statsMap.values.firstOrNull { stats ->
                stats.type == "outbound-rtp" &&
                        !getBooleanMember(stats.members, "isRemote", false) &&
                        isVideoStats(stats.members)
            }
                ?: return

        val members = outboundVideo.members
        val bytesSent = getLongMember(members, "bytesSent")
        val framesSent =
            getLongMember(members, "framesSent") ?: getLongMember(members, "framesEncoded")
        val framesDropped = getLongMember(members, "framesDropped")
        val fps = getDoubleMember(members, "framesPerSecond")
        val width = getLongMember(members, "frameWidth")
        val height = getLongMember(members, "frameHeight")
        val qualityReason = members["qualityLimitationReason"] as? String
        val codecId = members["codecId"] as? String
        val codecMime = codecId?.let { statsMap[it]?.members?.get("mimeType") as? String }

        val timestampMs = (outboundVideo.timestampUs / 1000.0).toLong()
        val bitrateKbps =
            if (bytesSent != null && lastStatsBytesSent != null && lastStatsTimestampMs != null
            ) {
                val deltaBytes = bytesSent - lastStatsBytesSent!!
                val deltaMs = timestampMs - lastStatsTimestampMs!!
                if (deltaMs > 0 && deltaBytes >= 0) {
                    ((deltaBytes * 8.0) / deltaMs).toInt()
                } else {
                    null
                }
            } else {
                null
            }
        lastStatsBytesSent = bytesSent
        lastStatsTimestampMs = timestampMs

        val candidatePair =
            statsMap.values.firstOrNull { stats ->
                stats.type == "candidate-pair" &&
                        getStringMember(stats.members, "state") == "succeeded" &&
                        getBooleanMember(stats.members, "nominated", false)
            }
        val rttMs =
            candidatePair
                ?.let { getDoubleMember(it.members, "currentRoundTripTime") }
                ?.times(1000.0)
        val availableOutKbps =
            candidatePair
                ?.let { getDoubleMember(it.members, "availableOutgoingBitrate") }
                ?.div(1000.0)

        val parts = ArrayList<String>(8)
        codecMime?.let { parts.add("codec=$it") }
        bitrateKbps?.let { parts.add("send=${it}kbps") }
        if (width != null && height != null) {
            parts.add("res=${width}x$height")
        }
        fps?.let { parts.add("fps=${String.format(Locale.US, "%.1f", it)}") }
        framesSent?.let { parts.add("sent=$it") }
        framesDropped?.let { parts.add("dropped=$it") }
        rttMs?.let { parts.add("rtt=${String.format(Locale.US, "%.0f", it)}ms") }
        availableOutKbps?.let { parts.add("avail=${String.format(Locale.US, "%.0f", it)}kbps") }
        qualityReason?.let { parts.add("qlim=$it") }

        if (parts.isNotEmpty()) {
            Log.d(TAG, "Stats: ${parts.joinToString(" | ")}")
        }

        val primarySessionIdSnapshot: String?
        val primaryIceState: PeerConnection.IceConnectionState?
        synchronized(streamLock) {
            primarySessionIdSnapshot = primarySessionId
            primaryIceState = primarySessionIdSnapshot?.let { sessionLivenessStates[it]?.iceState }
        }
        val nextZeroSentStatsIntervals =
            nextZeroSentStatsIntervalCount(
                currentCount = consecutiveZeroSentStatsIntervals,
                iceState = primaryIceState,
                framesSent = framesSent,
            )
        if (shouldWarnForZeroSentStats(nextZeroSentStatsIntervals)) {
            Log.w(
                TAG,
                "Stream is connected but no video frames have been sent for two stats intervals (session=$primarySessionIdSnapshot iceState=$primaryIceState sent=${framesSent ?: "null"} bitrateKbps=${bitrateKbps ?: "null"})",
            )
        }
        consecutiveZeroSentStatsIntervals = nextZeroSentStatsIntervals
    }

    private fun isVideoStats(members: Map<String, Any>): Boolean {
        val kind = (members["kind"] ?: members["mediaType"]) as? String
        return kind == "video"
    }

    private fun getStringMember(members: Map<String, Any>, key: String): String? {
        return members[key] as? String
    }

    private fun getBooleanMember(
        members: Map<String, Any>,
        key: String,
        default: Boolean
    ): Boolean {
        return when (val value = members[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: default
            else -> default
        }
    }

    private fun getLongMember(members: Map<String, Any>, key: String): Long? {
        return when (val value = members[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun getDoubleMember(members: Map<String, Any>, key: String): Double? {
        return when (val value = members[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun sendOffer(sdp: String, sessionId: String) {
        val json =
            JSONObject().apply {
                put("id", outgoingMessageId.getAndIncrement())
                put("method", "webrtc/offer")
                put(
                    "params",
                    JSONObject().apply {
                        put("sdp", sdp)
                        put("sessionId", sessionId)
                    }
                )
            }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun sendIceCandidate(candidate: IceCandidate, sessionId: String) {
        val json =
            JSONObject().apply {
                put("id", outgoingMessageId.getAndIncrement())
                put("method", "webrtc/ice")
                put(
                    "params",
                    JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("sessionId", sessionId)
                    }
                )
            }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun queueOutgoingIceCandidateLocked(candidate: IceCandidate) {
        pendingOutgoingIceCandidates.add(candidate)
        if (pendingOutgoingIceCandidates.size <= MAX_OUTGOING_ICE_CANDIDATES) return
        while (pendingOutgoingIceCandidates.size > MAX_OUTGOING_ICE_CANDIDATES) {
            pendingOutgoingIceCandidates.poll()
        }
    }

    private fun queueOutgoingIceCandidateLocked(
        session: SecondarySession,
        candidate: IceCandidate
    ) {
        session.pendingOutgoingIceCandidates.add(candidate)
        if (session.pendingOutgoingIceCandidates.size <= MAX_OUTGOING_ICE_CANDIDATES) return
        while (session.pendingOutgoingIceCandidates.size > MAX_OUTGOING_ICE_CANDIDATES) {
            session.pendingOutgoingIceCandidates.poll()
        }
    }

    private fun drainOutgoingIceCandidatesLocked(): List<IceCandidate> {
        if (pendingOutgoingIceCandidates.isEmpty()) return emptyList()
        val drained = ArrayList<IceCandidate>()
        while (true) {
            val candidate = pendingOutgoingIceCandidates.poll() ?: break
            drained.add(candidate)
        }
        return drained
    }

    private fun drainOutgoingIceCandidatesLocked(session: SecondarySession): List<IceCandidate> {
        if (session.pendingOutgoingIceCandidates.isEmpty()) return emptyList()
        val drained = ArrayList<IceCandidate>()
        while (true) {
            val candidate = session.pendingOutgoingIceCandidates.poll() ?: break
            drained.add(candidate)
        }
        return drained
    }

    private fun enableOutgoingIceAndDrainLocked(): List<IceCandidate> {
        canSendOutgoingIceCandidates = true
        return drainOutgoingIceCandidatesLocked()
    }

    private fun enableOutgoingIceAndDrainLocked(session: SecondarySession): List<IceCandidate> {
        session.canSendOutgoingIceCandidates = true
        return drainOutgoingIceCandidatesLocked(session)
    }

    fun handleKeepAlive(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(streamLock) {
            if (!isTrackedSessionLocked(sessionId)) return
            val state = ensureSessionLivenessStateLocked(sessionId)
            state.lastLivenessAtMs = SystemClock.elapsedRealtime()
            state.runnable?.let { stopHandler.removeCallbacks(it) }
            val runnable = Runnable { handleSessionLivenessTimeout(sessionId) }
            state.runnable = runnable
            stopHandler.postDelayed(runnable, KEEP_ALIVE_TIMEOUT_MS)
        }
    }

    fun handleRequestFrame(sessionId: String) {
        if (sessionId.isBlank()) return
        val shouldLogFirst =
            synchronized(streamLock) {
                requestFrameLoggedSessions.add(sessionId)
            }
        if (shouldLogFirst) {
            Log.i(TAG, "First requestFrame received for session=$sessionId")
        }
        handleKeepAlive(sessionId)
        val nowMs = SystemClock.elapsedRealtime()
        var scheduledRequest: PendingKeyframeRequest? = null
        val decision =
            synchronized(streamLock) {
                val generation = streamGeneration.get()
                val pendingRequest = pendingKeyframeRequest
                val scheduleDecision =
                    planKeyframeRequestSchedule(
                        streamActive = peerConnection != null || secondarySessions.isNotEmpty(),
                        pendingGeneration = pendingRequest?.generation,
                        pendingSessionId = pendingRequest?.sessionId,
                        currentGeneration = generation,
                        requestedSessionId = sessionId,
                        nowMs = nowMs,
                        lastKeyframeRequestStartedAtMs = lastKeyframeRequestStartedAtMs,
                        minIntervalMs = KEYFRAME_REQUEST_MIN_INTERVAL_MS,
                    )
                if (scheduleDecision.disposition == KeyframeRequestScheduleDisposition.SCHEDULE) {
                    pendingRequest?.let { keyframeHandler.removeCallbacks(it.runnable) }
                    val runnable = Runnable { executeQueuedKeyframeRequest(sessionId, generation) }
                    scheduledRequest =
                        PendingKeyframeRequest(
                            sessionId = sessionId,
                            generation = generation,
                            runnable = runnable,
                        )
                    pendingKeyframeRequest = scheduledRequest
                }
                scheduleDecision
            }

        when (decision.disposition) {
            KeyframeRequestScheduleDisposition.SKIP_NO_STREAM -> {
                Log.i(TAG, "RequestFrame received but no active stream; awaiting offer/connection")
            }

            KeyframeRequestScheduleDisposition.COALESCE -> {
                Log.d(TAG, "Coalesced requestFrame keyframe request for session=$sessionId")
            }

            KeyframeRequestScheduleDisposition.SCHEDULE -> {
                val logPrefix = if (decision.replacesPending) "Replaced" else "Scheduled"
                Log.d(
                    TAG,
                    "$logPrefix requestFrame keyframe request for session=$sessionId delayMs=${decision.delayMs}",
                )
                scheduledRequest?.let {
                    keyframeHandler.postDelayed(it.runnable, decision.delayMs)
                }
            }
        }
    }

    fun requestKeyFrame() {
        val sessionId =
            synchronized(streamLock) {
                primarySessionId?.takeIf { it.isNotBlank() }
                    ?: streamRequestId?.takeIf { it.isNotBlank() }
                    ?: secondarySessions.keys.firstOrNull()
            }
        if (sessionId.isNullOrBlank()) {
            Log.i(TAG, "requestKeyFrame called but no tracked session is active")
            return
        }
        handleRequestFrame(sessionId)
    }

    private fun handleSessionLivenessTimeout(sessionId: String) {
        val nowMs = SystemClock.elapsedRealtime()
        var shouldStop = false
        var reschedule: Runnable? = null
        var peerHealthy = false
        var iceState: PeerConnection.IceConnectionState? = null
        var controlState: DataChannel.State? = null
        var lastLivenessAtMs: Long? = null
        synchronized(streamLock) {
            val state = sessionLivenessStates[sessionId] ?: return
            state.runnable = null
            if (!isTrackedSessionLocked(sessionId)) {
                sessionLivenessStates.remove(sessionId)
                return
            }
            peerHealthy = isPeerHealthyForLiveness(state.iceState, state.controlState)
            iceState = state.iceState
            controlState = state.controlState
            val decision =
                evaluateSessionLivenessTimeout(
                    peerHealthy = peerHealthy,
                )
            if (decision.shouldStop) {
                sessionLivenessStates.remove(sessionId)
                shouldStop = true
            } else {
                lastLivenessAtMs = state.lastLivenessAtMs.takeIf { it > 0L }
                val runnable = Runnable { handleSessionLivenessTimeout(sessionId) }
                state.runnable = runnable
                reschedule = runnable
            }
        }
        if (shouldStop) {
            Log.w(
                TAG,
                "KeepAlive timeout for sessionId=$sessionId - stopping stream (peerHealthy=$peerHealthy iceState=$iceState controlState=$controlState)",
            )
            stopStream(sessionId, "keep_alive_timeout")
            return
        }
        val staleForMs = lastLivenessAtMs?.let { nowMs - it } ?: KEEP_ALIVE_TIMEOUT_MS
        Log.i(
            TAG,
            "KeepAlive timeout for sessionId=$sessionId but peer is healthy; keeping stream alive (iceState=$iceState controlState=$controlState staleForMs=$staleForMs)",
        )
        reschedule?.let { stopHandler.postDelayed(it, KEEP_ALIVE_TIMEOUT_MS) }
    }

    private fun cancelKeepAlive(sessionId: String) {
        synchronized(streamLock) {
            clearSessionLivenessStateLocked(sessionId)
        }
    }

    private fun cancelAllKeepAlives() {
        synchronized(streamLock) {
            sessionLivenessStates.keys.toList().forEach { sessionId ->
                clearSessionLivenessStateLocked(sessionId)
            }
        }
    }

    private fun sendStreamError(error: String, message: String) {
        try {
            val requestId = getStreamRequestId()
            val json =
                JSONObject().apply {
                    put("method", "stream/error")
                    put(
                        "params",
                        JSONObject().apply {
                            put("error", error)
                            put("message", message)
                            if (requestId != null) {
                                put("sessionId", requestId)
                            }
                        }
                    )
                }
            val sent = reverseConnectionService?.sendText(json.toString()) == true
            if (sent) {
                Log.d(TAG, "Sent stream/error: $error - $message")
            } else {
                Log.d(TAG, "Stream/error not sent (socket closed)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stream error", e)
        }
    }

    fun notifyStreamStopped(reason: String? = null, sessionId: String? = null) {
        if (!sessionId.isNullOrBlank()) {
            sendStreamStopped(reason, sessionId)
            return
        }
        val sessionIds = getActiveSessionIds()
        if (sessionIds.isEmpty()) {
            getStreamRequestId()?.let { sendStreamStopped(reason, it) }
            return
        }
        sessionIds.forEach { sendStreamStopped(reason, it) }
    }

    fun notifyStreamStoppedAsync(reason: String? = null, sessionId: String? = null) {
        val targetIds =
            if (!sessionId.isNullOrBlank()) {
                listOf(sessionId)
            } else {
                val active = getActiveSessionIds()
                if (active.isEmpty()) {
                    getStreamRequestId()?.let { listOf(it) } ?: emptyList()
                } else {
                    active
                }
            }
        if (targetIds.isEmpty()) return
        stopHandler.post {
            targetIds.forEach { sendStreamStopped(reason, it) }
        }
    }

    private fun queuePendingStreamStopped(reason: String?, requestId: Any?) {
        synchronized(streamLock) {
            pendingStreamStop = PendingStreamStop(reason, requestId)
        }
    }

    private fun flushPendingStreamStopped() {
        val pending =
            synchronized(streamLock) {
                pendingStreamStop
            }
                ?: return
        if (trySendStreamStopped(pending.reason, pending.sessionId)) {
            synchronized(streamLock) {
                if (pendingStreamStop == pending) {
                    pendingStreamStop = null
                }
            }
        }
    }

    private fun trySendStreamStopped(reason: String?, requestId: Any?): Boolean {
        return try {
            val json =
                JSONObject().apply {
                    put("method", "stream/stopped")
                    put(
                        "params",
                        JSONObject().apply {
                            if (!reason.isNullOrBlank()) {
                                put("reason", reason)
                            }
                            if (requestId != null) {
                                put("sessionId", requestId)
                            }
                        }
                    )
                }
            val sent = reverseConnectionService?.sendText(json.toString()) == true
            if (sent) {
                Log.d(TAG, "Sent stream/stopped${if (reason.isNullOrBlank()) "" else ": $reason"}")
            } else {
                Log.d(TAG, "Stream/stopped not sent (socket closed); queued")
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stream stopped", e)
            false
        }
    }

    private fun sendStreamStopped(reason: String?, requestId: Any?) {
        val sent = trySendStreamStopped(reason, requestId)
        if (!sent) {
            queuePendingStreamStopped(reason, requestId)
        }
    }

    private class FilteringVideoEncoderFactory(
        private val delegate: VideoEncoderFactory,
        allowedCodecNames: Set<String>,
    ) : VideoEncoderFactory {
        private val allowed = allowedCodecNames.map { it.uppercase(Locale.US) }.toSet()

        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
            return if (isAllowed(info)) delegate.createEncoder(info) else null
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            return delegate.getSupportedCodecs().filter { isAllowed(it) }.toTypedArray()
        }

        override fun getImplementations(): Array<VideoCodecInfo> {
            return delegate.getImplementations().filter { isAllowed(it) }.toTypedArray()
        }

        fun codecListLabel(): String {
            return delegate.getSupportedCodecs()
                .filter { isAllowed(it) }
                .joinToString(", ") { it.name }
        }

        private fun isAllowed(info: VideoCodecInfo): Boolean {
            return allowed.contains(info.name.uppercase(Locale.US))
        }
    }

    open class CustomPeerConnectionObserver : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(p0: IceCandidate) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    private class LoggingDataChannelObserver(
        private val channel: DataChannel,
        private val label: String,
        private val delegate: DataChannel.Observer?,
        private val onStateChanged: ((DataChannel.State?) -> Unit)? = null,
    ) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {
            delegate?.onBufferedAmountChange(previousAmount)
        }

        override fun onStateChange() {
            val state = channel.state()
            Log.i(TAG, "Control data channel state changed: $label state=$state")
            onStateChanged?.invoke(state)
            delegate?.onStateChange()
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            delegate?.onMessage(buffer)
        }
    }

    open class SimpleSdpObserver(private val name: String) : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {
            Log.e(TAG, "$name onCreateFailure: $p0")
        }

        override fun onSetFailure(p0: String?) {
            Log.e(TAG, "$name onSetFailure: $p0")
        }
    }

    private fun releaseResources() {
        Log.i(TAG, "Releasing all WebRtcManager resources")
        cancelAllKeepAlives()
        val resources: StreamResources
        val staleSecondary: List<SecondarySession>
        synchronized(streamLock) {
            streamGeneration.incrementAndGet()
            primarySessionId = null
            streamRequestId = null
            pendingKeyframeRequest = null
            staleSecondary = secondarySessions.values.toList()
            secondarySessions.clear()
            resources = detachStreamResourcesLocked()
        }
        staleSecondary.forEach { cleanupSecondarySession(it) }
        cleanupStreamResources(resources)
        keyframeHandler.removeCallbacksAndMessages(null)

        try {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing peerConnectionFactory", e)
        }

        try {
            eglBase.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing eglBase", e)
        }

        try {
            stopThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error quitting stopThread", e)
        }

        try {
            keyframeThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error quitting keyframeThread", e)
        }

        try {
            statsThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error quitting statsThread", e)
        }

        try {
            frameTapThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error quitting frameTapThread", e)
        }

        Log.i(TAG, "WebRtcManager resources released")
    }

    private fun refreshCaptureFastStateLocked() {
        captureFastState =
            buildCaptureFastState(
                captureActive = screenCapturer != null,
                videoTrackReady = videoTrack != null,
                captureSessionMode = captureSessionMode,
                generation = captureLogStreamId,
            )
    }
}
