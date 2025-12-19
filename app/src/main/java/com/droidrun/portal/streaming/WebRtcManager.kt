package com.droidrun.portal.streaming

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.droidrun.portal.service.ReverseConnectionService
import org.json.JSONObject
import org.webrtc.*
import java.util.LinkedList
import java.util.Queue

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
    
    // signaling
    private var reverseConnectionService: ReverseConnectionService? = null
    private val pendingIceCandidates: Queue<IceCandidate> = LinkedList()
    private var isRemoteDescriptionSet = false
    private var outgoingMessageId = 0

    init {
        initializePeerConnectionFactory()
    }

    fun setReverseConnectionService(service: ReverseConnectionService) {
        this.reverseConnectionService = service
    }

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
        // clean
        stopStream()

        createPeerConnection(iceServers)
        createVideoTrack(permissionResultData, width, height, fps)
        
        videoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf(VIDEO_TRACK_ID))
        }

        createOffer()
    }

    fun stopStream() {
        Log.i(TAG, "Stopping WebRTC Stream")
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null

            videoSource?.dispose()
            videoSource = null
            
            videoTrack?.dispose()
            videoTrack = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            peerConnection?.close()
            peerConnection = null

            pendingIceCandidates.clear()
            isRemoteDescriptionSet = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream", e)
        }
    }

    fun handleAnswer(sdp: String) {
        Log.d(TAG, "Handling Remote Answer")
        peerConnection?.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    isRemoteDescriptionSet = true
                    drainPendingIceCandidates()
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        if (isRemoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            Log.d(TAG, "Queueing ICE candidate (remote description not set)")
            pendingIceCandidates.add(candidate)
        }
    }
    
    private fun drainPendingIceCandidates() {
        Log.d(TAG, "Draining ${pendingIceCandidates.size} pending ICE candidates")
        while (pendingIceCandidates.isNotEmpty()) {
            peerConnection?.addIceCandidate(pendingIceCandidates.poll())
        }
    }

    private fun createPeerConnection(customIceServers: List<PeerConnection.IceServer>?) {
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
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Generated ICE Candidate: ${candidate.sdpMid}")
                sendIceCandidate(candidate)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $newState")
                if (newState == PeerConnection.IceConnectionState.DISCONNECTED || newState == PeerConnection.IceConnectionState.FAILED) {
                    // handle disconnect? TODO check
                }
            }
        })
    }
    
    private fun createVideoTrack(permissionResultData: android.content.Intent, width: Int, height: Int, fps: Int) {
        // Create Screen Capturer
        screenCapturer = ScreenCapturerAndroid(permissionResultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "MediaProjection stopped externally")
                stopStream() // Or notify service to stop
            }
        })

        // Create Video Source
        videoSource = peerConnectionFactory?.createVideoSource(screenCapturer!!.isScreencast)
        
        // Start Capture
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        screenCapturer?.startCapture(width, height, fps)

        // Create Video Track
        videoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                super.onCreateSuccess(desc)
                Log.d(TAG, "Offer Created")
                peerConnection?.setLocalDescription(SimpleSdpObserver("setLocalDescription"), desc)
                sendOffer(desc.description)
            }
        }, constraints)
    }

    private fun sendOffer(sdp: String) {
        val json = JSONObject().apply {
            put("id", outgoingMessageId++)
            put("method", "webrtc/offer")
            put("params", JSONObject().apply {
                put("sdp", sdp)
            })
        }
        reverseConnectionService?.sendText(json.toString())
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("id", outgoingMessageId++)
            put("method", "webrtc/ice")
            put("params", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        reverseConnectionService?.sendText(json.toString())
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
