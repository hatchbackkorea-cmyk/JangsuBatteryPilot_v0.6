package com.seungjae.jangsu280battery

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Camera Gate transport using an unreliable/unordered WebRTC DataChannel.
 *
 * We intentionally transport the already encoded H.264 packets produced by CameraGateGlAnalyzer
 * instead of opening a second camera. Signaling goes through RiderControlCenter, while live video
 * normally travels phone <-> operator PC directly over WebRTC/UDP. No frame queue is maintained:
 * when SCTP starts backing up, delta frames are dropped and playback resumes at the next key frame.
 *
 * Warm Standby: a preview viewer keeps the PeerConnection/DataChannel negotiated while receiving
 * sparse IDR frames. Broadcast Director V2 can send viewer_mode(preview=false) on that same peer;
 * the phone immediately switches it to full packet flow without another SDP/ICE handshake.
 */
class CameraGateWebRtcStreamer(
    context: Context,
    private val assignment: TimingOperatorStore.Assignment,
    private val onFallbackRequested: () -> Unit,
) {
    private val app = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val factory = Runtime.factory(app)

    @Volatile private var signalSocket: WebSocket? = null
    @Volatile private var latestMeta: String? = null
    @Volatile private var latestCsd0: ByteArray? = null
    @Volatile private var latestCsd1: ByteArray? = null
    @Volatile private var reconnectScheduled = false

    private data class Peer(
        val id: String,
        val connection: PeerConnection,
        val channel: DataChannel,
        @Volatile var preview: Boolean = false,
        @Volatile var waitingForKeyFrame: Boolean = true,
        @Volatile var opened: Boolean = false,
        @Volatile var lastPreviewKeyPtsUs: Long = 0L,
    )

    init {
        connectSignal()
    }

    fun offer(packet: CameraGateBroadcastPacket) {
        if (closed.get()) return
        val meta = JSONObject().apply {
            put("type", "camera_stream_meta")
            put("event_code", assignment.eventCode)
            put("role", assignment.role)
            put("device_id", TimingOperatorStore.deviceId(app))
            put("device_label", TimingOperatorStore.deviceLabel(app))
            put("mime", packet.mime)
            put("width", packet.width)
            put("height", packet.height)
            put("fps", packet.fps)
            put("bitrate", packet.bitrate)
            put("transport", "webrtc-datachannel")
            put("app_version", BuildConfig.VERSION_NAME)
        }.toString()
        if (meta != latestMeta) {
            latestMeta = meta
            peers.values.forEach { peer -> if (peer.opened) sendText(peer.channel, meta) }
        }

        val framed = framePacket(packet)
        val isConfig = packet.kind == CameraGateBroadcastStreamer.KIND_CSD0 ||
            packet.kind == CameraGateBroadcastStreamer.KIND_CSD1
        val isKey = isConfig || (packet.flags and CameraGateBroadcastStreamer.KEY_FRAME_FLAG) != 0
        if (packet.kind == CameraGateBroadcastStreamer.KIND_CSD0) latestCsd0 = framed
        if (packet.kind == CameraGateBroadcastStreamer.KIND_CSD1) latestCsd1 = framed

        peers.values.forEach { peer ->
            val channel = peer.channel
            if (!peer.opened || channel.state() != DataChannel.State.OPEN) return@forEach

            if (peer.preview) {
                if (isConfig) return@forEach
                if (!isKey) return@forEach
                if (peer.lastPreviewKeyPtsUs > 0L &&
                    packet.ptsUs - peer.lastPreviewKeyPtsUs < PREVIEW_KEY_INTERVAL_US
                ) return@forEach
                if (channel.bufferedAmount() > PREVIEW_MAX_BUFFERED_BYTES) return@forEach

                latestCsd0?.let { sendBinary(channel, it) }
                latestCsd1?.let { sendBinary(channel, it) }
                if (sendBinary(channel, framed)) {
                    peer.lastPreviewKeyPtsUs = packet.ptsUs
                    peer.waitingForKeyFrame = false
                } else {
                    peer.waitingForKeyFrame = true
                }
                return@forEach
            }

            if (channel.bufferedAmount() > MAX_BUFFERED_BYTES) {
                peer.waitingForKeyFrame = true
                return@forEach
            }
            if (peer.waitingForKeyFrame && !isConfig && !isKey) return@forEach
            if (isKey && !isConfig) {
                latestCsd0?.let { sendBinary(channel, it) }
                latestCsd1?.let { sendBinary(channel, it) }
                peer.waitingForKeyFrame = false
            }
            if (!sendBinary(channel, framed) && !isConfig) peer.waitingForKeyFrame = true
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { signalSocket?.close(1000, "camera gate closed") }
        signalSocket = null
        peers.keys.toList().forEach(::closePeer)
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun connectSignal() {
        if (closed.get()) return
        reconnectScheduled = false
        val base = assignment.serverUrl.trim().trimEnd('/').ifBlank {
            runCatching { RaceServerClient(app).baseUrl() }.getOrDefault("").trim().trimEnd('/')
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            scheduleReconnect()
            return
        }
        val wsBase = if (base.startsWith("https://")) {
            "wss://" + base.removePrefix("https://")
        } else {
            "ws://" + base.removePrefix("http://")
        }
        val code = URLEncoder.encode(assignment.eventCode, "UTF-8")
        val role = URLEncoder.encode(assignment.role, "UTF-8")
        val device = URLEncoder.encode(TimingOperatorStore.deviceId(app), "UTF-8")
        val token = URLEncoder.encode(assignment.token, "UTF-8")
        val request = Request.Builder()
            .url("$wsBase/api/race/camera-webrtc/signal/$code/$role/publisher?device_id=$device&token=$token")
            .build()
        signalSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignal(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (signalSocket === webSocket) signalSocket = null
                if (!closed.get()) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (signalSocket === webSocket) signalSocket = null
                if (!closed.get()) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closed.get() || reconnectScheduled) return
        reconnectScheduled = true
        scheduler.schedule({ connectSignal() }, 700, TimeUnit.MILLISECONDS)
    }

    private fun handleSignal(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = msg.optString("type")
        val viewerId = msg.optString("viewer_id")
        when (type) {
            "viewer_join" -> if (viewerId.isNotBlank()) createPeer(viewerId, msg.optBoolean("preview", false))
            "viewer_leave" -> if (viewerId.isNotBlank()) closePeer(viewerId)
            "viewer_mode" -> if (viewerId.isNotBlank()) {
                val peer = peers[viewerId] ?: return
                val nextPreview = msg.optBoolean("preview", false)
                if (peer.preview != nextPreview) {
                    peer.preview = nextPreview
                    peer.waitingForKeyFrame = true
                    peer.lastPreviewKeyPtsUs = 0L
                    if (!nextPreview && peer.opened) {
                        latestMeta?.let { sendText(peer.channel, it) }
                        latestCsd0?.let { sendBinary(peer.channel, it) }
                        latestCsd1?.let { sendBinary(peer.channel, it) }
                        // Promotion already has an established P2P path. Ask the encoder for an IDR
                        // so live motion begins on the next frame rather than waiting for cadence.
                        CameraGateBroadcastBridge.requestKeyFrame()
                    }
                }
            }
            "answer" -> peers[viewerId]?.connection?.setRemoteDescription(
                EmptySdpObserver,
                SessionDescription(SessionDescription.Type.ANSWER, msg.optString("sdp"))
            )
            "ice" -> {
                val candidate = msg.optString("candidate")
                if (candidate.isNotBlank()) {
                    peers[viewerId]?.connection?.addIceCandidate(
                        IceCandidate(msg.optString("sdpMid"), msg.optInt("sdpMLineIndex", 0), candidate)
                    )
                }
            }
            "fallback_request" -> {
                val peer = peers[viewerId]
                if (peer == null || !peer.preview) onFallbackRequested()
            }
        }
    }

    private fun createPeer(viewerId: String, preview: Boolean) {
        closePeer(viewerId)
        val config = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            )
        )
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                val currentPreview = peers[viewerId]?.preview ?: preview
                if (!currentPreview && (
                        newState == PeerConnection.IceConnectionState.FAILED ||
                            newState == PeerConnection.IceConnectionState.DISCONNECTED
                        )
                ) onFallbackRequested()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                sendSignal(JSONObject().apply {
                    put("type", "ice")
                    put("viewer_id", viewerId)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dataChannel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        }
        val pc = factory.createPeerConnection(config, observer) ?: run {
            if (!preview) onFallbackRequested()
            return
        }
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        val channel = pc.createDataChannel(if (preview) "timegate-preview" else "timegate-video", init)
        val peer = Peer(viewerId, pc, channel, preview = preview)
        peers[viewerId] = peer
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    peer.opened = true
                    peer.waitingForKeyFrame = true
                    latestMeta?.let { sendText(channel, it) }
                    latestCsd0?.let { sendBinary(channel, it) }
                    latestCsd1?.let { sendBinary(channel, it) }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) = Unit
        })

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null || closed.get()) return
                pc.setLocalDescription(EmptySdpObserver, description)
                sendSignal(JSONObject().apply {
                    put("type", "offer")
                    put("viewer_id", viewerId)
                    put("sdp", description.description)
                })
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                if (peers[viewerId]?.preview != true) onFallbackRequested()
            }
            override fun onSetFailure(error: String?) {
                if (peers[viewerId]?.preview != true) onFallbackRequested()
            }
        }, MediaConstraints())

        scheduler.schedule({
            val p = peers[viewerId]
            if (p != null && !p.preview && !closed.get() && !p.opened) onFallbackRequested()
        }, PEER_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun closePeer(viewerId: String) {
        val peer = peers.remove(viewerId) ?: return
        runCatching { peer.channel.unregisterObserver() }
        runCatching { peer.channel.close() }
        runCatching { peer.connection.close() }
        runCatching { peer.channel.dispose() }
        runCatching { peer.connection.dispose() }
    }

    private fun sendSignal(json: JSONObject) {
        signalSocket?.send(json.toString())
    }

    private fun sendText(channel: DataChannel, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
        return runCatching { channel.send(DataChannel.Buffer(buffer, false)) }.getOrDefault(false)
    }

    private fun sendBinary(channel: DataChannel, bytes: ByteArray): Boolean {
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
        return runCatching { channel.send(DataChannel.Buffer(buffer, true)) }.getOrDefault(false)
    }

    private fun framePacket(packet: CameraGateBroadcastPacket): ByteArray {
        val out = ByteBuffer.allocate(CameraGateBroadcastStreamer.HEADER_BYTES + packet.data.size)
            .order(ByteOrder.BIG_ENDIAN)
        out.put(CameraGateBroadcastStreamer.MAGIC)
        out.put(CameraGateBroadcastStreamer.PROTOCOL_VERSION)
        out.put(packet.kind.toByte())
        out.putShort((packet.flags and 0xffff).toShort())
        out.putLong(packet.ptsUs)
        out.put(packet.data)
        return out.array()
    }

    private object EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private object Runtime {
        @Volatile private var factoryInstance: PeerConnectionFactory? = null
        private val lock = Any()

        fun factory(context: Context): PeerConnectionFactory {
            factoryInstance?.let { return it }
            synchronized(lock) {
                factoryInstance?.let { return it }
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                return PeerConnectionFactory.builder().createPeerConnectionFactory().also {
                    factoryInstance = it
                }
            }
        }
    }

    companion object {
        private const val MAX_BUFFERED_BYTES = 160L * 1024L
        private const val PREVIEW_MAX_BUFFERED_BYTES = 96L * 1024L
        private const val PREVIEW_KEY_INTERVAL_US = 1_800_000L
        private const val PEER_OPEN_TIMEOUT_MS = 2200L
    }
}
