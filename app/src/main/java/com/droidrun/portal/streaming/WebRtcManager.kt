package com.droidrun.portal.streaming

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.droidrun.portal.service.ReverseConnectionService
import com.droidrun.portal.service.ScreenCaptureService
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
        private const val STATS_INTERVAL_MS = 5000L
        private const val ENABLE_VERBOSE_STREAM_STATS_LOG = false
        private const val ENABLE_EXTENSIVE_LAG_LOGS = true
        private const val ENABLE_DECISION_LOGS = false
        private const val DECISION_LOG_MIN_INTERVAL_MS = 10_000L
        private const val LOW_SEVERITY_LAG_LOG_MIN_INTERVAL_MS = 20_000L
        private const val LAG_DIAGNOSTIC_WARN_FPS_RATIO = 0.80
        private const val LAG_DIAGNOSTIC_ENCODE_BUDGET_RATIO = 0.90
        private const val LAG_DIAGNOSTIC_LOW_SEND_KBPS = 300
        private const val LAG_DIAGNOSTIC_REMOTE_LOSS_WARN_PCT = 2.0
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_OUTGOING_ICE_CANDIDATES = 50
        private const val MAX_ICE_RESTARTS = 1

        // Treat DISCONNECTED as transient first; reset peer only if it persists.
        private const val ICE_DISCONNECTED_GRACE_MS = 3_000L
        private const val LOW_FPS_RATIO_TRIGGER = 0.70
        private const val LOW_FPS_RATIO_RECOVERY = 0.95
        private const val LOW_FPS_STREAK_THRESHOLD = 2
        private const val LOW_FPS_STEP_DOWN_PERCENT = 15
        private const val LOW_FPS_MIN_BASELINE_RATIO = 0.45
        private const val CAPTURE_RECOVERY_STREAK_THRESHOLD = 6
        private const val CAPTURE_LADDER_STEP1_LONG_EDGE = 1120
        private const val CAPTURE_MIN_FPS = 15
        private const val CAPTURE_RECOVERY_FPS_CAP = 24

        // Global target is 24fps; adaptive controls can still downshift under load.
        private const val BALANCED_PRESET_TARGET_FPS = 24
        private const val CAPTURE_LADDER_DOWNSHIFT_TRIGGER_COUNT = 2
        private const val CAPTURE_SEVERE_LOW_FPS_RATIO = 0.45
        private const val CAPTURE_LADDER_CHANGE_COOLDOWN_MS = 20_000L
        private const val CAPTURE_RECOVERY_HOLD_MS = 60_000L

        // Drop stale capture frames, but keep enough headroom to avoid over-dropping on jittery devices.
        private const val STALE_CAPTURE_FRAME_AGE_MS = 240L
        private const val STALE_CAPTURE_DROP_LOG_EVERY = 5_000L
        private const val ENABLE_CAPTURE_LADDER = true
        private const val ENABLE_SDP_MIN_BITRATE_HINT = true
        private const val ENABLE_SDP_START_BITRATE_HINT = true
        private const val ENABLE_STARTUP_PREFER_RESOLUTION = true
        private const val STARTUP_PREFER_RESOLUTION_MS = 250L
        private const val ENABLE_STREAMING_LOW_LATENCY_ENCODER = true
        private const val STREAMING_STARTUP_KEYFRAME_INTERVAL_MS = 250L
        private const val STREAMING_RECOVERY_KEYFRAME_INTERVAL_MS = 3_000L
        private const val STREAMING_STABLE_KEYFRAME_INTERVAL_MS = 16_000L
        private const val STALL_WATCHDOG_WINDOW_MS = 10_000L
        private const val STALL_WATCHDOG_COOLDOWN_MS = 20_000L
        private const val STALL_WATCHDOG_MAX_RECOVERIES = 4
        private const val STALL_WATCHDOG_CAPTURE_DELTA_MIN = 15L
        private const val STALL_WATCHDOG_MAX_SEND_KBPS = 150
        private const val STALL_WATCHDOG_MAX_FPS = 2.0
        private const val STALL_WATCHDOG_MIN_AVAIL_KBPS = 1_200.0
        private const val STARTUP_RAMP_DURATION_MS = 250L
        private const val STARTUP_RAMP_INITIAL_MAX_RATIO = 0.70

        // Keep initial quality high; disable conservative startup bitrate ramp.
        private const val ENABLE_STARTUP_RAMP = false
        private const val LOW_FPS_FALLBACK_STARTUP_GRACE_MS = 250L
        private const val RTT_SPIKE_THRESHOLD_MS = 70.0
        private const val RTT_RECOVERY_THRESHOLD_MS = 35.0
        private const val RTT_SPIKE_STREAK_THRESHOLD = 2
        private const val RTT_RECOVERY_STREAK_THRESHOLD = 4
        private const val RTT_BACKOFF_STEP_PERCENT = 15
        private const val RTT_BACKOFF_MAX_STEPS = 3
        private const val RTT_ADAPT_COOLDOWN_MS = 10_000L
        private const val ADAPTIVE_MAX_RECOVERY_STEP_PERCENT = 12
        private const val ADAPTIVE_MAX_RECOVERY_STREAK_THRESHOLD = 3
        private const val ADAPTIVE_MAX_RECOVERY_COOLDOWN_MS = 10_000L
        private const val ADAPTIVE_MAX_RECOVERY_MIN_FPS_RATIO = 0.90
        private const val LOCAL_FPS_GOVERNOR_TRIGGER_FPS = 14.0
        private const val LOCAL_FPS_GOVERNOR_TRIGGER_STREAK = 2
        private const val LOCAL_FPS_GOVERNOR_CAP_FPS = 18
        private const val LOCAL_FPS_GOVERNOR_HOLD_MS = 30_000L
        private const val LOCAL_FPS_GOVERNOR_RTT_HEALTHY_MAX_MS = 30.0

        // If source FPS falls below this for consecutive stats windows, downshift capture once.
        private const val SOURCE_FPS_DOWNSHIFT_TRIGGER_FPS = 12.0
        private const val SOURCE_FPS_DOWNSHIFT_TRIGGER_STREAK = 2

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
    }

    private data class PeerResources(
        val peerConnection: PeerConnection?,
        val controlChannel: DataChannel?
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

    private data class PendingIceDisconnect(
        val streamId: Int,
        val reason: String,
        val runnable: Runnable,
    )

    private data class BitratePlan(
        val minBps: Int,
        val startBps: Int,
        val maxBps: Int,
    )

    private data class CaptureProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
    ) {
        fun label(): String = "${width}x$height@$fps"
    }

    private data class PendingCaptureLadderChange(
        val fromIndex: Int,
        val toIndex: Int,
        val triggerFps: Double,
        val reason: String,
    )

    private data class DevicePerformancePreset(
        val name: String,
        val maxLongEdge: Int,
        val maxFps: Int,
        val bitrateScale: Double,
        val preferLowLatencyEncoder: Boolean,
    )

    private data class StallRecoveryRequest(
        val width: Int,
        val height: Int,
        val fps: Int,
        val waitForOffer: Boolean,
    )

    private data class LagDiagnosticState(
        val targetFps: Int,
        val senderMaxFps: Int,
        val senderFpsCapped: Boolean,
        val localFpsGovernorActive: Boolean,
        val localFpsGovernorLeaseMs: Long,
        val lowFpsStreak: Int,
        val sourceFpsLowStreak: Int,
        val captureDownshiftTriggerCount: Int,
        val captureLadderIndex: Int,
        val captureLadderLastIndex: Int,
        val captureProfileLabel: String?,
        val adaptiveMaxKbps: Int?,
        val startupAdaptiveMaxKbps: Int?,
        val rttAdaptiveMaxKbps: Int?,
        val bitratePlan: BitratePlan,
    )

    private var peerConnectionFactory: PeerConnectionFactory? = null

    @Volatile
    private var peerConnection: PeerConnection? = null
    private val eglBase: EglBase by lazy { EglBase.create() }

    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var captureObserver: CapturerObserver? = null

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
    private val devicePerformancePreset: DevicePerformancePreset by lazy { detectDevicePerformancePreset() }

    private val stopThread =
        HandlerThread("WebRtcStop", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val stopHandler = Handler(stopThread.looper)
    private val statsThread =
        HandlerThread("WebRtcStats", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val statsHandler = Handler(statsThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamGeneration = AtomicInteger(0)

    @Volatile
    private var streamRequestId: String? = null

    @Volatile
    private var pendingIceServers: List<PeerConnection.IceServer>? = null

    @Volatile
    private var requestedMinBitrateBps: Int? = null

    @Volatile
    private var requestedStartBitrateBps: Int? = null

    @Volatile
    private var requestedMaxBitrateBps: Int? = null

    @Volatile
    private var adaptiveMaxBitrateBps: Int? = null

    @Volatile
    private var startupAdaptiveMaxBitrateBps: Int? = null

    @Volatile
    private var rttAdaptiveMaxBitrateBps: Int? = null
    private var lowFpsStreak = 0
    private var lowFpsFallbackSteps = 0
    private var highFpsRecoveryStreak = 0
    private var captureDownshiftTriggerCount = 0
    private var streamStartElapsedRealtimeMs = 0L
    private var rttSpikeStreak = 0
    private var rttRecoveryStreak = 0
    private var rttBackoffSteps = 0
    private var lastRttAdaptChangeElapsedMs = 0L
    private var adaptiveMaxRecoveryStreak = 0
    private var lastAdaptiveMaxRecoveryChangeElapsedMs = 0L
    private var localFpsGovernorLowStreak = 0
    private var localFpsGovernorCapUntilElapsedMs = 0L
    private var sourceFpsLowStreak = 0
    private var captureBaselineProfile: CaptureProfile? = null
    private var captureLadderProfiles: List<CaptureProfile> = emptyList()
    private var captureLadderIndex = 0
    private var lastCaptureLadderChangeMs = 0L
    private var lastCaptureDownshiftMs = 0L
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureFps = 0

    @Volatile
    private var activeCaptureTargetFps = BALANCED_PRESET_TARGET_FPS
    private val localDroppedCaptureFrames = AtomicLong(0)
    private val lastForwardedCaptureTimestampNs = AtomicLong(Long.MIN_VALUE)
    private var connectionToastStreamId = 0
    private var statsRunnable: Runnable? = null
    private var lastStatsBytesSent: Long? = null
    private var lastStatsTimestampMs: Long? = null
    private var lastStatsFramesEncoded: Long? = null
    private var lastStatsTotalEncodeTimeSec: Double? = null
    private var lastStatsFramesSent: Long? = null
    private var lastStatsCapturedFramesForwarded: Long? = null
    private var lastStatsLocalDroppedCaptureFrames: Long? = null
    private var lastStatsSourceDropped: Long? = null
    private var lastStatsRemotePacketsLost: Long? = null
    private var lastStatsRemoteNackCount: Long? = null
    private var lastStatsRemotePliCount: Long? = null
    private var lastStatsRemoteFirCount: Long? = null
    private var stallSnapshotTimestampMs: Long? = null
    private var stallSnapshotFramesSent: Long? = null
    private var stallSnapshotCapturedFrames: Long = 0
    private var lastStallRecoveryAttemptMs = 0L
    private var stallRecoveryAttempts = 0
    private var stallRecoveryInProgress = false
    private val capturedFramesForwarded = AtomicLong(0)
    private var lastLowFpsDecisionLogElapsedMs = 0L
    private var lastSourceFpsDecisionLogElapsedMs = 0L
    private var lastLocalFpsGovernorDecisionLogElapsedMs = 0L
    private var lastRttAdaptDecisionLogElapsedMs = 0L
    private var lastStallWatchdogDecisionLogElapsedMs = 0L
    private var lastLowSeverityLagLogElapsedMs = 0L

    @Volatile
    private var controlChannel: DataChannel? = null
    private var streamingControlHandler: ScrcpyControlChannel? = null
    private var idleStopRunnable: Runnable? = null

    @Volatile
    private var pendingStreamStop: PendingStreamStop? = null
    private var pendingIceDisconnect: PendingIceDisconnect? = null

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

    fun isCurrentSession(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        return streamRequestId == sessionId
    }

    fun setPendingIceServers(servers: List<PeerConnection.IceServer>?) {
        pendingIceServers = servers
    }

    fun consumePendingIceServers(): List<PeerConnection.IceServer>? {
        val servers = pendingIceServers
        pendingIceServers = null
        return servers
    }

    fun setStreamEncoderOverrides(
        minBitrateBps: Int?,
        startBitrateBps: Int?,
        maxBitrateBps: Int?,
    ) {
        var normalizedMax = maxBitrateBps?.coerceIn(300_000, 20_000_000)
        var normalizedMin = minBitrateBps?.coerceIn(100_000, normalizedMax ?: 20_000_000)
        if (normalizedMax != null && normalizedMin != null && normalizedMin > normalizedMax) {
            normalizedMin = normalizedMax
        }
        var normalizedStart =
            startBitrateBps?.coerceIn(
                normalizedMin ?: 100_000,
                normalizedMax ?: 20_000_000,
            )
        if (normalizedMax != null && normalizedStart != null && normalizedStart > normalizedMax) {
            normalizedStart = normalizedMax
        }
        if (normalizedMin != null && normalizedStart != null && normalizedStart < normalizedMin) {
            normalizedStart = normalizedMin
        }

        synchronized(streamLock) {
            requestedMinBitrateBps = normalizedMin
            requestedStartBitrateBps = normalizedStart
            requestedMaxBitrateBps = normalizedMax
            resetAdaptiveBitrateStateLocked()
        }

        Log.i(
            TAG,
            "Encoder overrides: min=${normalizedMin ?: "auto"} start=${normalizedStart ?: "auto"} max=${normalizedMax ?: "auto"}",
        )
    }

    private fun resetAdaptiveBitrateStateLocked() {
        adaptiveMaxBitrateBps = null
        startupAdaptiveMaxBitrateBps = null
        rttAdaptiveMaxBitrateBps = null
        lowFpsStreak = 0
        lowFpsFallbackSteps = 0
        highFpsRecoveryStreak = 0
        captureDownshiftTriggerCount = 0
        streamStartElapsedRealtimeMs = 0L
        rttSpikeStreak = 0
        rttRecoveryStreak = 0
        rttBackoffSteps = 0
        lastRttAdaptChangeElapsedMs = 0L
        adaptiveMaxRecoveryStreak = 0
        lastAdaptiveMaxRecoveryChangeElapsedMs = 0L
        localFpsGovernorLowStreak = 0
        localFpsGovernorCapUntilElapsedMs = 0L
        sourceFpsLowStreak = 0
    }

    private fun resetStallWatchdogStateLocked(resetRecoveryBudget: Boolean = true) {
        stallSnapshotTimestampMs = null
        stallSnapshotFramesSent = null
        stallSnapshotCapturedFrames = capturedFramesForwarded.get()
        stallRecoveryInProgress = false
        if (resetRecoveryBudget) {
            lastStallRecoveryAttemptMs = 0L
            stallRecoveryAttempts = 0
        }
    }

    private fun resetCaptureLadderStateLocked() {
        captureBaselineProfile = null
        captureLadderProfiles = emptyList()
        captureLadderIndex = 0
        activeCaptureTargetFps = BALANCED_PRESET_TARGET_FPS
        lastCaptureLadderChangeMs = 0L
        lastCaptureDownshiftMs = 0L
        captureDownshiftTriggerCount = 0
        sourceFpsLowStreak = 0
    }

    private fun resetCapturedFrameDropState() {
        localDroppedCaptureFrames.set(0)
        capturedFramesForwarded.set(0)
        lastForwardedCaptureTimestampNs.set(Long.MIN_VALUE)
    }

    private fun initializeCaptureLadderLocked(
        width: Int,
        height: Int,
        fps: Int,
        resetStallRecoveryBudget: Boolean = true,
    ) {
        val baseline = sanitizeCaptureProfile(width, height, fps)
        captureBaselineProfile = baseline
        captureLadderProfiles = buildCaptureLadder(baseline)
        captureLadderIndex = 0
        activeCaptureTargetFps = baseline.fps
        highFpsRecoveryStreak = 0
        captureDownshiftTriggerCount = 0
        lastCaptureLadderChangeMs = 0L
        lastCaptureDownshiftMs = 0L
        sourceFpsLowStreak = 0
        resetCapturedFrameDropState()
        streamStartElapsedRealtimeMs = SystemClock.elapsedRealtime()
        lastRttAdaptChangeElapsedMs = streamStartElapsedRealtimeMs
        resetStallWatchdogStateLocked(resetStallRecoveryBudget)
        Log.i(
            TAG,
            "Capture ladder: ${captureLadderProfiles.joinToString(" -> ") { it.label() }}",
        )
    }

    fun isStreamActive(): Boolean = synchronized(streamLock) { peerConnection != null }

    fun isCaptureActive(): Boolean = synchronized(streamLock) { screenCapturer != null }

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
                val lowLatencyEnabled =
                    ENABLE_STREAMING_LOW_LATENCY_ENCODER &&
                            devicePerformancePreset.preferLowLatencyEncoder
                val selectedEncoderFactory: VideoEncoderFactory =
                    if (lowLatencyEnabled) {
                        LowLatencyVideoEncoderFactory(
                            filteredEncoderFactory,
                            startupWindowMs = STARTUP_RAMP_DURATION_MS,
                            startupKeyframeIntervalMs = STREAMING_STARTUP_KEYFRAME_INTERVAL_MS,
                            recoveryKeyframeIntervalMs = STREAMING_RECOVERY_KEYFRAME_INTERVAL_MS,
                            stableKeyframeIntervalMs = STREAMING_STABLE_KEYFRAME_INTERVAL_MS,
                        )
                    } else {
                        filteredEncoderFactory
                    }
                Log.i(TAG, "Enabled video encoders: ${filteredEncoderFactory.codecListLabel()}")
                Log.i(
                    TAG,
                    "Low-latency encoder wrapper: enabled=$lowLatencyEnabled preset=${devicePerformancePreset.name} keyframeMs=${STREAMING_STARTUP_KEYFRAME_INTERVAL_MS}/${STREAMING_RECOVERY_KEYFRAME_INTERVAL_MS}/${STREAMING_STABLE_KEYFRAME_INTERVAL_MS}",
                )
                PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .setVideoEncoderFactory(selectedEncoderFactory)
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
        cancelIdleStop()
        val requestedCapture = sanitizeCaptureProfile(width, height, fps)
        val effectiveCapture = applyDevicePresetToCaptureProfile(requestedCapture)
        Log.i(
            TAG,
            "Starting WebRTC Stream: requested=${requestedCapture.label()} effective=${effectiveCapture.label()} preset=${devicePerformancePreset.name}, waitForOffer=$waitForOffer"
        )
        val effectiveIceServers = iceServers ?: consumePendingIceServers()
        val streamId = streamGeneration.incrementAndGet()
        val staleResources = synchronized(streamLock) { detachStreamResourcesLocked() }
        cleanupStreamResources(staleResources)
        synchronized(streamLock) {
            waitingForOffer = waitForOffer
            iceRestartAttempts = 0
            resetAdaptiveBitrateStateLocked()
            initializeCaptureLadderLocked(
                effectiveCapture.width,
                effectiveCapture.height,
                effectiveCapture.fps,
                resetStallRecoveryBudget = true,
            )
            createPeerConnection(effectiveIceServers, streamId)
            createVideoTrack(
                permissionResultData,
                effectiveCapture.width,
                effectiveCapture.height,
                effectiveCapture.fps,
                streamId,
            )

            videoTrack?.let { track ->
                val sender = peerConnection?.addTrack(track, listOf(VIDEO_TRACK_ID))
                configureVideoSender(
                    sender,
                    effectiveCapture.width,
                    effectiveCapture.height,
                    effectiveCapture.fps,
                )
            }

            startStatsLogging(streamId)
            if (!waitForOffer) createOffer(streamId)
            else sendStreamReady()
        }
    }

    fun startStreamWithExistingCapture(
        width: Int,
        height: Int,
        fps: Int,
        waitForOffer: Boolean = false,
        resetStallRecoveryBudget: Boolean = true,
    ) {
        cancelIdleStop()
        val requestedCapture = sanitizeCaptureProfile(width, height, fps)
        val effectiveCapture = applyDevicePresetToCaptureProfile(requestedCapture)
        Log.i(
            TAG,
            "Restarting WebRTC Stream: requested=${requestedCapture.label()} effective=${effectiveCapture.label()} preset=${devicePerformancePreset.name}, waitForOffer=$waitForOffer",
        )
        val effectiveIceServers = consumePendingIceServers()
        val streamId = streamGeneration.incrementAndGet()
        val stalePeer = synchronized(streamLock) { detachPeerResourcesLocked() }
        cleanupPeerResources(stalePeer)
        updateCaptureFormat(
            effectiveCapture.width,
            effectiveCapture.height,
            effectiveCapture.fps,
        )
        synchronized(streamLock) {
            waitingForOffer = waitForOffer
            iceRestartAttempts = 0
            resetAdaptiveBitrateStateLocked()
            initializeCaptureLadderLocked(
                effectiveCapture.width,
                effectiveCapture.height,
                effectiveCapture.fps,
                resetStallRecoveryBudget = resetStallRecoveryBudget,
            )
            if (videoTrack == null) {
                throw IllegalStateException("No active capture to reuse")
            }
            createPeerConnection(effectiveIceServers, streamId)
            val sender = peerConnection?.addTrack(videoTrack, listOf(VIDEO_TRACK_ID))
            configureVideoSender(
                sender,
                effectiveCapture.width,
                effectiveCapture.height,
                effectiveCapture.fps,
            )
            startStatsLogging(streamId)
            if (!waitForOffer) {
                createOffer(streamId)
            } else {
                sendStreamReady()
            }
        }
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
            activeCaptureTargetFps = fps
        }
    }

    private fun sendStreamReady() {
        val json =
            JSONObject().apply {
                put("method", "stream/ready")
                put("params", JSONObject().apply { put("sessionId", streamRequestId) })
            }
        reverseConnectionService?.sendText(json.toString())
    }

    fun stopStream() {
        cancelIdleStop()
        val resources =
            synchronized(streamLock) {
                streamGeneration.incrementAndGet()
                streamRequestId = null
                detachStreamResourcesLocked()
            }
        cleanupStreamResources(resources)
    }

    fun requestGracefulStop(reason: String = "cloud_stop") {
        val peerResources =
            synchronized(streamLock) {
                if (peerConnection == null) {
                    null
                } else {
                    streamGeneration.incrementAndGet()
                    detachPeerResourcesLocked()
                }
            }

        if (peerResources != null) cleanupPeerResources(peerResources)

        scheduleIdleStop(reason)
    }

    fun stopStreamAsync(onStopped: (() -> Unit)? = null) {
        cancelIdleStop()
        val stopGeneration = streamGeneration.get()
        stopHandler.post {
            val resources =
                synchronized(streamLock) {
                    val currentGeneration = streamGeneration.get()
                    if (stopGeneration != currentGeneration) {
                        null
                    } else {
                        streamGeneration.incrementAndGet()
                        streamRequestId = null
                        detachStreamResourcesLocked()
                    }
                }
            if (resources != null) {
                cleanupStreamResources(resources)
            }
            if (onStopped != null) {
                mainHandler.post { onStopped.invoke() }
            }
        }
    }

    private fun scheduleIdleStop(reason: String) {
        if (!isCaptureActive()) return
        idleStopRunnable?.let { stopHandler.removeCallbacks(it) }
        val runnable = Runnable {
            idleStopRunnable = null
            if (isCaptureActive()) {
                Log.i(TAG, "Idle timeout reached ($reason) - stopping capture")
                ScreenCaptureService.requestStop("idle_timeout")
            }
        }
        idleStopRunnable = runnable
        stopHandler.postDelayed(runnable, IDLE_TIMEOUT_MS)
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

    private fun detectDevicePerformancePreset(): DevicePerformancePreset {
        val socModel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        val signature =
            listOfNotNull(
                socModel,
                Build.HARDWARE,
                Build.BOARD,
                Build.PRODUCT,
                Build.MODEL,
            ).joinToString(" ")
                .lowercase(Locale.US)
        val totalRamGb = estimateDeviceRamGb()
        val h264HardwareEncoders = listHardwareH264Encoders()
        val hasHardwareH264 = h264HardwareEncoders.isNotEmpty()

        val isHighTierSoc =
            listOf(
                "8 gen 3",
                "8 gen 2",
                "8 elite",
                "sm8750",
                "sm8650",
                "sm8550",
                "dimensity 9300",
                "dimensity 9200",
                "tensor g3",
                "tensor g4",
                "a17",
                "a16",
            ).any { signature.contains(it) }
        val isLowTierSoc =
            listOf(
                "exynos 850",
                "helio g35",
                "helio p",
                "snapdragon 4",
                "snapdragon 6",
                "sdm6",
                "mt676",
            ).any { signature.contains(it) }

        val preset =
            when {
                !hasHardwareH264 || totalRamGb <= 4 || isLowTierSoc -> {
                    DevicePerformancePreset(
                        name = "safe",
                        maxLongEdge = 1280,
                        maxFps = BALANCED_PRESET_TARGET_FPS,
                        bitrateScale = 0.78,
                        preferLowLatencyEncoder = true,
                    )
                }

                isHighTierSoc && totalRamGb >= 8 -> {
                    DevicePerformancePreset(
                        name = "performance",
                        maxLongEdge = 1920,
                        maxFps = BALANCED_PRESET_TARGET_FPS,
                        bitrateScale = 1.0,
                        preferLowLatencyEncoder = true,
                    )
                }

                else -> {
                    DevicePerformancePreset(
                        name = "balanced",
                        maxLongEdge = 1280,
                        maxFps = BALANCED_PRESET_TARGET_FPS,
                        bitrateScale = 1.0,
                        preferLowLatencyEncoder = true,
                    )
                }
            }

        Log.i(
            TAG,
            "Device preset selected: ${preset.name} soc=${socModel ?: "unknown"} ramGb=$totalRamGb hwH264=${if (h264HardwareEncoders.isEmpty()) "none" else h264HardwareEncoders.joinToString()}",
        )
        return preset
    }

    private fun estimateDeviceRamGb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        if (memoryInfo.totalMem <= 0L) return 4
        return (memoryInfo.totalMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
    }

    private fun listHardwareH264Encoders(): List<String> {
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { codec ->
                    codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
                }
                .filter { codec ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        !codec.isSoftwareOnly
                    } else {
                        val name = codec.name.lowercase(Locale.US)
                        !name.startsWith("omx.google") &&
                                !name.startsWith("c2.android") &&
                                !name.contains("sw")
                    }
                }
                .map { it.name }
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate H264 encoders", e)
            emptyList()
        }
    }

    private fun applyDevicePresetToCaptureProfile(requested: CaptureProfile): CaptureProfile {
        val preset = devicePerformancePreset
        var adjusted = requested
        if (adjusted.fps > preset.maxFps) {
            adjusted = adjusted.copy(fps = preset.maxFps)
        }
        adjusted = profileForLongEdgeCap(adjusted, preset.maxLongEdge, adjusted.fps)
        return sanitizeCaptureProfile(adjusted.width, adjusted.height, adjusted.fps)
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
        (captureObserver as? StaleFrameDroppingCapturerObserver)?.dispose()
        screenCapturer = null
        videoSource = null
        videoTrack = null
        captureObserver = null
        surfaceTextureHelper = null
        captureWidth = 0
        captureHeight = 0
        captureFps = 0
        resetCaptureLadderStateLocked()
        resetCapturedFrameDropState()
        return resources
    }

    private fun detachPeerResourcesLocked(): PeerResources {
        val resources = PeerResources(peerConnection, controlChannel)
        peerConnection = null
        controlChannel = null
        streamingControlHandler = null
        stopStatsLogging()
        pendingIceCandidates.clear()
        pendingOutgoingIceCandidates.clear()
        canSendOutgoingIceCandidates = false
        isRemoteDescriptionSet = false
        pendingIceDisconnect?.let { stopHandler.removeCallbacks(it.runnable) }
        pendingIceDisconnect = null
        iceRestartAttempts = 0
        waitingForOffer = false
        resetAdaptiveBitrateStateLocked()
        resetStallWatchdogStateLocked()
        return resources
    }

    private fun cleanupStreamResources(resources: StreamResources) {
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
                    pendingOutgoing?.forEach { sendIceCandidate(it) }
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleOffer(sdp: String, sessionId: String) {
        var connection: PeerConnection? = null
        var generation = 0
        synchronized(streamLock) {
            if (peerConnection == null) {
                Log.w(TAG, "handleOffer called but no active PeerConnection")
            } else {
                connection = peerConnection
                generation = streamGeneration.get()
            }
        }
        val activeConnection = connection ?: return

        activeConnection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription-offer") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    synchronized(streamLock) {
                        if (peerConnection !== activeConnection ||
                            streamGeneration.get() != generation
                        ) {
                            Log.w(TAG, "Remote offer set on stale PeerConnection")
                            return
                        }
                        isRemoteDescriptionSet = true
                    }
                    createAnswer(activeConnection, generation, sessionId)
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp)
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
                    val forcedH264 = forceH264(desc.description)
                    val preferredSdp =
                        if (forcedH264 == null) {
                            desc.description
                        } else {
                            Log.i(TAG, "Forcing H264 in answer SDP")
                            forcedH264
                        }
                    val bitratePlan = synchronized(streamLock) { resolveBitratePlanLocked() }
                    val tunedSdp = applyVideoBitrateHints(preferredSdp, bitratePlan)
                    val answer =
                        if (tunedSdp == desc.description) {
                            desc
                        } else {
                            SessionDescription(desc.type, tunedSdp)
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
                    outgoing.forEach { sendIceCandidate(it) }
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
        reverseConnectionService?.sendText(json.toString())
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        var connection: PeerConnection? = null
        var shouldReturn = false
        synchronized(streamLock) {
            if (peerConnection == null) {
                Log.w(TAG, "handleIceCandidate called but no active PeerConnection")
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

    private fun drainPendingIceCandidatesLocked(): List<IceCandidate> {
        if (pendingIceCandidates.isEmpty()) return emptyList()
        val drained = ArrayList<IceCandidate>()
        while (true) {
            val candidate = pendingIceCandidates.poll() ?: break
            drained.add(candidate)
        }
        return drained
    }

    private fun isCurrentStreamLocked(streamId: Int): Boolean {
        return streamId == streamGeneration.get() && peerConnection != null
    }

    private fun isCurrentStream(streamId: Int): Boolean {
        return synchronized(streamLock) { isCurrentStreamLocked(streamId) }
    }

    private fun postStopStreamIfCurrent(streamId: Int) {
        cancelIdleStop()
        stopHandler.post {
            val resources =
                synchronized(streamLock) {
                    if (!isCurrentStreamLocked(streamId)) {
                        null
                    } else {
                        streamGeneration.incrementAndGet()
                        streamRequestId = null
                        detachStreamResourcesLocked()
                    }
                }
                    ?: return@post
            cleanupStreamResources(resources)
        }
    }

    private fun handlePeerDisconnected(streamId: Int, reason: String) {
        val peerResources = synchronized(streamLock) {
            if (!isCurrentStreamLocked(streamId)) {
                null
            } else {
                streamGeneration.incrementAndGet()
                detachPeerResourcesLocked()
            }
        }
        if (peerResources != null) {
            cleanupPeerResources(peerResources)
            scheduleIdleStop(reason)
        }
    }

    private fun schedulePeerDisconnectWithGrace(streamId: Int, reason: String) {
        val pending =
            synchronized(streamLock) {
                if (!isCurrentStreamLocked(streamId)) return
                pendingIceDisconnect?.let { stopHandler.removeCallbacks(it.runnable) }
                val runnable =
                    Runnable {
                        val shouldDisconnect =
                            synchronized(streamLock) {
                                val currentPending = pendingIceDisconnect
                                if (currentPending == null || currentPending.streamId != streamId) {
                                    false
                                } else {
                                    pendingIceDisconnect = null
                                    isCurrentStreamLocked(streamId)
                                }
                            }
                        if (!shouldDisconnect) return@Runnable
                        Log.w(
                            TAG,
                            "ICE disconnect persisted for ${ICE_DISCONNECTED_GRACE_MS}ms - keeping capture, resetting peer",
                        )
                        handlePeerDisconnected(streamId, reason)
                    }
                PendingIceDisconnect(streamId, reason, runnable).also { pendingIceDisconnect = it }
            }
        stopHandler.postDelayed(pending.runnable, ICE_DISCONNECTED_GRACE_MS)
        Log.w(
            TAG,
            "ICE connection DISCONNECTED - waiting ${ICE_DISCONNECTED_GRACE_MS}ms before peer reset",
        )
    }

    private fun cancelPendingPeerDisconnect(streamId: Int? = null) {
        val pending =
            synchronized(streamLock) {
                val currentPending = pendingIceDisconnect
                if (currentPending == null) {
                    null
                } else if (streamId != null && currentPending.streamId != streamId) {
                    null
                } else {
                    pendingIceDisconnect = null
                    currentPending
                }
            }
                ?: return
        stopHandler.removeCallbacks(pending.runnable)
    }

    private fun createPeerConnection(
        customIceServers: List<PeerConnection.IceServer>?,
        streamId: Int
    ) {
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

        peerConnection =
            peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : CustomPeerConnectionObserver() {
                    override fun onIceCandidate(p0: IceCandidate) {
                        var toSend: IceCandidate? = null
                        synchronized(streamLock) {
                            if (!isCurrentStreamLocked(streamId)) return
                            if (canSendOutgoingIceCandidates) {
                                toSend = p0
                            } else {
                                queueOutgoingIceCandidateLocked(p0)
                            }
                        }
                        toSend?.let { sendIceCandidate(it) }
                    }

                    override fun onIceConnectionChange(
                        p0: PeerConnection.IceConnectionState?
                    ) {
                        if (!isCurrentStream(streamId)) return
                        when (p0) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                cancelPendingPeerDisconnect(streamId)
                                cancelIdleStop()
                                showCloudStreamConnectedToastOnce(streamId)
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                schedulePeerDisconnectWithGrace(
                                    streamId,
                                    p0.name.lowercase(Locale.US)
                                )
                            }

                            PeerConnection.IceConnectionState.CLOSED -> {
                                cancelPendingPeerDisconnect(streamId)
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
                                cancelPendingPeerDisconnect(streamId)
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
                                        sendStreamReady()
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

        val dcInit =
            DataChannel.Init().apply {
                ordered = true
                negotiated = true
                id = 1
            }
        controlChannel = peerConnection?.createDataChannel("control", dcInit)
        controlChannel?.let { dc ->
            streamingControlHandler = ScrcpyControlChannel()
            dc.registerObserver(streamingControlHandler)
        }
    }

    private fun createVideoTrack(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
        streamId: Int,
    ) {
        screenCapturer =
            ScreenCapturerAndroid(
                permissionResultData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        postStopStreamIfCurrent(streamId)
                        ScreenCaptureService.requestStop("projection_stopped")
                    }
                }
            )

        val source = peerConnectionFactory?.createVideoSource(screenCapturer!!.isScreencast)
        videoSource = source
        val sourceObserver =
            source?.capturerObserver
                ?: throw IllegalStateException("Unable to create video source observer")
        captureObserver = StaleFrameDroppingCapturerObserver(sourceObserver)

        // Start Capture
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper?.handler?.post {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                Log.i(TAG, "Capture thread priority set to DISPLAY")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set capture thread priority", t)
            }
        }
        screenCapturer?.initialize(surfaceTextureHelper, context, captureObserver)
        try {
            screenCapturer?.startCapture(width, height, fps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopStream()
            throw RuntimeException("Failed to start screen capture: ${e.message}", e)
        }

        synchronized(streamLock) {
            captureWidth = width
            captureHeight = height
            captureFps = fps
            activeCaptureTargetFps = fps
        }

        videoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
    }

    private inner class StaleFrameDroppingCapturerObserver(
        private val delegate: CapturerObserver
    ) : CapturerObserver {
        @Volatile
        private var disposed = false

        fun dispose() {
            if (disposed) return
            disposed = true
        }

        override fun onCapturerStarted(success: Boolean) {
            delegate.onCapturerStarted(success)
        }

        override fun onCapturerStopped() {
            dispose()
            delegate.onCapturerStopped()
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            if (disposed) return
            val frameTimestampNs = frame.timestampNs
            val nowNs = System.nanoTime()
            val targetFps = activeCaptureTargetFps.coerceAtLeast(1)
            val expectedIntervalNs = 1_000_000_000L / targetFps
            val maxAgeNs = max(STALE_CAPTURE_FRAME_AGE_MS * 1_000_000L, expectedIntervalNs * 4)
            val frameAgeNs = nowNs - frameTimestampNs

            if (frameAgeNs > maxAgeNs) {
                logCaptureFrameDrop("stale", frameAgeNs)
                return
            }

            val previousTimestamp = lastForwardedCaptureTimestampNs.get()
            if (previousTimestamp != Long.MIN_VALUE && frameTimestampNs <= previousTimestamp) {
                logCaptureFrameDrop("out_of_order", null)
                return
            }

            lastForwardedCaptureTimestampNs.set(frameTimestampNs)
            delegate.onFrameCaptured(frame)
            capturedFramesForwarded.incrementAndGet()
        }
    }

    private fun logCaptureFrameDrop(reason: String, ageNs: Long?) {
        val dropped = localDroppedCaptureFrames.incrementAndGet()
        if (dropped == 1L || dropped % STALE_CAPTURE_DROP_LOG_EVERY == 0L) {
            val ageLabel =
                ageNs?.let { " ageMs=${String.format(Locale.US, "%.1f", it / 1_000_000.0)}" } ?: ""
            Log.w(
                TAG,
                "Dropping capture frame ($reason$ageLabel targetFps=$activeCaptureTargetFps dropped=$dropped)",
            )
        }
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

                    val bitratePlan = synchronized(streamLock) { resolveBitratePlanLocked() }
                    val preferredSdp = preferH264(desc.description)
                    val mungedSdp = applyVideoBitrateHints(preferredSdp, bitratePlan)
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
                    sendOffer(offer.description)
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
        val plan = resolveBitratePlan(width, height, fps)
        val effectiveFps = resolveEffectiveSenderMaxFps(fps)
        applySenderParameters(sender, plan, effectiveFps, reason = "initial")
    }

    private fun applySenderParameters(
        sender: RtpSender,
        plan: BitratePlan,
        fps: Int,
        reason: String,
        degradationPreference: RtpParameters.DegradationPreference? = null,
    ): Boolean {
        val parameters = sender.parameters
        val targetDegradationPreference =
            degradationPreference
                ?: synchronized(streamLock) { resolveTargetDegradationPreferenceLocked() }
        var updated = false
        for (encoding in parameters.encodings) {
            val currentMax = encoding.maxBitrateBps
            if (currentMax == null || currentMax != plan.maxBps) {
                encoding.maxBitrateBps = plan.maxBps
                updated = true
            }
            val currentFps = encoding.maxFramerate
            if (currentFps == null || currentFps != fps) {
                encoding.maxFramerate = fps
                updated = true
            }
        }
        if (parameters.degradationPreference != targetDegradationPreference) {
            parameters.degradationPreference = targetDegradationPreference
            updated = true
        }
        if (!updated) return true

        val success = sender.setParameters(parameters)
        Log.i(
            TAG,
            "Applied sender params ($reason, min=${plan.minBps}, start=${plan.startBps}, max=${plan.maxBps}, maxFps=$fps, degradation=${targetDegradationPreference.name}, success=$success)"
        )
        return success
    }

    private fun resolveTargetDegradationPreferenceLocked(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): RtpParameters.DegradationPreference {
        val inStartupQualityWindow =
            ENABLE_STARTUP_PREFER_RESOLUTION &&
                    streamStartElapsedRealtimeMs > 0L &&
                    (nowElapsedMs - streamStartElapsedRealtimeMs) < STARTUP_PREFER_RESOLUTION_MS
        return if (inStartupQualityWindow) {
            RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        } else {
            RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        }
    }

    private fun maybeApplyStartupDegradationPreference() {
        synchronized(streamLock) {
            val connection = peerConnection ?: return
            val videoSender =
                connection.senders.firstOrNull { it.track()?.kind() == "video" } ?: return
            val targetFps = captureFps.takeIf { it > 0 } ?: activeCaptureTargetFps.coerceAtLeast(1)
            val desiredPreference = resolveTargetDegradationPreferenceLocked()
            val currentPreference = videoSender.parameters.degradationPreference
            if (currentPreference == desiredPreference) return

            val plan = resolveBitratePlanLocked()
            val success =
                applySenderParameters(
                    sender = videoSender,
                    plan = plan,
                    fps = resolveEffectiveSenderMaxFps(targetFps),
                    reason = "startup_quality_pref",
                    degradationPreference = desiredPreference,
                )
            Log.i(
                TAG,
                "Startup quality preference switched to ${desiredPreference.name} success=$success",
            )
        }
    }

    private fun maybeApplyLowFpsFallback(fps: Double?, qualityReason: String?) {
        if (fps == null) return
        val nowMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val normalizedQualityReason = qualityReason?.lowercase(Locale.US)
        val qualityLimited = normalizedQualityReason != null && normalizedQualityReason != "none"

        if (!qualityLimited) {
            maybeRecoverAdaptiveMaxBitrate(fps)
        }

        if (!qualityLimited && !ENABLE_CAPTURE_LADDER) {
            synchronized(streamLock) {
                lowFpsStreak = 0
                highFpsRecoveryStreak = 0
                captureDownshiftTriggerCount = 0
            }
            return
        }

        var pendingLadderChange: PendingCaptureLadderChange? = null

        synchronized(streamLock) {
            val targetFps = captureFps.takeIf { it > 0 } ?: return@synchronized
            val lowThreshold = targetFps * LOW_FPS_RATIO_TRIGGER
            val recoveryThreshold = targetFps * LOW_FPS_RATIO_RECOVERY

            when {
                fps < lowThreshold -> {
                    lowFpsStreak += 1
                    highFpsRecoveryStreak = 0
                    if (shouldEmitDecisionLog(lastLowFpsDecisionLogElapsedMs, nowElapsedMs)) {
                        lastLowFpsDecisionLogElapsedMs = nowElapsedMs
                        Log.i(
                            TAG,
                            "Low-FPS monitor: fps=${
                                formatMetric(
                                    fps,
                                    1
                                )
                            } target=$targetFps threshold=${
                                formatMetric(
                                    lowThreshold,
                                    1
                                )
                            } streak=$lowFpsStreak/$LOW_FPS_STREAK_THRESHOLD qlim=${qualityReason ?: "none"}",
                        )
                    }
                }

                fps >= recoveryThreshold -> {
                    lowFpsStreak = 0
                    captureDownshiftTriggerCount = 0
                    val canRecoverCapture =
                        normalizedQualityReason == null || normalizedQualityReason == "none"
                    val passedRecoveryHold =
                        nowMs - lastCaptureDownshiftMs >= CAPTURE_RECOVERY_HOLD_MS
                    val passedChangeCooldown =
                        nowMs - lastCaptureLadderChangeMs >= CAPTURE_LADDER_CHANGE_COOLDOWN_MS
                    if (!canRecoverCapture ||
                        captureLadderIndex <= 0 ||
                        !passedRecoveryHold ||
                        !passedChangeCooldown
                    ) {
                        if (shouldEmitDecisionLog(lastLowFpsDecisionLogElapsedMs, nowElapsedMs)) {
                            lastLowFpsDecisionLogElapsedMs = nowElapsedMs
                            Log.i(
                                TAG,
                                "Low-FPS recovery hold: fps=${
                                    formatMetric(
                                        fps,
                                        1
                                    )
                                } target=$targetFps threshold=${
                                    formatMetric(
                                        recoveryThreshold,
                                        1
                                    )
                                } canRecover=$canRecoverCapture ladder=${captureLadderIndex}/${captureLadderProfiles.lastIndex} holdPassed=$passedRecoveryHold cooldownPassed=$passedChangeCooldown qlim=${qualityReason ?: "none"}",
                            )
                        }
                        highFpsRecoveryStreak = 0
                        return@synchronized
                    }
                    highFpsRecoveryStreak += 1
                    if (highFpsRecoveryStreak < CAPTURE_RECOVERY_STREAK_THRESHOLD) {
                        if (shouldEmitDecisionLog(lastLowFpsDecisionLogElapsedMs, nowElapsedMs)) {
                            lastLowFpsDecisionLogElapsedMs = nowElapsedMs
                            Log.i(
                                TAG,
                                "Low-FPS recovery streak: fps=${
                                    formatMetric(
                                        fps,
                                        1
                                    )
                                } target=$targetFps streak=$highFpsRecoveryStreak/$CAPTURE_RECOVERY_STREAK_THRESHOLD",
                            )
                        }
                        return@synchronized
                    }
                    highFpsRecoveryStreak = 0
                    pendingLadderChange =
                        PendingCaptureLadderChange(
                            fromIndex = captureLadderIndex,
                            toIndex = captureLadderIndex - 1,
                            triggerFps = fps,
                            reason = "recovery",
                        )
                    return@synchronized
                }

                else -> {
                    highFpsRecoveryStreak = 0
                    captureDownshiftTriggerCount = 0
                    return@synchronized
                }
            }

            if (lowFpsStreak < LOW_FPS_STREAK_THRESHOLD) {
                if (shouldEmitDecisionLog(lastLowFpsDecisionLogElapsedMs, nowElapsedMs)) {
                    lastLowFpsDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Low-FPS waiting streak: fps=${
                            formatMetric(
                                fps,
                                1
                            )
                        } target=$targetFps streak=$lowFpsStreak/$LOW_FPS_STREAK_THRESHOLD",
                    )
                }
                return@synchronized
            }
            lowFpsStreak = 0
            captureDownshiftTriggerCount += 1

            val severeDrop = fps < (targetFps * CAPTURE_SEVERE_LOW_FPS_RATIO)
            val passedChangeCooldown =
                nowMs - lastCaptureLadderChangeMs >= CAPTURE_LADDER_CHANGE_COOLDOWN_MS
            val inStartupGrace =
                streamStartElapsedRealtimeMs > 0L &&
                        (SystemClock.elapsedRealtime() - streamStartElapsedRealtimeMs) <
                        LOW_FPS_FALLBACK_STARTUP_GRACE_MS
            val shouldScheduleDownshift =
                ENABLE_CAPTURE_LADDER &&
                        !qualityLimited &&
                        captureLadderIndex < captureLadderProfiles.lastIndex &&
                        passedChangeCooldown &&
                        (severeDrop ||
                                captureDownshiftTriggerCount >= CAPTURE_LADDER_DOWNSHIFT_TRIGGER_COUNT)

            if (shouldEmitDecisionLog(lastLowFpsDecisionLogElapsedMs, nowElapsedMs)) {
                lastLowFpsDecisionLogElapsedMs = nowElapsedMs
                Log.i(
                    TAG,
                    "Low-FPS decision: fps=${
                        formatMetric(
                            fps,
                            1
                        )
                    } target=$targetFps severe=$severeDrop qlim=${qualityReason ?: "none"} startupGrace=$inStartupGrace cooldownPassed=$passedChangeCooldown downshiftTrigger=$captureDownshiftTriggerCount/$CAPTURE_LADDER_DOWNSHIFT_TRIGGER_COUNT ladder=${captureLadderIndex}/${captureLadderProfiles.lastIndex} scheduleDownshift=$shouldScheduleDownshift",
                )
            }

            if (!shouldScheduleDownshift && qualityLimited && !inStartupGrace) {
                val currentPlan = resolveBitratePlanLocked()
                val adaptiveFloor = computeAdaptiveMaxFloorBpsLocked()
                val nextAdaptiveMax =
                    (currentPlan.maxBps * (100 - LOW_FPS_STEP_DOWN_PERCENT) / 100)
                        .coerceAtLeast(adaptiveFloor)
                if (nextAdaptiveMax < currentPlan.maxBps) {
                    adaptiveMaxBitrateBps = nextAdaptiveMax
                    adaptiveMaxRecoveryStreak = 0
                    lastAdaptiveMaxRecoveryChangeElapsedMs = SystemClock.elapsedRealtime()
                    lowFpsFallbackSteps += 1
                    val connection = peerConnection
                    val videoSender =
                        connection?.senders?.firstOrNull { it.track()?.kind() == "video" }
                    if (videoSender != null) {
                        val nextPlan = resolveBitratePlanLocked()
                        val success =
                            applySenderParameters(
                                sender = videoSender,
                                plan = nextPlan,
                                fps = resolveEffectiveSenderMaxFps(targetFps),
                                reason = "low_fps_fallback#$lowFpsFallbackSteps",
                            )
                        Log.w(
                            TAG,
                            "Low-FPS fallback: fps=${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    fps
                                )
                            } target=$targetFps nextMax=${nextPlan.maxBps} success=$success",
                        )
                    }
                }
            } else if (!shouldScheduleDownshift && qualityLimited && inStartupGrace) {
                if (shouldEmitDecisionLog(lastLowFpsDecisionLogElapsedMs, nowElapsedMs)) {
                    lastLowFpsDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Low-FPS fallback skipped in startup grace: fps=${
                            formatMetric(
                                fps,
                                1
                            )
                        } target=$targetFps startupGraceMs=$LOW_FPS_FALLBACK_STARTUP_GRACE_MS qlim=${qualityReason ?: "none"}",
                    )
                }
            }

            if (shouldScheduleDownshift) {
                captureDownshiftTriggerCount = 0
                pendingLadderChange =
                    PendingCaptureLadderChange(
                        fromIndex = captureLadderIndex,
                        toIndex = captureLadderIndex + 1,
                        triggerFps = fps,
                        reason = "low_fps",
                    )
            }
        }

        pendingLadderChange?.let { applyCaptureLadderChange(it) }
    }

    private fun maybeRecoverAdaptiveMaxBitrate(fps: Double) {
        synchronized(streamLock) {
            val targetFps = captureFps.takeIf { it > 0 } ?: return
            val currentAdaptiveMax = adaptiveMaxBitrateBps ?: run {
                adaptiveMaxRecoveryStreak = 0
                return
            }
            val recoveryThreshold = targetFps * ADAPTIVE_MAX_RECOVERY_MIN_FPS_RATIO
            if (fps < recoveryThreshold) {
                adaptiveMaxRecoveryStreak = 0
                return
            }

            adaptiveMaxRecoveryStreak += 1
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val cooldownPassed =
                nowElapsedMs - lastAdaptiveMaxRecoveryChangeElapsedMs >=
                        ADAPTIVE_MAX_RECOVERY_COOLDOWN_MS
            if (adaptiveMaxRecoveryStreak < ADAPTIVE_MAX_RECOVERY_STREAK_THRESHOLD ||
                !cooldownPassed
            ) {
                return
            }
            adaptiveMaxRecoveryStreak = 0

            val baseMaxBps = computeBaseMaxBitrateBpsLocked()
            if (currentAdaptiveMax >= baseMaxBps) {
                adaptiveMaxBitrateBps = null
                return
            }

            val nextAdaptiveMax =
                ((currentAdaptiveMax * (100 + ADAPTIVE_MAX_RECOVERY_STEP_PERCENT)) / 100.0)
                    .roundToInt()
                    .coerceAtMost(baseMaxBps)
            if (nextAdaptiveMax <= currentAdaptiveMax) return

            adaptiveMaxBitrateBps =
                if (nextAdaptiveMax >= baseMaxBps) {
                    null
                } else {
                    nextAdaptiveMax
                }
            lastAdaptiveMaxRecoveryChangeElapsedMs = nowElapsedMs

            val connection = peerConnection
            val videoSender = connection?.senders?.firstOrNull { it.track()?.kind() == "video" }
            if (videoSender != null) {
                val nextPlan = resolveBitratePlanLocked()
                val success =
                    applySenderParameters(
                        sender = videoSender,
                        plan = nextPlan,
                        fps = resolveEffectiveSenderMaxFps(targetFps),
                        reason = "adaptive_recovery",
                    )
                val effectiveAdaptiveKbps = adaptiveMaxBitrateBps?.div(1000)
                Log.i(
                    TAG,
                    "Adaptive max recovery: fps=${
                        String.format(
                            Locale.US,
                            "%.1f",
                            fps
                        )
                    } target=$targetFps nextMax=${nextPlan.maxBps} effectiveAdaptMax=${effectiveAdaptiveKbps ?: "none"}kbps success=$success",
                )
            }
        }
    }

    private fun maybeApplyStartupAndRttAdaptation(
        rttMs: Double?,
        availableOutKbps: Double?,
        qualityReason: String?,
    ) {
        synchronized(streamLock) {
            val targetFps = captureFps.takeIf { it > 0 } ?: return
            val baseMaxBps = computeBaseMaxBitrateBpsLocked()
            val floorBps = computeAdaptiveMaxFloorBpsLocked()
            val nowElapsedMs = SystemClock.elapsedRealtime()

            if (streamStartElapsedRealtimeMs <= 0L) {
                streamStartElapsedRealtimeMs = nowElapsedMs
                lastRttAdaptChangeElapsedMs = nowElapsedMs
            }

            val adaptationReasons = mutableListOf<String>()
            var changed = false

            val streamElapsedMs = nowElapsedMs - streamStartElapsedRealtimeMs
            val startupCapBps =
                if (ENABLE_STARTUP_RAMP && streamElapsedMs < STARTUP_RAMP_DURATION_MS) {
                    val progress =
                        (streamElapsedMs.toDouble() / STARTUP_RAMP_DURATION_MS.toDouble())
                            .coerceIn(0.0, 1.0)
                    val ratio =
                        STARTUP_RAMP_INITIAL_MAX_RATIO +
                                (1.0 - STARTUP_RAMP_INITIAL_MAX_RATIO) * progress
                    (baseMaxBps * ratio)
                        .roundToInt()
                        .coerceIn(floorBps, baseMaxBps)
                } else {
                    null
                }

            if (startupAdaptiveMaxBitrateBps != startupCapBps) {
                startupAdaptiveMaxBitrateBps = startupCapBps
                changed = true
                adaptationReasons +=
                    if (startupCapBps != null) {
                        "startup_ramp"
                    } else {
                        "startup_complete"
                    }
            }

            val qualityLimited =
                !qualityReason.isNullOrBlank() && !qualityReason.equals("none", ignoreCase = true)
            val cooldownPassed =
                nowElapsedMs - lastRttAdaptChangeElapsedMs >= RTT_ADAPT_COOLDOWN_MS

            if (rttMs != null) {
                when {
                    rttMs >= RTT_SPIKE_THRESHOLD_MS -> {
                        rttSpikeStreak += 1
                        rttRecoveryStreak = 0
                    }

                    rttMs <= RTT_RECOVERY_THRESHOLD_MS -> {
                        rttRecoveryStreak += 1
                        rttSpikeStreak = 0
                    }

                    else -> {
                        rttSpikeStreak = 0
                        rttRecoveryStreak = 0
                    }
                }
            }

            if (!qualityLimited &&
                rttSpikeStreak >= RTT_SPIKE_STREAK_THRESHOLD &&
                cooldownPassed
            ) {
                rttSpikeStreak = 0
                rttRecoveryStreak = 0
                val previousSteps = rttBackoffSteps
                rttBackoffSteps = (rttBackoffSteps + 1).coerceAtMost(RTT_BACKOFF_MAX_STEPS)
                if (rttBackoffSteps != previousSteps) {
                    rttAdaptiveMaxBitrateBps =
                        computeRttAdaptiveMaxBpsLocked(baseMaxBps, floorBps, rttBackoffSteps)
                    lastRttAdaptChangeElapsedMs = nowElapsedMs
                    changed = true
                    adaptationReasons += "rtt_backoff#$rttBackoffSteps"
                }
            } else if (rttBackoffSteps > 0 &&
                rttRecoveryStreak >= RTT_RECOVERY_STREAK_THRESHOLD &&
                cooldownPassed
            ) {
                rttRecoveryStreak = 0
                rttSpikeStreak = 0
                rttBackoffSteps = (rttBackoffSteps - 1).coerceAtLeast(0)
                rttAdaptiveMaxBitrateBps =
                    if (rttBackoffSteps == 0) {
                        null
                    } else {
                        computeRttAdaptiveMaxBpsLocked(baseMaxBps, floorBps, rttBackoffSteps)
                    }
                lastRttAdaptChangeElapsedMs = nowElapsedMs
                changed = true
                adaptationReasons +=
                    if (rttBackoffSteps == 0) {
                        "rtt_recovery"
                    } else {
                        "rtt_recovery#$rttBackoffSteps"
                    }
            }

            if (!changed) {
                if (shouldEmitDecisionLog(lastRttAdaptDecisionLogElapsedMs, nowElapsedMs) &&
                    (rttMs != null || qualityLimited)
                ) {
                    lastRttAdaptDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Network adaptation hold: rtt=${
                            formatMetric(
                                rttMs,
                                0
                            )
                        }ms qlim=${qualityReason ?: "none"} cooldownPassed=$cooldownPassed rttSpikeStreak=$rttSpikeStreak/$RTT_SPIKE_STREAK_THRESHOLD rttRecoveryStreak=$rttRecoveryStreak/$RTT_RECOVERY_STREAK_THRESHOLD backoffSteps=$rttBackoffSteps/$RTT_BACKOFF_MAX_STEPS",
                    )
                }
                return
            }

            val connection = peerConnection
            val videoSender = connection?.senders?.firstOrNull { it.track()?.kind() == "video" }
            if (videoSender != null) {
                val plan = resolveBitratePlanLocked()
                val success =
                    applySenderParameters(
                        sender = videoSender,
                        plan = plan,
                        fps = resolveEffectiveSenderMaxFps(targetFps),
                        reason = "network_adapt:${adaptationReasons.joinToString(",")}",
                    )
                val effectiveAdaptiveKbps = currentAdaptiveMaxBitrateBpsLocked()?.div(1000)
                Log.i(
                    TAG,
                    "Network adaptation applied reasons=${adaptationReasons.joinToString(",")} max=${plan.maxBps} effectiveAdaptMax=${effectiveAdaptiveKbps ?: "none"}kbps rtt=${
                        rttMs?.let {
                            String.format(
                                Locale.US,
                                "%.0f",
                                it
                            )
                        } ?: "n/a"
                    }ms avail=${
                        availableOutKbps?.let {
                            String.format(
                                Locale.US,
                                "%.0f",
                                it
                            )
                        } ?: "n/a"
                    }kbps success=$success",
                )
                lastRttAdaptDecisionLogElapsedMs = nowElapsedMs
            }
        }
    }

    private fun maybeApplyLocalFpsGovernor(
        fps: Double?,
        rttMs: Double?,
        qualityReason: String?,
    ) {
        synchronized(streamLock) {
            val targetFps = captureFps.takeIf { it > 0 } ?: return
            if (targetFps <= LOCAL_FPS_GOVERNOR_CAP_FPS) return

            val nowElapsedMs = SystemClock.elapsedRealtime()
            val governorHasLease = localFpsGovernorCapUntilElapsedMs > 0L
            val governorCurrentlyActive = isLocalFpsGovernorActive(nowElapsedMs)
            val qlimNone =
                qualityReason.isNullOrBlank() || qualityReason.equals("none", ignoreCase = true)
            val rttHealthy = rttMs == null || rttMs <= LOCAL_FPS_GOVERNOR_RTT_HEALTHY_MAX_MS
            val fpsLow = fps != null && fps < LOCAL_FPS_GOVERNOR_TRIGGER_FPS

            if (qlimNone && rttHealthy && fpsLow) {
                localFpsGovernorLowStreak += 1
            } else {
                localFpsGovernorLowStreak = 0
            }

            var reason: String? = null
            if (governorHasLease && !governorCurrentlyActive) {
                localFpsGovernorCapUntilElapsedMs = 0L
                localFpsGovernorLowStreak = 0
                reason = "local_fps_governor_off"
            } else if (!governorCurrentlyActive &&
                localFpsGovernorLowStreak >= LOCAL_FPS_GOVERNOR_TRIGGER_STREAK
            ) {
                localFpsGovernorLowStreak = 0
                localFpsGovernorCapUntilElapsedMs = nowElapsedMs + LOCAL_FPS_GOVERNOR_HOLD_MS
                reason = "local_fps_governor_on"
            }

            if (reason == null) {
                if (shouldEmitDecisionLog(lastLocalFpsGovernorDecisionLogElapsedMs, nowElapsedMs) &&
                    (fpsLow || governorCurrentlyActive)
                ) {
                    lastLocalFpsGovernorDecisionLogElapsedMs = nowElapsedMs
                    val holdRemainingMs =
                        (localFpsGovernorCapUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
                    Log.i(
                        TAG,
                        "Local FPS governor hold: active=$governorCurrentlyActive holdMs=$holdRemainingMs fps=${
                            formatMetric(
                                fps,
                                1
                            )
                        } trigger=${LOCAL_FPS_GOVERNOR_TRIGGER_FPS} streak=$localFpsGovernorLowStreak/$LOCAL_FPS_GOVERNOR_TRIGGER_STREAK rtt=${
                            formatMetric(
                                rttMs,
                                0
                            )
                        }ms qlim=${qualityReason ?: "none"}",
                    )
                }
                return
            }

            val connection = peerConnection
            val videoSender = connection?.senders?.firstOrNull { it.track()?.kind() == "video" }
            if (videoSender != null) {
                val plan = resolveBitratePlanLocked()
                val effectiveFps = resolveEffectiveSenderMaxFps(targetFps, nowElapsedMs)
                val success =
                    applySenderParameters(
                        sender = videoSender,
                        plan = plan,
                        fps = effectiveFps,
                        reason = reason,
                    )
                val holdRemainingMs =
                    (localFpsGovernorCapUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
                Log.i(
                    TAG,
                    "Local FPS governor: reason=$reason effectiveFps=$effectiveFps holdMs=$holdRemainingMs fps=${
                        fps?.let {
                            String.format(
                                Locale.US,
                                "%.1f",
                                it
                            )
                        } ?: "n/a"
                    } rtt=${
                        rttMs?.let {
                            String.format(
                                Locale.US,
                                "%.0f",
                                it
                            )
                        } ?: "n/a"
                    }ms qlim=${qualityReason ?: "none"} success=$success",
                )
                lastLocalFpsGovernorDecisionLogElapsedMs = nowElapsedMs
            }
        }
    }

    private fun maybeApplySourceFpsDownshift(
        sourceFps: Double?,
        qualityReason: String?,
    ) {
        if (!ENABLE_CAPTURE_LADDER) return
        if (sourceFps == null) return

        val normalizedQualityReason = qualityReason?.lowercase(Locale.US)
        val qualityLimited = normalizedQualityReason != null && normalizedQualityReason != "none"
        val nowMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        var pendingLadderChange: PendingCaptureLadderChange? = null

        synchronized(streamLock) {
            if (qualityLimited) {
                if (shouldEmitDecisionLog(lastSourceFpsDecisionLogElapsedMs, nowElapsedMs)) {
                    lastSourceFpsDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Source-FPS downshift skipped: qualityLimited=${qualityReason ?: "none"} sourceFps=${
                            formatMetric(
                                sourceFps,
                                1
                            )
                        }",
                    )
                }
                sourceFpsLowStreak = 0
                return@synchronized
            }
            if (sourceFps < SOURCE_FPS_DOWNSHIFT_TRIGGER_FPS) {
                sourceFpsLowStreak += 1
                if (shouldEmitDecisionLog(lastSourceFpsDecisionLogElapsedMs, nowElapsedMs)) {
                    lastSourceFpsDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Source-FPS monitor: sourceFps=${
                            formatMetric(
                                sourceFps,
                                1
                            )
                        } trigger=$SOURCE_FPS_DOWNSHIFT_TRIGGER_FPS streak=$sourceFpsLowStreak/$SOURCE_FPS_DOWNSHIFT_TRIGGER_STREAK",
                    )
                }
            } else {
                sourceFpsLowStreak = 0
                return@synchronized
            }

            if (sourceFpsLowStreak < SOURCE_FPS_DOWNSHIFT_TRIGGER_STREAK) return@synchronized
            sourceFpsLowStreak = 0

            val passedChangeCooldown =
                nowMs - lastCaptureLadderChangeMs >= CAPTURE_LADDER_CHANGE_COOLDOWN_MS
            if (!passedChangeCooldown) {
                if (shouldEmitDecisionLog(lastSourceFpsDecisionLogElapsedMs, nowElapsedMs)) {
                    lastSourceFpsDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Source-FPS downshift blocked by cooldown: sourceFps=${
                            formatMetric(
                                sourceFps,
                                1
                            )
                        } ladder=${captureLadderIndex}/${captureLadderProfiles.lastIndex} cooldownMs=$CAPTURE_LADDER_CHANGE_COOLDOWN_MS",
                    )
                }
                return@synchronized
            }
            if (captureLadderIndex >= captureLadderProfiles.lastIndex) {
                if (shouldEmitDecisionLog(lastSourceFpsDecisionLogElapsedMs, nowElapsedMs)) {
                    lastSourceFpsDecisionLogElapsedMs = nowElapsedMs
                    Log.i(
                        TAG,
                        "Source-FPS downshift blocked at lowest ladder: sourceFps=${
                            formatMetric(
                                sourceFps,
                                1
                            )
                        } ladder=${captureLadderIndex}/${captureLadderProfiles.lastIndex}",
                    )
                }
                return@synchronized
            }

            pendingLadderChange =
                PendingCaptureLadderChange(
                    fromIndex = captureLadderIndex,
                    toIndex = captureLadderIndex + 1,
                    triggerFps = sourceFps,
                    reason = "source_fps",
                )
            if (shouldEmitDecisionLog(lastSourceFpsDecisionLogElapsedMs, nowElapsedMs)) {
                lastSourceFpsDecisionLogElapsedMs = nowElapsedMs
                Log.i(
                    TAG,
                    "Source-FPS downshift scheduled: sourceFps=${
                        formatMetric(
                            sourceFps,
                            1
                        )
                    } ladder=${captureLadderIndex}/${captureLadderProfiles.lastIndex} -> ${captureLadderIndex + 1}",
                )
            }
        }

        pendingLadderChange?.let { applyCaptureLadderChange(it) }
    }

    private fun maybeRecoverFromEncoderStall(
        timestampMs: Long,
        framesSent: Long?,
        fps: Double?,
        bitrateKbps: Int?,
        availableOutKbps: Double?,
        qualityReason: String?,
    ): Boolean {
        val recovery =
            synchronized(streamLock) {
                val nowMs = SystemClock.elapsedRealtime()
                if (stallRecoveryInProgress) {
                    if (shouldEmitDecisionLog(lastStallWatchdogDecisionLogElapsedMs, nowMs)) {
                        lastStallWatchdogDecisionLogElapsedMs = nowMs
                        Log.i(TAG, "Stall watchdog hold: recovery already in progress")
                    }
                    return false
                }
                if (stallRecoveryAttempts >= STALL_WATCHDOG_MAX_RECOVERIES) {
                    if (shouldEmitDecisionLog(lastStallWatchdogDecisionLogElapsedMs, nowMs)) {
                        lastStallWatchdogDecisionLogElapsedMs = nowMs
                        Log.i(
                            TAG,
                            "Stall watchdog budget exhausted: attempts=$stallRecoveryAttempts/$STALL_WATCHDOG_MAX_RECOVERIES",
                        )
                    }
                    return false
                }
                if (nowMs - lastStallRecoveryAttemptMs < STALL_WATCHDOG_COOLDOWN_MS) {
                    if (shouldEmitDecisionLog(lastStallWatchdogDecisionLogElapsedMs, nowMs)) {
                        lastStallWatchdogDecisionLogElapsedMs = nowMs
                        val waitMs =
                            (STALL_WATCHDOG_COOLDOWN_MS -
                                    (nowMs - lastStallRecoveryAttemptMs)).coerceAtLeast(0L)
                        Log.i(
                            TAG,
                            "Stall watchdog cooldown: waitMs=$waitMs/$STALL_WATCHDOG_COOLDOWN_MS attempts=$stallRecoveryAttempts/$STALL_WATCHDOG_MAX_RECOVERIES",
                        )
                    }
                    return false
                }

                val currentFramesSent = framesSent ?: return false
                val snapshotTimestampMs = stallSnapshotTimestampMs
                val snapshotFramesSent = stallSnapshotFramesSent
                if (snapshotTimestampMs == null || snapshotFramesSent == null) {
                    stallSnapshotTimestampMs = timestampMs
                    stallSnapshotFramesSent = currentFramesSent
                    stallSnapshotCapturedFrames = capturedFramesForwarded.get()
                    return false
                }

                val windowMs = timestampMs - snapshotTimestampMs
                if (windowMs < STALL_WATCHDOG_WINDOW_MS) return false

                val capturedFramesNow = capturedFramesForwarded.get()
                val captureDelta =
                    (capturedFramesNow - stallSnapshotCapturedFrames).coerceAtLeast(0L)
                val sentDelta = (currentFramesSent - snapshotFramesSent).coerceAtLeast(0L)

                stallSnapshotTimestampMs = timestampMs
                stallSnapshotFramesSent = currentFramesSent
                stallSnapshotCapturedFrames = capturedFramesNow

                val fpsLow = fps == null || fps <= STALL_WATCHDOG_MAX_FPS
                val sendLow = bitrateKbps == null || bitrateKbps <= STALL_WATCHDOG_MAX_SEND_KBPS
                val sentStalled = sentDelta <= 1L
                val captureActive = captureDelta >= STALL_WATCHDOG_CAPTURE_DELTA_MIN
                val networkHealthy =
                    availableOutKbps == null || availableOutKbps >= STALL_WATCHDOG_MIN_AVAIL_KBPS
                val qualityLimited =
                    !qualityReason.isNullOrBlank() &&
                            !qualityReason.equals("none", ignoreCase = true)

                if (!fpsLow || !sendLow || !sentStalled || !captureActive || !networkHealthy || qualityLimited) {
                    if (shouldEmitDecisionLog(lastStallWatchdogDecisionLogElapsedMs, nowMs) &&
                        (sentStalled || captureActive || fpsLow || sendLow)
                    ) {
                        lastStallWatchdogDecisionLogElapsedMs = nowMs
                        Log.i(
                            TAG,
                            "Stall watchdog hold: trigger=false fpsLow=$fpsLow sendLow=$sendLow sentStalled=$sentStalled captureActive=$captureActive networkHealthy=$networkHealthy qualityLimited=$qualityLimited windowMs=$windowMs captureDelta=$captureDelta sentDelta=$sentDelta fps=${
                                formatMetric(
                                    fps,
                                    1
                                )
                            } send=${bitrateKbps ?: -1}kbps avail=${
                                formatMetric(
                                    availableOutKbps,
                                    0
                                )
                            }kbps qlim=${qualityReason ?: "none"}",
                        )
                    }
                    return false
                }

                val width = captureWidth.takeIf { it > 0 } ?: return false
                val height = captureHeight.takeIf { it > 0 } ?: return false
                val requestedFps =
                    captureFps.takeIf { it > 0 } ?: activeCaptureTargetFps.coerceAtLeast(1)

                stallRecoveryInProgress = true
                stallRecoveryAttempts += 1
                lastStallRecoveryAttemptMs = nowMs
                val request =
                    StallRecoveryRequest(
                        width = width,
                        height = height,
                        fps = requestedFps,
                        waitForOffer = waitingForOffer,
                    )
                val reason =
                    "windowMs=$windowMs captureDelta=$captureDelta sentDelta=$sentDelta fps=${
                        fps?.let {
                            String.format(
                                Locale.US,
                                "%.1f",
                                it
                            )
                        } ?: "n/a"
                    } send=${bitrateKbps ?: -1}kbps avail=${
                        availableOutKbps?.let {
                            String.format(
                                Locale.US,
                                "%.0f",
                                it
                            )
                        } ?: "n/a"
                    }kbps qlim=${qualityReason ?: "none"} attempt=$stallRecoveryAttempts/$STALL_WATCHDOG_MAX_RECOVERIES"
                lastStallWatchdogDecisionLogElapsedMs = nowMs
                request to reason
            }
        val request = recovery.first
        val reasonLabel = recovery.second
        Log.w(TAG, "Stall watchdog: restarting peer with existing capture ($reasonLabel)")
        mainHandler.post {
            try {
                startStreamWithExistingCapture(
                    width = request.width,
                    height = request.height,
                    fps = request.fps,
                    waitForOffer = request.waitForOffer,
                    resetStallRecoveryBudget = false,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Stall watchdog recovery failed", e)
                synchronized(streamLock) {
                    resetStallWatchdogStateLocked(resetRecoveryBudget = false)
                }
            }
        }
        return true
    }

    private fun applyCaptureLadderChange(change: PendingCaptureLadderChange) {
        val targetProfile =
            synchronized(streamLock) {
                if (change.fromIndex != captureLadderIndex) {
                    null
                } else {
                    captureLadderProfiles.getOrNull(change.toIndex)
                }
            }
                ?: return

        val formatUpdated =
            try {
                updateCaptureFormat(targetProfile.width, targetProfile.height, targetProfile.fps)
                true
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to apply capture ladder profile ${targetProfile.label()} (${change.reason})",
                    e,
                )
                false
            }
        if (!formatUpdated) return
        val nowMs = System.currentTimeMillis()

        synchronized(streamLock) {
            if (change.fromIndex != captureLadderIndex) return@synchronized
            if (captureLadderProfiles.getOrNull(change.toIndex) != targetProfile) {
                return@synchronized
            }
            captureLadderIndex = change.toIndex
            highFpsRecoveryStreak = 0
            captureDownshiftTriggerCount = 0
            lastCaptureLadderChangeMs = nowMs
            if (change.reason == "low_fps" || change.reason == "source_fps") {
                lastCaptureDownshiftMs = nowMs
            }

            if (change.reason == "recovery" && captureLadderIndex == 0) {
                adaptiveMaxBitrateBps = null
                lowFpsFallbackSteps = 0
            }

            val connection = peerConnection
            val videoSender = connection?.senders?.firstOrNull { it.track()?.kind() == "video" }
            if (videoSender != null) {
                val plan = resolveBitratePlanLocked()
                applySenderParameters(
                    sender = videoSender,
                    plan = plan,
                    fps = resolveEffectiveSenderMaxFps(targetProfile.fps),
                    reason = "capture_ladder_${change.reason}#$captureLadderIndex",
                )
            }
        }

        Log.w(
            TAG,
            "Capture ladder ${change.reason}: -> ${targetProfile.label()} triggerFps=${
                String.format(
                    Locale.US,
                    "%.1f",
                    change.triggerFps
                )
            }",
        )
    }

    private fun sanitizeCaptureProfile(width: Int, height: Int, fps: Int): CaptureProfile {
        val normalizedWidth = normalizeEvenDimension(width.coerceAtLeast(240))
        val normalizedHeight = normalizeEvenDimension(height.coerceAtLeast(240))
        val normalizedFps = fps.coerceAtLeast(CAPTURE_MIN_FPS)
        return CaptureProfile(normalizedWidth, normalizedHeight, normalizedFps)
    }

    private fun buildCaptureLadder(baseline: CaptureProfile): List<CaptureProfile> {
        if (!ENABLE_CAPTURE_LADDER) {
            return listOf(sanitizeCaptureProfile(baseline.width, baseline.height, baseline.fps))
        }
        val reducedFps = min(baseline.fps, CAPTURE_RECOVERY_FPS_CAP).coerceAtLeast(CAPTURE_MIN_FPS)
        val longEdge = max(baseline.width, baseline.height)
        val profiles = mutableListOf<CaptureProfile>()
        profiles += baseline
        profiles += baseline.copy(fps = reducedFps)

        if (longEdge > CAPTURE_LADDER_STEP1_LONG_EDGE) {
            val step1 = profileForLongEdgeCap(baseline, CAPTURE_LADDER_STEP1_LONG_EDGE, reducedFps)
            if (step1.width != baseline.width || step1.height != baseline.height) {
                profiles += step1
            }
        }

        return profiles
            .map { sanitizeCaptureProfile(it.width, it.height, it.fps) }
            .distinct()
    }

    private fun profileForLongEdgeCap(
        baseline: CaptureProfile,
        longEdgeCap: Int,
        fps: Int,
    ): CaptureProfile {
        val longEdge = max(baseline.width, baseline.height)
        val shortEdge = min(baseline.width, baseline.height)
        if (longEdge <= longEdgeCap) {
            return CaptureProfile(baseline.width, baseline.height, fps)
        }
        val scale = longEdgeCap.toDouble() / longEdge.toDouble()
        val scaledLong = normalizeEvenDimension((longEdge * scale).roundToInt().coerceAtLeast(240))
        val scaledShort =
            normalizeEvenDimension((shortEdge * scale).roundToInt().coerceAtLeast(240))
        return if (baseline.width >= baseline.height) {
            CaptureProfile(scaledLong, scaledShort, fps)
        } else {
            CaptureProfile(scaledShort, scaledLong, fps)
        }
    }

    private fun normalizeEvenDimension(value: Int): Int {
        val normalized = value.coerceAtLeast(2)
        return if (normalized % 2 == 0) normalized else normalized - 1
    }

    private fun computeAdaptiveMaxFloorBpsLocked(): Int {
        val width = captureWidth.takeIf { it > 0 } ?: 720
        val height = captureHeight.takeIf { it > 0 } ?: 1280
        val fps = captureFps.takeIf { it > 0 } ?: 24
        val baseMax = (requestedMaxBitrateBps ?: computeMaxBitrateBps(width, height, fps))
            .coerceIn(300_000, 20_000_000)
        val baselineFloor = (baseMax * LOW_FPS_MIN_BASELINE_RATIO).toInt()
        val requestedFloor = requestedMinBitrateBps ?: 300_000
        return max(baselineFloor, requestedFloor).coerceIn(300_000, baseMax)
    }

    private fun computeBaseMaxBitrateBpsLocked(): Int {
        val width = captureWidth.takeIf { it > 0 } ?: 720
        val height = captureHeight.takeIf { it > 0 } ?: 1280
        val fps = captureFps.takeIf { it > 0 } ?: 24
        return computeMaxBitrateBps(width, height, fps).coerceIn(300_000, 20_000_000)
    }

    private fun currentAdaptiveMaxBitrateBpsLocked(): Int? {
        var effective: Int? = null
        adaptiveMaxBitrateBps?.let { effective = it }
        startupAdaptiveMaxBitrateBps?.let { startup ->
            effective = if (effective == null) startup else min(effective!!, startup)
        }
        rttAdaptiveMaxBitrateBps?.let { rtt ->
            effective = if (effective == null) rtt else min(effective!!, rtt)
        }
        return effective
    }

    private fun computeRttAdaptiveMaxBpsLocked(
        baseMaxBps: Int,
        floorBps: Int,
        steps: Int,
    ): Int {
        val ratioPercent =
            (100 - (steps.coerceAtLeast(0) * RTT_BACKOFF_STEP_PERCENT)).coerceAtLeast(45)
        return ((baseMaxBps * ratioPercent) / 100.0)
            .roundToInt()
            .coerceIn(floorBps, baseMaxBps)
    }

    private fun isLocalFpsGovernorActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return localFpsGovernorCapUntilElapsedMs > nowElapsedMs
    }

    private fun resolveEffectiveSenderMaxFps(
        requestedFps: Int,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Int {
        val capped =
            if (isLocalFpsGovernorActive(nowElapsedMs)) {
                min(requestedFps, LOCAL_FPS_GOVERNOR_CAP_FPS)
            } else {
                requestedFps
            }
        return capped.coerceAtLeast(1)
    }

    private fun resolveBitratePlan(width: Int, height: Int, fps: Int): BitratePlan {
        synchronized(streamLock) {
            captureWidth = width
            captureHeight = height
            captureFps = fps
            activeCaptureTargetFps = fps
            return resolveBitratePlanLocked()
        }
    }

    private fun resolveBitratePlanLocked(): BitratePlan {
        val computedMax = computeBaseMaxBitrateBpsLocked()
        var max = requestedMaxBitrateBps ?: computedMax
        currentAdaptiveMaxBitrateBpsLocked()?.let { adaptiveMax ->
            max = min(max, adaptiveMax)
        }
        max = max.coerceIn(300_000, 20_000_000)

        var min = requestedMinBitrateBps ?: computeMinBitrateBps(max)
        min = min.coerceIn(100_000, max)

        var start = requestedStartBitrateBps ?: computeStartBitrateBps(max)
        start = start.coerceIn(min, max)

        return BitratePlan(
            minBps = min,
            startBps = start,
            maxBps = max,
        )
    }

    private fun computeMaxBitrateBps(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val base =
            when {
                pixels >= 3_600_000L -> 10_000_000 // ~1440p and above
                pixels >= 2_000_000L -> 6_000_000  // ~1080p
                pixels >= 900_000L -> 3_800_000    // ~720p (including 720x1280)
                pixels >= 500_000L -> 2_400_000    // ~540p
                else -> 1_400_000
            }
        val baseForFps =
            when {
                fps <= 10 -> base * 3 / 5
                fps <= 15 -> base * 3 / 4
                fps >= 50 -> (base * 6) / 5
                fps >= 40 -> (base * 11) / 10
                else -> base
            }
        val scaled =
            (baseForFps * devicePerformancePreset.bitrateScale)
                .roundToInt()
                .coerceAtLeast(300_000)
        return scaled
    }

    private fun computeMinBitrateBps(maxBitrateBps: Int): Int {
        return (maxBitrateBps / 5).coerceAtLeast(600_000)
    }

    private fun computeStartBitrateBps(maxBitrateBps: Int): Int {
        return ((maxBitrateBps * 3) / 4).coerceAtLeast(1_500_000)
    }

    private fun applyVideoBitrateHints(sdp: String, plan: BitratePlan): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video ") }
        if (mLineIndex == -1) return sdp

        val mParts = lines[mLineIndex].split(" ").filter { it.isNotBlank() }
        if (mParts.size <= 3) return sdp
        val videoPayloads = mParts.drop(3).toSet()

        val rtpmapRegex = Regex("^a=rtpmap:(\\d+) ([a-zA-Z0-9-]+)/\\d+.*")
        val fmtpRegex = Regex("^a=fmtp:(\\d+)\\s+(.*)$")
        val targetCodecs = setOf("H264", "VP8", "VP9", "AV1", "H265", "HEVC")

        val targetPayloads =
            lines.mapNotNull { line ->
                val match = rtpmapRegex.find(line) ?: return@mapNotNull null
                val payload = match.groupValues[1]
                if (payload !in videoPayloads) return@mapNotNull null
                val codec = match.groupValues[2].uppercase(Locale.US)
                if (codec !in targetCodecs) return@mapNotNull null
                payload
            }.toSet()

        if (targetPayloads.isEmpty()) return sdp

        val hints =
            linkedMapOf<String, Int>().apply {
                if (ENABLE_SDP_MIN_BITRATE_HINT) {
                    put("x-google-min-bitrate", plan.minBps / 1000)
                }
                if (ENABLE_SDP_START_BITRATE_HINT) {
                    put("x-google-start-bitrate", plan.startBps / 1000)
                }
                put("x-google-max-bitrate", plan.maxBps / 1000)
            }

        var changed = false
        for (payload in targetPayloads) {
            val fmtpIndex = lines.indexOfFirst { it.startsWith("a=fmtp:$payload ") }
            if (fmtpIndex >= 0) {
                val existingParams = fmtpRegex.find(lines[fmtpIndex])?.groupValues?.get(2) ?: ""
                val rawItems =
                    existingParams.split(";")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                val nonKeyValueItems = mutableListOf<String>()
                val keyValueItems = linkedMapOf<String, String>()
                for (item in rawItems) {
                    val separator = item.indexOf('=')
                    if (separator <= 0) {
                        nonKeyValueItems.add(item)
                        continue
                    }
                    val key = item.substring(0, separator).trim().lowercase(Locale.US)
                    val value = item.substring(separator + 1).trim()
                    if (key.isNotEmpty()) {
                        keyValueItems[key] = value
                    }
                }
                if (!ENABLE_SDP_MIN_BITRATE_HINT) {
                    keyValueItems.remove("x-google-min-bitrate")
                }
                if (!ENABLE_SDP_START_BITRATE_HINT) {
                    keyValueItems.remove("x-google-start-bitrate")
                }
                hints.forEach { (key, value) ->
                    keyValueItems[key] = value.toString()
                }
                val rebuilt =
                    (nonKeyValueItems + keyValueItems.entries.map { "${it.key}=${it.value}" })
                        .joinToString(";")
                val updatedLine = "a=fmtp:$payload $rebuilt"
                if (updatedLine != lines[fmtpIndex]) {
                    lines[fmtpIndex] = updatedLine
                    changed = true
                }
            } else {
                val newLine =
                    "a=fmtp:$payload " +
                            hints.entries.joinToString(";") { "${it.key}=${it.value}" }
                lines.add(newLine)
                changed = true
            }
        }

        if (!changed) return sdp
        return lines.joinToString("\r\n")
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
        lastStatsFramesEncoded = null
        lastStatsTotalEncodeTimeSec = null
        lastStatsFramesSent = null
        lastStatsCapturedFramesForwarded = null
        lastStatsLocalDroppedCaptureFrames = null
        lastStatsSourceDropped = null
        lastStatsRemotePacketsLost = null
        lastStatsRemoteNackCount = null
        lastStatsRemotePliCount = null
        lastStatsRemoteFirCount = null
        lastLowSeverityLagLogElapsedMs = 0L
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
        lastStatsFramesEncoded = null
        lastStatsTotalEncodeTimeSec = null
        lastStatsFramesSent = null
        lastStatsCapturedFramesForwarded = null
        lastStatsLocalDroppedCaptureFrames = null
        lastStatsSourceDropped = null
        lastStatsRemotePacketsLost = null
        lastStatsRemoteNackCount = null
        lastStatsRemotePliCount = null
        lastStatsRemoteFirCount = null
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
        val framesEncoded = getLongMember(members, "framesEncoded")
        val framesDropped = getLongMember(members, "framesDropped")
        val keyFramesEncoded = getLongMember(members, "keyFramesEncoded")
        val hugeFramesSent = getLongMember(members, "hugeFramesSent")
        val totalEncodeTimeSec = getDoubleMember(members, "totalEncodeTime")
        val fps = getDoubleMember(members, "framesPerSecond")
        val width = getLongMember(members, "frameWidth")
        val height = getLongMember(members, "frameHeight")
        val qualityReason = members["qualityLimitationReason"] as? String
        val qualityDurations =
            if (ENABLE_VERBOSE_STREAM_STATS_LOG) {
                formatQualityLimitationDurations(members)
            } else {
                null
            }
        val codecId = members["codecId"] as? String
        val codecMime = codecId?.let { statsMap[it]?.members?.get("mimeType") as? String }
        val outboundSsrc = getLongMember(members, "ssrc")

        val mediaSourceVideo =
            statsMap.values.firstOrNull { stats ->
                stats.type == "media-source" && isVideoStats(stats.members)
            }
        val sourceFps = mediaSourceVideo?.let { getDoubleMember(it.members, "framesPerSecond") }
        val sourceWidth = mediaSourceVideo?.let { getLongMember(it.members, "width") }
        val sourceHeight = mediaSourceVideo?.let { getLongMember(it.members, "height") }
        val sourceDropped = mediaSourceVideo?.let { getLongMember(it.members, "framesDropped") }

        val remoteInboundVideo =
            statsMap.values.firstOrNull { stats ->
                stats.type == "remote-inbound-rtp" &&
                        isVideoStats(stats.members) &&
                        (getStringMember(stats.members, "localId") == outboundVideo.id ||
                                (outboundSsrc != null &&
                                        getLongMember(stats.members, "ssrc") == outboundSsrc))
            }
                ?: statsMap.values.firstOrNull { stats ->
                    stats.type == "remote-inbound-rtp" && isVideoStats(stats.members)
                }
        val remoteRttMs =
            remoteInboundVideo
                ?.let { getDoubleMember(it.members, "roundTripTime") }
                ?.times(1000.0)
        val remoteJitterMs =
            remoteInboundVideo
                ?.let { getDoubleMember(it.members, "jitter") }
                ?.times(1000.0)
        val remotePacketsLost = remoteInboundVideo?.let { getLongMember(it.members, "packetsLost") }
        val remoteFractionLostPct =
            remoteInboundVideo
                ?.let { getDoubleMember(it.members, "fractionLost") }
                ?.times(100.0)
        val remoteNackCount = remoteInboundVideo?.let { getLongMember(it.members, "nackCount") }
        val remotePliCount = remoteInboundVideo?.let { getLongMember(it.members, "pliCount") }
        val remoteFirCount = remoteInboundVideo?.let { getLongMember(it.members, "firCount") }

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

        val encodeMsPerFrameWindow =
            if (framesEncoded != null &&
                totalEncodeTimeSec != null &&
                lastStatsFramesEncoded != null &&
                lastStatsTotalEncodeTimeSec != null
            ) {
                val deltaFrames = framesEncoded - lastStatsFramesEncoded!!
                val deltaEncodeSec = totalEncodeTimeSec - lastStatsTotalEncodeTimeSec!!
                if (deltaFrames > 0 && deltaEncodeSec >= 0.0) {
                    (deltaEncodeSec * 1000.0) / deltaFrames.toDouble()
                } else {
                    null
                }
            } else {
                null
            }
        lastStatsFramesEncoded = framesEncoded
        lastStatsTotalEncodeTimeSec = totalEncodeTimeSec
        val framesSentDelta = deltaCounter(framesSent, lastStatsFramesSent)
        lastStatsFramesSent = framesSent
        val capturedForwardedNow = capturedFramesForwarded.get()
        val capturedForwardedDelta =
            deltaCounter(capturedForwardedNow, lastStatsCapturedFramesForwarded)
        lastStatsCapturedFramesForwarded = capturedForwardedNow
        val localDroppedNow = localDroppedCaptureFrames.get()
        val localDroppedDelta =
            deltaCounter(localDroppedNow, lastStatsLocalDroppedCaptureFrames)
        lastStatsLocalDroppedCaptureFrames = localDroppedNow
        val sourceDroppedDelta = deltaCounter(sourceDropped, lastStatsSourceDropped)
        lastStatsSourceDropped = sourceDropped
        val remotePacketsLostDelta = deltaCounter(remotePacketsLost, lastStatsRemotePacketsLost)
        lastStatsRemotePacketsLost = remotePacketsLost
        val remoteNackDelta = deltaCounter(remoteNackCount, lastStatsRemoteNackCount)
        lastStatsRemoteNackCount = remoteNackCount
        val remotePliDelta = deltaCounter(remotePliCount, lastStatsRemotePliCount)
        lastStatsRemotePliCount = remotePliCount
        val remoteFirDelta = deltaCounter(remoteFirCount, lastStatsRemoteFirCount)
        lastStatsRemoteFirCount = remoteFirCount

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
        val stallRecoveryTriggered =
            maybeRecoverFromEncoderStall(
                timestampMs = timestampMs,
                framesSent = framesSent,
                fps = fps,
                bitrateKbps = bitrateKbps,
                availableOutKbps = availableOutKbps,
                qualityReason = qualityReason,
            )
        if (!stallRecoveryTriggered) {
            maybeApplyStartupDegradationPreference()
            maybeApplyStartupAndRttAdaptation(
                rttMs = rttMs,
                availableOutKbps = availableOutKbps,
                qualityReason = qualityReason,
            )
            maybeApplyLocalFpsGovernor(
                fps = fps,
                rttMs = rttMs,
                qualityReason = qualityReason,
            )
            maybeApplySourceFpsDownshift(
                sourceFps = sourceFps,
                qualityReason = qualityReason,
            )
            maybeApplyLowFpsFallback(fps, qualityReason)
        }
        val lagDiagnosticState = collectLagDiagnosticState()
        val adaptiveMaxKbps = lagDiagnosticState.adaptiveMaxKbps
        val senderMaxFps = lagDiagnosticState.senderMaxFps
        val senderTargetFps = lagDiagnosticState.targetFps

        if (ENABLE_VERBOSE_STREAM_STATS_LOG) {
            val parts = ArrayList<String>(36)
            codecMime?.let { parts.add("codec=$it") }
            bitrateKbps?.let { parts.add("send=${it}kbps") }
            if (width != null && height != null) {
                parts.add("res=${width}x$height")
            }
            fps?.let { parts.add("fps=${String.format(Locale.US, "%.1f", it)}") }
            framesSent?.let { parts.add("sent=$it") }
            framesSentDelta?.let { parts.add("sentDelta=+$it") }
            framesEncoded?.let { parts.add("encFrames=$it") }
            framesDropped?.let { parts.add("dropped=$it") }
            keyFramesEncoded?.let { parts.add("kfs=$it") }
            hugeFramesSent?.let { parts.add("huge=$it") }
            encodeMsPerFrameWindow?.let {
                parts.add(
                    "encMs=${
                        String.format(
                            Locale.US,
                            "%.1f",
                            it
                        )
                    }"
                )
            }
            if (localDroppedNow > 0) {
                parts.add("localDrop=$localDroppedNow")
            }
            localDroppedDelta?.let { if (it > 0L) parts.add("localDropDelta=+$it") }
            capturedForwardedDelta?.let { parts.add("capturedDelta=+$it") }
            if (sourceWidth != null && sourceHeight != null) {
                parts.add("src=${sourceWidth}x$sourceHeight")
            }
            sourceFps?.let { parts.add("srcFps=${String.format(Locale.US, "%.1f", it)}") }
            sourceDropped?.let { parts.add("srcDrop=$it") }
            sourceDroppedDelta?.let { if (it > 0L) parts.add("srcDropDelta=+$it") }
            rttMs?.let { parts.add("rtt=${String.format(Locale.US, "%.0f", it)}ms") }
            remoteRttMs?.let { parts.add("rxRtt=${String.format(Locale.US, "%.0f", it)}ms") }
            remoteJitterMs?.let { parts.add("rxJit=${String.format(Locale.US, "%.1f", it)}ms") }
            remotePacketsLost?.let { parts.add("rxLost=$it") }
            remotePacketsLostDelta?.let { if (it > 0L) parts.add("rxLostDelta=+$it") }
            remoteFractionLostPct?.let {
                parts.add(
                    "rxLoss=${
                        String.format(
                            Locale.US,
                            "%.1f",
                            it
                        )
                    }%"
                )
            }
            remoteNackCount?.let { parts.add("rxNack=$it") }
            remoteNackDelta?.let { if (it > 0L) parts.add("rxNackDelta=+$it") }
            remotePliCount?.let { parts.add("rxPli=$it") }
            remotePliDelta?.let { if (it > 0L) parts.add("rxPliDelta=+$it") }
            remoteFirCount?.let { parts.add("rxFir=$it") }
            remoteFirDelta?.let { if (it > 0L) parts.add("rxFirDelta=+$it") }
            availableOutKbps?.let { parts.add("avail=${String.format(Locale.US, "%.0f", it)}kbps") }
            qualityReason?.let { parts.add("qlim=$it") }
            qualityDurations?.let { parts.add("qdur=$it") }
            adaptiveMaxKbps?.let { parts.add("adaptMax=${it}kbps") }
            if (senderMaxFps != senderTargetFps) {
                parts.add("senderMaxFps=$senderMaxFps")
            }
            if (lagDiagnosticState.captureLadderLastIndex >= 0) {
                parts.add("ladder=${lagDiagnosticState.captureLadderIndex}/${lagDiagnosticState.captureLadderLastIndex}")
            }
            lagDiagnosticState.captureProfileLabel?.let { parts.add("cap=$it") }
            if (lagDiagnosticState.localFpsGovernorActive) {
                parts.add("fpsGov=${lagDiagnosticState.localFpsGovernorLeaseMs}ms")
            }

            if (parts.isNotEmpty()) {
                Log.d(TAG, "Stats: ${parts.joinToString(" | ")}")
            }
        }
        if (ENABLE_EXTENSIVE_LAG_LOGS) {
            val normalizedQualityReason = qualityReason?.lowercase(Locale.US)
            val qualityLimited =
                normalizedQualityReason != null && normalizedQualityReason != "none"
            val fpsLow =
                fps != null &&
                        fps < (lagDiagnosticState.senderMaxFps * LAG_DIAGNOSTIC_WARN_FPS_RATIO)
            val sourceFpsLow =
                sourceFps != null &&
                        sourceFps < (lagDiagnosticState.targetFps * LAG_DIAGNOSTIC_WARN_FPS_RATIO)
            val encodeBudgetMs =
                if (lagDiagnosticState.senderMaxFps > 0) {
                    1000.0 / lagDiagnosticState.senderMaxFps.toDouble()
                } else {
                    null
                }
            val encodeOverBudget =
                encodeMsPerFrameWindow != null &&
                        encodeBudgetMs != null &&
                        encodeMsPerFrameWindow > (encodeBudgetMs * LAG_DIAGNOSTIC_ENCODE_BUDGET_RATIO)
            val sendLow = bitrateKbps != null && bitrateKbps <= LAG_DIAGNOSTIC_LOW_SEND_KBPS
            val availTight =
                availableOutKbps != null &&
                        bitrateKbps != null &&
                        availableOutKbps < (bitrateKbps * 1.15)
            val rttSpike = rttMs != null && rttMs >= RTT_SPIKE_THRESHOLD_MS
            val remoteLossHigh =
                remoteFractionLostPct != null &&
                        remoteFractionLostPct >= LAG_DIAGNOSTIC_REMOTE_LOSS_WARN_PCT
            val retxSignals =
                (remoteNackDelta ?: 0L) > 0L ||
                        (remotePliDelta ?: 0L) > 0L ||
                        (remoteFirDelta ?: 0L) > 0L
            val localDropSpikes = (localDroppedDelta ?: 0L) > 0L
            val sendStalled = (framesSentDelta ?: Long.MAX_VALUE) <= 1L
            val captureActive =
                (capturedForwardedDelta ?: 0L) >= STALL_WATCHDOG_CAPTURE_DELTA_MIN
            val captureActiveButSendStalled = sendStalled && captureActive

            val reasons = mutableListOf<String>()
            if (qualityLimited) reasons += "quality_limited=${qualityReason ?: "unknown"}"
            if (sourceFpsLow) reasons += "capture_source_fps_low"
            if (localDropSpikes) reasons += "capture_stale_drop+${localDroppedDelta ?: 0L}"
            if (captureActiveButSendStalled) reasons += "capture_active_send_stalled"
            if (encodeOverBudget) reasons += "encoder_over_budget"
            if (fpsLow) reasons += "output_fps_low"
            if (lagDiagnosticState.senderFpsCapped) {
                reasons += "sender_fps_capped=${lagDiagnosticState.senderMaxFps}/${lagDiagnosticState.targetFps}"
            }
            if (lagDiagnosticState.captureLadderIndex > 0) {
                reasons += "capture_ladder_downshift=${lagDiagnosticState.captureLadderIndex}"
            }
            if (sendLow) reasons += "send_kbps_low=${bitrateKbps ?: -1}"
            if (availTight) reasons += "avail_kbps_tight=${formatMetric(availableOutKbps, 0)}"
            if (rttSpike) reasons += "rtt_spike_ms=${formatMetric(rttMs, 0)}"
            if (remoteLossHigh) reasons += "loss_pct_high=${formatMetric(remoteFractionLostPct, 1)}"
            if (retxSignals) {
                reasons +=
                    "retransmit(nack+${remoteNackDelta ?: 0L}/pli+${remotePliDelta ?: 0L}/fir+${remoteFirDelta ?: 0L})"
            }

            val captureSignals = sourceFpsLow || localDropSpikes || captureActiveButSendStalled
            val encoderSignals = encodeOverBudget || (fpsLow && !qualityLimited && !sourceFpsLow)
            val networkSignals =
                qualityLimited || availTight || rttSpike || remoteLossHigh || retxSignals || sendLow
            val lagClass =
                when {
                    captureSignals && !encoderSignals && !networkSignals -> "capture"
                    encoderSignals && !captureSignals && !networkSignals -> "encoder"
                    networkSignals && !captureSignals && !encoderSignals -> "network"
                    captureSignals || encoderSignals || networkSignals -> "mixed"
                    else -> "none"
                }
            val severity =
                when {
                    captureActiveButSendStalled || remoteLossHigh || (fps != null && fps < 10.0) -> "high"
                    reasons.isNotEmpty() -> "medium"
                    else -> "low"
                }
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val shouldLogLagDiag =
                severity != "low" ||
                        (nowElapsedMs - lastLowSeverityLagLogElapsedMs) >=
                        LOW_SEVERITY_LAG_LOG_MIN_INTERVAL_MS
            if (shouldLogLagDiag) {
                if (severity == "low") {
                    lastLowSeverityLagLogElapsedMs = nowElapsedMs
                }
                Log.i(
                    TAG,
                    "LagDiag[$severity]: class=$lagClass reasons=${
                        if (reasons.isEmpty()) "none" else reasons.joinToString(
                            ","
                        )
                    } metrics=fps=${
                        formatMetric(
                            fps,
                            1
                        )
                    }/${lagDiagnosticState.senderMaxFps} srcFps=${
                        formatMetric(
                            sourceFps,
                            1
                        )
                    } send=${bitrateKbps ?: -1}kbps avail=${
                        formatMetric(
                            availableOutKbps,
                            0
                        )
                    }kbps rtt=${formatMetric(rttMs, 0)}ms rxLoss=${
                        formatMetric(
                            remoteFractionLostPct,
                            1
                        )
                    }% encMs=${
                        formatMetric(
                            encodeMsPerFrameWindow,
                            1
                        )
                    } budgetMs=${
                        encodeBudgetMs?.let {
                            formatMetric(
                                it,
                                1
                            )
                        } ?: "n/a"
                    } qlim=${qualityReason ?: "none"} deltas=sent+${framesSentDelta ?: 0L}/captured+${capturedForwardedDelta ?: 0L}/localDrop+${localDroppedDelta ?: 0L}/srcDrop+${sourceDroppedDelta ?: 0L} state=ladder${lagDiagnosticState.captureLadderIndex}/${lagDiagnosticState.captureLadderLastIndex} profile=${lagDiagnosticState.captureProfileLabel ?: "n/a"} plan=${lagDiagnosticState.bitratePlan.minBps}/${lagDiagnosticState.bitratePlan.startBps}/${lagDiagnosticState.bitratePlan.maxBps} adapt=${lagDiagnosticState.adaptiveMaxKbps ?: "none"}kbps startupAdapt=${lagDiagnosticState.startupAdaptiveMaxKbps ?: "none"}kbps rttAdapt=${lagDiagnosticState.rttAdaptiveMaxKbps ?: "none"}kbps governor=${if (lagDiagnosticState.localFpsGovernorActive) "on(${lagDiagnosticState.localFpsGovernorLeaseMs}ms)" else "off"} streaks=low=${lagDiagnosticState.lowFpsStreak},source=${lagDiagnosticState.sourceFpsLowStreak},downshift=${lagDiagnosticState.captureDownshiftTriggerCount}",
                )
            }
        }
    }

    private fun collectLagDiagnosticState(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): LagDiagnosticState =
        synchronized(streamLock) {
            val targetFps = captureFps.takeIf { it > 0 } ?: activeCaptureTargetFps.coerceAtLeast(1)
            val senderMaxFps = resolveEffectiveSenderMaxFps(targetFps, nowElapsedMs)
            val localGovernorActive = isLocalFpsGovernorActive(nowElapsedMs)
            val localGovernorLeaseMs =
                (localFpsGovernorCapUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
            val captureProfileLabel = captureLadderProfiles.getOrNull(captureLadderIndex)?.label()
            LagDiagnosticState(
                targetFps = targetFps,
                senderMaxFps = senderMaxFps,
                senderFpsCapped = senderMaxFps < targetFps,
                localFpsGovernorActive = localGovernorActive,
                localFpsGovernorLeaseMs = localGovernorLeaseMs,
                lowFpsStreak = lowFpsStreak,
                sourceFpsLowStreak = sourceFpsLowStreak,
                captureDownshiftTriggerCount = captureDownshiftTriggerCount,
                captureLadderIndex = captureLadderIndex,
                captureLadderLastIndex = captureLadderProfiles.lastIndex,
                captureProfileLabel = captureProfileLabel,
                adaptiveMaxKbps = adaptiveMaxBitrateBps?.div(1000),
                startupAdaptiveMaxKbps = startupAdaptiveMaxBitrateBps?.div(1000),
                rttAdaptiveMaxKbps = rttAdaptiveMaxBitrateBps?.div(1000),
                bitratePlan = resolveBitratePlanLocked(),
            )
        }

    private fun shouldEmitDecisionLog(
        lastLoggedElapsedMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (!ENABLE_EXTENSIVE_LAG_LOGS || !ENABLE_DECISION_LOGS) return false
        return (nowElapsedMs - lastLoggedElapsedMs) >= DECISION_LOG_MIN_INTERVAL_MS
    }

    private fun deltaCounter(current: Long?, previous: Long?): Long? {
        if (current == null || previous == null) return null
        return (current - previous).coerceAtLeast(0L)
    }

    private fun formatMetric(value: Double?, digits: Int): String {
        if (value == null) return "n/a"
        return String.format(Locale.US, "%.${digits}f", value)
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

    private fun formatQualityLimitationDurations(members: Map<String, Any>): String? {
        val durations = members["qualityLimitationDurations"] as? Map<*, *> ?: return null
        val order = listOf("none", "bandwidth", "cpu", "other")
        val formatted = mutableListOf<String>()
        for (key in order) {
            val raw = durations[key] ?: continue
            val value =
                when (raw) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                }
                    ?: continue
            formatted += "$key:${String.format(Locale.US, "%.1f", value)}"
        }
        return if (formatted.isEmpty()) null else formatted.joinToString(",")
    }

    private fun sendOffer(sdp: String) {
        val json =
            JSONObject().apply {
                put("id", outgoingMessageId.getAndIncrement())
                put("method", "webrtc/offer")
                put("params", JSONObject().apply { put("sdp", sdp) })
            }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
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
                        put("sessionId", streamRequestId)
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

    private fun drainOutgoingIceCandidatesLocked(): List<IceCandidate> {
        if (pendingOutgoingIceCandidates.isEmpty()) return emptyList()
        val drained = ArrayList<IceCandidate>()
        while (true) {
            val candidate = pendingOutgoingIceCandidates.poll() ?: break
            drained.add(candidate)
        }
        return drained
    }

    private fun enableOutgoingIceAndDrainLocked(): List<IceCandidate> {
        canSendOutgoingIceCandidates = true
        return drainOutgoingIceCandidatesLocked()
    }

    fun notifyStreamStopped(reason: String? = null) {
        sendStreamStopped(reason, getStreamRequestId())
    }

    fun notifyStreamStoppedAsync(reason: String? = null) {
        val requestId = getStreamRequestId()
        stopHandler.post { sendStreamStopped(reason, requestId) }
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
            return delegate.supportedCodecs.filter { isAllowed(it) }.toTypedArray()
        }

        override fun getImplementations(): Array<VideoCodecInfo> {
            return delegate.implementations.filter { isAllowed(it) }.toTypedArray()
        }

        fun codecListLabel(): String {
            return delegate.supportedCodecs
                .filter { isAllowed(it) }
                .joinToString(", ") { it.name }
        }

        private fun isAllowed(info: VideoCodecInfo): Boolean {
            return allowed.contains(info.name.uppercase(Locale.US))
        }
    }

    private class LowLatencyVideoEncoderFactory(
        private val delegate: VideoEncoderFactory,
        private val startupWindowMs: Long,
        private val startupKeyframeIntervalMs: Long,
        private val recoveryKeyframeIntervalMs: Long,
        private val stableKeyframeIntervalMs: Long,
    ) : VideoEncoderFactory {
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
            val encoder = delegate.createEncoder(info) ?: return null
            if (!info.name.equals("H264", ignoreCase = true)) {
                return encoder
            }
            Log.i(TAG, "Low-latency encoder wrapper enabled for codec=${info.name}")
            return LowLatencyVideoEncoder(
                delegate = encoder,
                startupWindowMs = startupWindowMs,
                startupKeyframeIntervalMs = startupKeyframeIntervalMs,
                recoveryKeyframeIntervalMs = recoveryKeyframeIntervalMs,
                stableKeyframeIntervalMs = stableKeyframeIntervalMs,
            )
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.getSupportedCodecs()

        override fun getImplementations(): Array<VideoCodecInfo> = delegate.getImplementations()
    }

    private class LowLatencyVideoEncoder(
        private val delegate: VideoEncoder,
        private val startupWindowMs: Long,
        private val startupKeyframeIntervalMs: Long,
        private val recoveryKeyframeIntervalMs: Long,
        private val stableKeyframeIntervalMs: Long,
    ) : VideoEncoder {
        private var encodeStartedElapsedMs = 0L
        private var lastKeyframeRequestElapsedMs = 0L
        private var lastRateFps = 0.0

        private fun effectiveKeyframeIntervalMs(nowMs: Long): Long {
            if (encodeStartedElapsedMs == 0L) {
                encodeStartedElapsedMs = nowMs
            }
            val inStartup = (nowMs - encodeStartedElapsedMs) < startupWindowMs
            val severeRecoveryIntervalMs = max(1_500L, recoveryKeyframeIntervalMs / 2)
            val veryStableIntervalMs = (stableKeyframeIntervalMs * 4L / 3L).coerceAtMost(20_000L)
            return when {
                inStartup -> startupKeyframeIntervalMs
                lastRateFps > 0.0 && lastRateFps <= 10.0 -> severeRecoveryIntervalMs
                lastRateFps > 0.0 && lastRateFps < 18.0 -> recoveryKeyframeIntervalMs
                lastRateFps >= 22.0 -> veryStableIntervalMs
                else -> stableKeyframeIntervalMs
            }
        }

        override fun createNative(native: Long): Long {
            return delegate.createNative(native)
        }

        override fun isHardwareEncoder(): Boolean {
            return delegate.isHardwareEncoder
        }

        override fun initEncode(
            settings: VideoEncoder.Settings,
            callback: VideoEncoder.Callback,
        ): VideoCodecStatus {
            encodeStartedElapsedMs = SystemClock.elapsedRealtime()
            lastKeyframeRequestElapsedMs = 0L
            lastRateFps = settings.maxFramerate.toDouble()
            return delegate.initEncode(settings, callback)
        }

        override fun release(): VideoCodecStatus {
            return delegate.release()
        }

        override fun encode(
            frame: VideoFrame,
            info: VideoEncoder.EncodeInfo,
        ): VideoCodecStatus {
            val nowMs = SystemClock.elapsedRealtime()
            val keyframeIntervalMs = effectiveKeyframeIntervalMs(nowMs)
            val hasKeyFrameRequest =
                info.frameTypes.any { it == EncodedImage.FrameType.VideoFrameKey }
            val shouldForceKeyframe =
                !hasKeyFrameRequest &&
                        (lastKeyframeRequestElapsedMs == 0L ||
                                (nowMs - lastKeyframeRequestElapsedMs) >= keyframeIntervalMs)
            val effectiveInfo =
                if (shouldForceKeyframe) {
                    VideoEncoder.EncodeInfo(
                        arrayOf(EncodedImage.FrameType.VideoFrameKey),
                    )
                } else {
                    info
                }
            val status = delegate.encode(frame, effectiveInfo)
            if (status == VideoCodecStatus.OK && (shouldForceKeyframe || hasKeyFrameRequest)) {
                lastKeyframeRequestElapsedMs = nowMs
            }
            return status
        }

        override fun setRateAllocation(
            allocation: VideoEncoder.BitrateAllocation,
            framerate: Int,
        ): VideoCodecStatus {
            val tunedFramerate =
                if (framerate in 1..4) {
                    5
                } else {
                    framerate
                }
            lastRateFps = tunedFramerate.toDouble()
            return delegate.setRateAllocation(allocation, tunedFramerate)
        }

        override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus {
            val tunedFps =
                if (parameters.framerateFps > 0.0 && parameters.framerateFps < 5.0) {
                    5.0
                } else {
                    parameters.framerateFps
                }
            val tuned =
                if (tunedFps == parameters.framerateFps) {
                    parameters
                } else {
                    VideoEncoder.RateControlParameters(parameters.bitrate, tunedFps)
                }
            lastRateFps = tunedFps
            return delegate.setRates(tuned)
        }

        override fun getScalingSettings(): VideoEncoder.ScalingSettings {
            return delegate.scalingSettings
        }

        override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> {
            return delegate.resolutionBitrateLimits
        }

        override fun getImplementationName(): String {
            return "${delegate.implementationName}-lowlat"
        }

        override fun getEncoderInfo(): VideoEncoder.EncoderInfo {
            return delegate.encoderInfo
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
        stopStream()

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
            statsThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error quitting statsThread", e)
        }

        Log.i(TAG, "WebRtcManager resources released")
    }
}
