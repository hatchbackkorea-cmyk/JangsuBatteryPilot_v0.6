package com.seungjae.jangsu280battery

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Encoded packet emitted by the secondary 30 FPS broadcast encoder. */
data class CameraGateBroadcastPacket(
    val kind: Int,
    val ptsUs: Long,
    val flags: Int,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val mime: String,
    val fps: Int,
    val bitrate: Int,
)

/**
 * Non-blocking HD publisher for an official Camera Gate phone.
 *
 * The camera/timing thread only performs a bounded queue offer. Network writes happen on a
 * dedicated worker so a slow Wi-Fi/LTE path can never delay the timing trigger path. When the
 * queue fills we discard inter frames and resume at the next key frame with fresh codec config.
 */
class CameraGateBroadcastStreamer(
    context: Context,
    private val assignment: TimingOperatorStore.Assignment,
) {
    private val app = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val queue = ArrayBlockingQueue<Outbound>(18)
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var waitingForKeyFrame = false
    @Volatile private var latestMeta: String? = null
    @Volatile private var latestCsd0: ByteArray? = null
    @Volatile private var latestCsd1: ByteArray? = null
    @Volatile private var reconnectAfterMs = 0L

    private sealed class Outbound {
        data class Text(val value: String) : Outbound()
        data class Binary(val bytes: ByteArray, val keyFrame: Boolean, val config: Boolean) : Outbound()
    }

    init {
        worker.execute { runLoop() }
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
            put("app_version", BuildConfig.VERSION_NAME)
        }.toString()
        if (meta != latestMeta) {
            latestMeta = meta
            enqueue(Outbound.Text(meta))
        }

        val framed = framePacket(packet)
        val isConfig = packet.kind == KIND_CSD0 || packet.kind == KIND_CSD1
        val isKey = isConfig || (packet.flags and KEY_FRAME_FLAG) != 0
        if (packet.kind == KIND_CSD0) latestCsd0 = framed
        if (packet.kind == KIND_CSD1) latestCsd1 = framed

        if (waitingForKeyFrame && !isConfig && !isKey) return
        if (isKey && !isConfig) waitingForKeyFrame = false
        enqueue(Outbound.Binary(framed, keyFrame = isKey, config = isConfig))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        runCatching { socket?.close(1000, "camera gate closed") }
        socket = null
        worker.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun enqueue(item: Outbound) {
        if (queue.offer(item)) return
        // Broadcast congestion must never become timing congestion.
        queue.clear()
        waitingForKeyFrame = true
        latestMeta?.let { queue.offer(Outbound.Text(it)) }
        latestCsd0?.let { queue.offer(Outbound.Binary(it, keyFrame = true, config = true)) }
        latestCsd1?.let { queue.offer(Outbound.Binary(it, keyFrame = true, config = true)) }
        when (item) {
            is Outbound.Text -> queue.offer(item)
            is Outbound.Binary -> if (item.config || item.keyFrame) {
                waitingForKeyFrame = !item.config && !item.keyFrame
                queue.offer(item)
            }
        }
    }

    private fun runLoop() {
        while (!closed.get()) {
            try {
                if (!connected && System.currentTimeMillis() >= reconnectAfterMs) connect()
                val item = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                val ws = socket
                if (!connected || ws == null) {
                    // Keep only bootstrap state while disconnected. Live P frames are intentionally
                    // discarded; the next key frame will restart the viewer cleanly.
                    if (item is Outbound.Binary && !item.config) waitingForKeyFrame = true
                    continue
                }
                val ok = when (item) {
                    is Outbound.Text -> ws.send(item.value)
                    is Outbound.Binary -> ws.send(ByteString.of(*item.bytes))
                }
                if (!ok) {
                    connected = false
                    reconnectAfterMs = System.currentTimeMillis() + 1000L
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                connected = false
                reconnectAfterMs = System.currentTimeMillis() + 1200L
            }
        }
    }

    private fun connect() {
        if (closed.get()) return
        reconnectAfterMs = System.currentTimeMillis() + 1500L
        val base = assignment.serverUrl.trim().trimEnd('/').ifBlank {
            runCatching { RaceServerClient(app).baseUrl() }.getOrDefault("").trim().trimEnd('/')
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) return
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            else -> "ws://" + base.removePrefix("http://")
        }
        val code = URLEncoder.encode(assignment.eventCode, "UTF-8")
        val role = URLEncoder.encode(assignment.role, "UTF-8")
        val device = URLEncoder.encode(TimingOperatorStore.deviceId(app), "UTF-8")
        val token = URLEncoder.encode(assignment.token, "UTF-8")
        val request = Request.Builder()
            .url("$wsBase/api/race/camera-stream/publish/$code/$role?device_id=$device&token=$token")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                waitingForKeyFrame = true
                latestMeta?.let { webSocket.send(it) }
                latestCsd0?.let { webSocket.send(ByteString.of(*it)) }
                latestCsd1?.let { webSocket.send(ByteString.of(*it)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (!closed.get()) reconnectAfterMs = System.currentTimeMillis() + 800L
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (!closed.get()) reconnectAfterMs = System.currentTimeMillis() + 1200L
            }
        })
    }

    private fun framePacket(packet: CameraGateBroadcastPacket): ByteArray {
        val out = ByteBuffer.allocate(HEADER_BYTES + packet.data.size).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.put(PROTOCOL_VERSION)
        out.put(packet.kind.toByte())
        out.putShort((packet.flags and 0xffff).toShort())
        out.putLong(packet.ptsUs)
        out.put(packet.data)
        return out.array()
    }

    companion object {
        val MAGIC = byteArrayOf('T'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte())
        const val PROTOCOL_VERSION: Byte = 1
        const val HEADER_BYTES = 16
        const val KIND_CSD0 = 1
        const val KIND_CSD1 = 2
        const val KIND_FRAME = 3
        const val KEY_FRAME_FLAG = 1
    }
}
