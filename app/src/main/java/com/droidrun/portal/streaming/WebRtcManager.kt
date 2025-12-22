package com.droidrun.portal.streaming

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Handler
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
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var streamGeneration = 0
    private var streamRequestId: Any? = null

    init {
        initializePeerConnectionFactory()
    }

    fun setReverseConnectionService(service: ReverseConnectionService) {
        this.reverseConnectionService = service
    }

    fun setStreamRequestId(requestId: Any?) {
        synchronized(streamLock) {
            streamRequestId = requestId
        }
    }

    fun getStreamRequestId(): Any? = synchronized(streamLock) { streamRequestId }

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
        synchronized(streamLock) {
            Log.i(TAG, "Starting WebRTC Stream: ${width}x${height} @ $fps fps")
            streamGeneration += 1
            val streamId = streamGeneration
            // Clean up first
            stopStreamInternal()

            createPeerConnection(iceServers, streamId)
            createVideoTrack(permissionResultData, width, height, fps, streamId)
            
            videoTrack?.let { track ->
                peerConnection?.addTrack(track, listOf(VIDEO_TRACK_ID))
            }

            createOffer(streamId)
        }
    }

    fun stopStream() {
        synchronized(streamLock) {
            streamGeneration += 1
            stopStreamInternal()
            streamRequestId = null
        }
    }
    
    private fun stopStreamInternal() {
        Log.i(TAG, "Stopping WebRTC Stream")
        
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer", e)
        }
        
        try {
            screenCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer", e)
        }
        screenCapturer = null

        try {
            videoSource?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing videoSource", e)
        }
        videoSource = null
        
        try {
            videoTrack?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing videoTrack", e)
        }
        videoTrack = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing surfaceTextureHelper", e)
        }
        surfaceTextureHelper = null

        try {
            peerConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing peerConnection", e)
        }
        peerConnection = null

        pendingIceCandidates.clear()
        isRemoteDescriptionSet = false
    }

    fun handleAnswer(sdp: String) {
        synchronized(streamLock) {
            Log.d(TAG, "Handling Remote Answer")
            if (peerConnection == null) {
                Log.w(TAG, "handleAnswer called but no active PeerConnection")
                return
            }
            peerConnection?.setRemoteDescription(
                object : SimpleSdpObserver("setRemoteDescription") {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        synchronized(streamLock) {
                            isRemoteDescriptionSet = true
                            drainPendingIceCandidates()
                        }
                    }
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp)
            )
        }
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        synchronized(streamLock) {
            if (peerConnection == null) {
                Log.w(TAG, "handleIceCandidate called but no active PeerConnection")
                return
            }
            if (isRemoteDescriptionSet) {
                peerConnection?.addIceCandidate(candidate)
            } else {
                Log.d(TAG, "Queueing ICE candidate (remote description not set)")
                pendingIceCandidates.add(candidate)
            }
        }
    }
    
    private fun drainPendingIceCandidates() {
        Log.d(TAG, "Draining ${pendingIceCandidates.size} pending ICE candidates")
        while (pendingIceCandidates.isNotEmpty()) {
            peerConnection?.addIceCandidate(pendingIceCandidates.poll())
        }
    }

    private fun isCurrentStreamLocked(streamId: Int): Boolean {
        return streamId == streamGeneration && peerConnection != null
    }

    private fun isCurrentStream(streamId: Int): Boolean {
        return synchronized(streamLock) { isCurrentStreamLocked(streamId) }
    }

    private fun postStopStreamIfCurrent(streamId: Int) {
        mainHandler.post {
            synchronized(streamLock) {
                if (!isCurrentStreamLocked(streamId)) return@post
                streamGeneration += 1
                stopStreamInternal()
                streamRequestId = null
            }
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
                synchronized(streamLock) {
                    if (!isCurrentStreamLocked(streamId)) {
                        Log.w(TAG, "Offer created for stale stream; ignoring")
                        return
                    }
                    Log.d(TAG, "Offer Created")
                    peerConnection?.setLocalDescription(SimpleSdpObserver("setLocalDescription"), desc)
                    sendOffer(desc.description)
                }
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
