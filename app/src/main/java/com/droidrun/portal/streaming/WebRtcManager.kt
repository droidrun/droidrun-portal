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

// TODO fully refactor

/**
 * Manages WebRTC PeerConnection, Signaling, and Stream lifecycle.
 * Singleton to ensure only one active stream manager exists.
 */
class WebRtcManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamGeneration = AtomicInteger(0)
    @Volatile
    private var streamRequestId: Any? = null

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
                peerConnection?.addTrack(track, listOf(VIDEO_TRACK_ID))
            }

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
                activeConnection.setLocalDescription(SimpleSdpObserver("setLocalDescription"), desc)
                if (!isCurrentStream(streamId)) {
                    Log.w(TAG, "Offer created for stale stream after setLocalDescription; ignoring")
                    return
                }
                sendOffer(desc.description)
            }
        }, constraints)
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
