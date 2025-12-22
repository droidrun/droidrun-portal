package com.droidrun.portal.streaming

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.droidrun.portal.service.ReverseConnectionService
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlin.math.max

// TODO fully refactor

/**
 * Manages WebRTC PeerConnection, Signaling, and Stream lifecycle.
 * Singleton to ensure only one active stream manager exists.
 */
class WebRtcManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val H264_CODEC_NAME = "H264/90000"
        private const val STATS_INTERVAL_MS = 5000L

        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private val eglBase: EglBase by lazy { EglBase.create() }
    
    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    
    @Volatile
    private var reverseConnectionService: ReverseConnectionService? = null
    private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
    @Volatile
    private var isRemoteDescriptionSet = false
    private val outgoingMessageId = AtomicInteger(0)
    
    // for all peerConnection lifecycle operations
    private val streamLock = Any()
    
    private val stopThread = HandlerThread("WebRtcStop").apply { start() }
    private val stopHandler = Handler(stopThread.looper)
    private val statsThread = HandlerThread("WebRtcStats").apply { start() }
    private val statsHandler = Handler(statsThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamGeneration = AtomicInteger(0)
    @Volatile
    private var streamRequestId: Any? = null
    private var statsRunnable: Runnable? = null
    private var lastStatsBytesSent: Long? = null
    private var lastStatsTimestampMs: Long? = null

    init {
        initializePeerConnectionFactory()
    }

    fun setReverseConnectionService(service: ReverseConnectionService) {
        this.reverseConnectionService = service
    }

    fun setStreamRequestId(requestId: Any?) {
        streamRequestId = requestId
    }

    fun getStreamRequestId(): Any? = streamRequestId

    fun isStreamActive(): Boolean = synchronized(streamLock) { peerConnection != null }

    private fun initializePeerConnectionFactory() {
        Log.d(TAG, "Initializing PeerConnectionFactory")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun startStream(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
        iceServers: List<PeerConnection.IceServer>? = null
    ) {
        Log.i(TAG, "Starting WebRTC Stream: ${width}x${height} @ $fps fps")
        val streamId = streamGeneration.incrementAndGet()
        val staleResources = synchronized(streamLock) {
            detachStreamResourcesLocked()
        }
        cleanupStreamResources(staleResources)
        synchronized(streamLock) {
            createPeerConnection(iceServers, streamId)
            createVideoTrack(permissionResultData, width, height, fps, streamId)
            
            videoTrack?.let { track ->
                val sender = peerConnection?.addTrack(track, listOf(VIDEO_TRACK_ID))
                configureVideoSender(sender, width, height, fps)
            }

            startStatsLogging(streamId)
            createOffer(streamId)
        }
    }

    fun stopStream() {
        val resources = synchronized(streamLock) {
            streamGeneration.incrementAndGet()
            streamRequestId = null
            detachStreamResourcesLocked()
        }
        cleanupStreamResources(resources)
    }

    fun stopStreamAsync(onStopped: (() -> Unit)? = null) {
        val stopGeneration = streamGeneration.get()
        Log.i(TAG, "stopStreamAsync requested (gen=$stopGeneration)")
        stopHandler.post {
            var invokeCallback = false
            val resources = synchronized(streamLock) {
                val currentGeneration = streamGeneration.get()
                if (stopGeneration != currentGeneration) {
                    Log.w(TAG, "stopStreamAsync superseded (gen=$stopGeneration current=$currentGeneration)")
                    invokeCallback = true
                    null
                } else {
                    streamGeneration.incrementAndGet()
                    streamRequestId = null
                    invokeCallback = true
                    detachStreamResourcesLocked()
                }
            }
            if (resources != null) {
                cleanupStreamResources(resources)
            }
            if (invokeCallback && onStopped != null) {
                mainHandler.post { onStopped() }
            }
            Log.i(TAG, "stopStreamAsync finished (gen=$stopGeneration)")
        }
    }
    
    private data class StreamResources(
        val screenCapturer: VideoCapturer?,
        val videoSource: VideoSource?,
        val videoTrack: VideoTrack?,
        val surfaceTextureHelper: SurfaceTextureHelper?,
        val peerConnection: PeerConnection?
    )

    private fun detachStreamResourcesLocked(): StreamResources {
        val resources = StreamResources(
            screenCapturer = screenCapturer,
            videoSource = videoSource,
            videoTrack = videoTrack,
            surfaceTextureHelper = surfaceTextureHelper,
            peerConnection = peerConnection
        )
        screenCapturer = null
        videoSource = null
        videoTrack = null
        surfaceTextureHelper = null
        peerConnection = null
        stopStatsLogging()
        pendingIceCandidates.clear()
        isRemoteDescriptionSet = false
        return resources
    }

    private fun cleanupStreamResources(resources: StreamResources) {
        Log.i(TAG, "Stopping WebRTC Stream")

        try {
            Log.d(TAG, "Stopping screen capturer")
            resources.screenCapturer?.stopCapture()
            Log.d(TAG, "Screen capturer stopped")
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

        try {
            Log.d(TAG, "Closing peerConnection")
            resources.peerConnection?.close()
            Log.d(TAG, "peerConnection closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing peerConnection", e)
        }
    }

    fun handleAnswer(sdp: String) {
        Log.d(TAG, "Handling Remote Answer")
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
                    var shouldReturn = false
                    synchronized(streamLock) {
                        if (peerConnection !== activeConnection || streamGeneration.get() != generation) {
                            Log.w(TAG, "Remote description set on stale PeerConnection")
                            shouldReturn = true
                        } else {
                            isRemoteDescriptionSet = true
                            pending = drainPendingIceCandidatesLocked()
                        }
                    }
                    if (shouldReturn) return
                    pending?.let { candidates ->
                        for (candidate in candidates) {
                            activeConnection.addIceCandidate(candidate)
                        }
                    }
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        var connection: PeerConnection? = null
        var shouldReturn = false
        synchronized(streamLock) {
            if (peerConnection == null) {
                Log.w(TAG, "handleIceCandidate called but no active PeerConnection")
                shouldReturn = true
            } else if (!isRemoteDescriptionSet) {
                Log.d(TAG, "Queueing ICE candidate (remote description not set)")
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
        Log.d(TAG, "Drained ${drained.size} pending ICE candidates")
        return drained
    }

    private fun isCurrentStreamLocked(streamId: Int): Boolean {
        return streamId == streamGeneration.get() && peerConnection != null
    }

    private fun isCurrentStream(streamId: Int): Boolean {
        return synchronized(streamLock) { isCurrentStreamLocked(streamId) }
    }

    private fun postStopStreamIfCurrent(streamId: Int) {
        stopHandler.post {
            val resources = synchronized(streamLock) {
                if (!isCurrentStreamLocked(streamId)) {
                    null
                } else {
                    streamGeneration.incrementAndGet()
                    streamRequestId = null
                    detachStreamResourcesLocked()
                }
            } ?: return@post
            cleanupStreamResources(resources)
        }
    }

    private fun createPeerConnection(customIceServers: List<PeerConnection.IceServer>?, streamId: Int) {
        val iceServers = ArrayList<PeerConnection.IceServer>()
        if (customIceServers != null && customIceServers.isNotEmpty()) {
             iceServers.addAll(customIceServers)
        } else {
            // Default google STUN
            iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED // Prefer UDP TODO check both
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : CustomPeerConnectionObserver() {
            override fun onIceCandidate(p0: IceCandidate) {
                Log.d(TAG, "Generated ICE Candidate: ${p0.sdpMid}")
                if (!isCurrentStream(streamId)) {
                    Log.w(TAG, "ICE candidate on stale stream; ignoring")
                    return
                }
                sendIceCandidate(p0)
            }

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $p0")
                if (p0 == PeerConnection.IceConnectionState.FAILED) {
                    if (!isCurrentStream(streamId)) {
                        Log.w(TAG, "ICE failure on stale stream; ignoring")
                        return
                    }
                    Log.e(TAG, "ICE connection failed - stopping stream")
                    sendStreamError("ice_connection_failed", "ICE connection failed")
                    postStopStreamIfCurrent(streamId)
                } else if (p0 == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "ICE connection disconnected (may be transient)")
                }
            }
        })
    }
    
    private fun createVideoTrack(
        permissionResultData: android.content.Intent,
        width: Int,
        height: Int,
        fps: Int,
        streamId: Int,
    ) {
        screenCapturer = ScreenCapturerAndroid(permissionResultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "MediaProjection stopped externally")
                postStopStreamIfCurrent(streamId)
            }
        })

        videoSource = peerConnectionFactory?.createVideoSource(screenCapturer!!.isScreencast)
        
        // Start Capture
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        try {
            screenCapturer?.startCapture(width, height, fps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopStream()
            throw RuntimeException("Failed to start screen capture: ${e.message}", e)
        }

        videoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
    }

    private fun createOffer(streamId: Int) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                super.onCreateSuccess(desc)
                var connection: PeerConnection? = null
                synchronized(streamLock) {
                    if (!isCurrentStreamLocked(streamId)) {
                        Log.w(TAG, "Offer created for stale stream; ignoring")
                    } else {
                        connection = peerConnection
                    }
                }
                val activeConnection = connection ?: return
                Log.d(TAG, "Offer Created")
                val mungedSdp = preferH264(desc.description)
                val offer = if (mungedSdp == desc.description) {
                    desc
                } else {
                    Log.i(TAG, "Reordered SDP to prefer H264")
                    SessionDescription(desc.type, mungedSdp)
                }
                setLocalDescriptionAndSendOffer(activeConnection, streamId, offer, desc, offer != desc)
            }
        }, constraints)
    }

    private fun setLocalDescriptionAndSendOffer(
        connection: PeerConnection,
        streamId: Int,
        offer: SessionDescription,
        fallback: SessionDescription,
        allowFallback: Boolean,
    ) {
        connection.setLocalDescription(object : SimpleSdpObserver("setLocalDescription") {
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
                setLocalDescriptionAndSendOffer(connection, streamId, fallback, fallback, false)
            }
        }, offer)
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
        val base = when {
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
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains(H264_CODEC_NAME)) {
                val payload = line.substringAfter("a=rtpmap:").substringBefore(' ').trim()
                if (payload.isNotEmpty()) {
                    h264Payloads.add(payload)
                }
            } else if (line.startsWith("a=fmtp:") && line.contains("apt=")) {
                val payload = line.substringAfter("a=fmtp:").substringBefore(' ').trim()
                val aptValue = line.substringAfter("apt=", "").substringBefore(';').trim()
                if (payload.isNotEmpty() && aptValue.isNotEmpty()) {
                    rtxAptMap[payload] = aptValue
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
        val munged = lines.joinToString("\r\n")
        return if (sdp.endsWith("\r\n")) "$munged\r\n" else munged
    }

    private fun startStatsLogging(streamId: Int) {
        stopStatsLogging()
        lastStatsBytesSent = null
        lastStatsTimestampMs = null
        val runnable = object : Runnable {
            override fun run() {
                val connection = synchronized(streamLock) {
                    if (isCurrentStreamLocked(streamId)) peerConnection else null
                } ?: return
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
    }

    private fun logStats(report: RTCStatsReport) {
        val statsMap = report.statsMap
        val outboundVideo = statsMap.values.firstOrNull { stats ->
            stats.type == "outbound-rtp" &&
                !getBooleanMember(stats.members, "isRemote", false) &&
                isVideoStats(stats.members)
        } ?: return

        val members = outboundVideo.members
        val bytesSent = getLongMember(members, "bytesSent")
        val framesSent = getLongMember(members, "framesSent") ?: getLongMember(members, "framesEncoded")
        val framesDropped = getLongMember(members, "framesDropped")
        val fps = getDoubleMember(members, "framesPerSecond")
        val width = getLongMember(members, "frameWidth")
        val height = getLongMember(members, "frameHeight")
        val qualityReason = members["qualityLimitationReason"] as? String
        val codecId = members["codecId"] as? String
        val codecMime = codecId?.let { statsMap[it]?.members?.get("mimeType") as? String }

        val timestampMs = (outboundVideo.timestampUs / 1000.0).toLong()
        val bitrateKbps = if (bytesSent != null && lastStatsBytesSent != null && lastStatsTimestampMs != null) {
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

        val candidatePair = statsMap.values.firstOrNull { stats ->
            stats.type == "candidate-pair" &&
                getStringMember(stats.members, "state") == "succeeded" &&
                getBooleanMember(stats.members, "nominated", false)
        }
        val rttMs = candidatePair?.let { getDoubleMember(it.members, "currentRoundTripTime") }?.times(1000.0)
        val availableOutKbps = candidatePair?.let {
            getDoubleMember(it.members, "availableOutgoingBitrate")
        }?.div(1000.0)

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
            Log.i(TAG, "Stats: ${parts.joinToString(" | ")}")
        }
    }

    private fun isVideoStats(members: Map<String, Any>): Boolean {
        val kind = (members["kind"] ?: members["mediaType"]) as? String
        return kind == "video"
    }

    private fun getStringMember(members: Map<String, Any>, key: String): String? {
        return members[key] as? String
    }

    private fun getBooleanMember(members: Map<String, Any>, key: String, default: Boolean): Boolean {
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

    private fun sendOffer(sdp: String) {
        val json = JSONObject().apply {
            put("id", outgoingMessageId.getAndIncrement())
            put("method", "webrtc/offer")
            put("params", JSONObject().apply {
                put("sdp", sdp)
            })
        }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("id", outgoingMessageId.getAndIncrement())
            put("method", "webrtc/ice")
            put("params", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun sendStreamError(error: String, message: String) {
        try {
            val requestId = getStreamRequestId()
            val json = JSONObject().apply {
                put("method", "stream/error")
                put("params", JSONObject().apply {
                    put("error", error)
                    put("message", message)
                    if (requestId != null) {
                        put("request_id", requestId)
                    }
                })
            }
            reverseConnectionService?.sendText(json.toString())
            Log.d(TAG, "Sent stream/error: $error - $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stream error", e)
        }
    }

    fun notifyStreamStopped(reason: String? = null) {
        sendStreamStopped(reason, getStreamRequestId())
    }

    fun notifyStreamStoppedAsync(reason: String? = null) {
        val requestId = getStreamRequestId()
        stopHandler.post { sendStreamStopped(reason, requestId) }
    }

    private fun sendStreamStopped(reason: String?, requestId: Any?) {
        try {
            val json = JSONObject().apply {
                put("method", "stream/stopped")
                put("params", JSONObject().apply {
                    if (!reason.isNullOrBlank()) {
                        put("reason", reason)
                    }
                    if (requestId != null) {
                        put("request_id", requestId)
                    }
                })
            }
            reverseConnectionService?.sendText(json.toString())
            Log.d(TAG, "Sent stream/stopped${if (reason.isNullOrBlank()) "" else ": $reason"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stream stopped", e)
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
        override fun onCreateFailure(p0: String?) { Log.e(TAG, "$name onCreateFailure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e(TAG, "$name onSetFailure: $p0") }
    }
}
