package com.seungjae.jangsu280battery

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
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
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Native H.264 viewer for the Camera Gate relay. Only one role is decoded at a time. */
class RaceCameraStreamClient(
    private val baseUrl: String,
    private val eventCode: String,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val codecThread = HandlerThread("RaceCameraStreamDecoder").apply { start() }
    private val codecHandler = Handler(codecThread.looper)
    private val pending = ArrayDeque<Frame>()
    private val availableInputs = ArrayDeque<Int>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var currentRole = ""
    @Volatile private var closed = false

    private var decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private var mime = "video/avc"
    private var width = 1920
    private var height = 1080
    private var fps = 30
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    private var waitingForKey = true

    private data class Frame(val ptsUs: Long, val flags: Int, val data: ByteArray)

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                codecHandler.post { rebuildDecoderIfReady() }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                codecHandler.post { releaseDecoder() }
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun switchRole(role: String) {
        val clean = role.trim().uppercase()
        if (closed || clean == currentRole) return
        generation += 1
        val mine = generation
        currentRole = clean
        runCatching { socket?.cancel() }
        socket = null
        codecHandler.post {
            resetStreamState()
            status("$clean 카메라 연결 중…")
        }
        connect(clean, mine)
    }

    fun close() {
        if (closed) return
        closed = true
        generation += 1
        runCatching { socket?.close(1000, "viewer closed") }
        socket = null
        codecHandler.post { releaseDecoder() }
        codecThread.quitSafely()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun connect(role: String, mine: Int) {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            status("중계 서버 주소를 확인해 주세요")
            return
        }
        val wsBase = if (base.startsWith("https://")) {
            "wss://" + base.removePrefix("https://")
        } else {
            "ws://" + base.removePrefix("http://")
        }
        val code = URLEncoder.encode(eventCode, "UTF-8")
        val encodedRole = URLEncoder.encode(role, "UTF-8")
        val req = Request.Builder().url("$wsBase/api/race/camera-stream/watch/$code/$encodedRole").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (mine != generation || closed) {
                    webSocket.close(1000, "stale role")
                    return
                }
                status("$role 카메라 대기 중")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mine != generation || closed) return
                handleText(role, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (mine != generation || closed) return
                val copy = bytes.toByteArray()
                codecHandler.post {
                    if (mine == generation && !closed) handleBinary(role, copy)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (mine != generation || closed) return
                status("$role 카메라 연결 끊김 · 재선택하면 다시 연결")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (mine != generation || closed) return
                status("$role 카메라 연결 종료")
            }
        })
    }

    private fun handleText(role: String, raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optString("type") != "camera_stream_meta") return
        val nextMime = json.optString("mime", "video/avc")
        val nextWidth = json.optInt("width", 1920).coerceAtLeast(1)
        val nextHeight = json.optInt("height", 1080).coerceAtLeast(1)
        val nextFps = json.optInt("fps", 30).coerceAtLeast(1)
        codecHandler.post {
            val changed = nextMime != mime || nextWidth != width || nextHeight != height
            mime = nextMime
            width = nextWidth
            height = nextHeight
            fps = nextFps
            if (changed) releaseDecoder()
            rebuildDecoderIfReady()
            status("$role · ${width}×$height · ${fps}fps")
        }
    }

    private fun handleBinary(role: String, raw: ByteArray) {
        if (raw.size < CameraGateBroadcastStreamer.HEADER_BYTES) return
        val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        b.get(magic)
        if (!magic.contentEquals(CameraGateBroadcastStreamer.MAGIC)) return
        val version = b.get().toInt() and 0xff
        if (version != CameraGateBroadcastStreamer.PROTOCOL_VERSION.toInt()) return
        val kind = b.get().toInt() and 0xff
        val flags = b.short.toInt() and 0xffff
        val ptsUs = b.long
        val payload = ByteArray(b.remaining())
        b.get(payload)

        when (kind) {
            CameraGateBroadcastStreamer.KIND_CSD0 -> {
                csd0 = payload
                rebuildDecoderIfReady()
            }
            CameraGateBroadcastStreamer.KIND_CSD1 -> {
                csd1 = payload
                rebuildDecoderIfReady()
            }
            CameraGateBroadcastStreamer.KIND_FRAME -> {
                val key = flags and CameraGateBroadcastStreamer.KEY_FRAME_FLAG != 0
                if (waitingForKey && !key) return
                if (key) waitingForKey = false
                if (pending.size >= MAX_PENDING) {
                    pending.clear()
                    waitingForKey = true
                    if (!key) return
                    waitingForKey = false
                }
                pending.addLast(Frame(ptsUs, flags, payload))
                rebuildDecoderIfReady()
                pumpDecoder()
                if (key) status("$role · LIVE · ${width}×$height ${fps}fps")
            }
        }
    }

    private fun rebuildDecoderIfReady() {
        if (closed || decoder != null || csd0 == null || !textureView.isAvailable) return
        val st = textureView.surfaceTexture ?: return
        try {
            val surface = Surface(st)
            val codec = MediaCodec.createDecoderByType(mime)
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
            }
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (closed) return
                    availableInputs.addLast(index)
                    pumpDecoder()
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    status("$currentRole 디코더 오류 · ${e.diagnosticInfo}")
                    codecHandler.post {
                        releaseDecoder()
                        waitingForKey = true
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            }, codecHandler)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoderSurface = surface
            decoder = codec
            waitingForKey = true
            pumpDecoder()
        } catch (e: Throwable) {
            status("$currentRole 영상 디코더 준비 실패 · ${e.message ?: e.javaClass.simpleName}")
            releaseDecoder()
        }
    }

    private fun pumpDecoder() {
        val codec = decoder ?: return
        while (!closed && availableInputs.isNotEmpty() && pending.isNotEmpty()) {
            val index = availableInputs.removeFirst()
            val frame = pending.removeFirst()
            val input = codec.getInputBuffer(index)
            if (input == null || input.capacity() < frame.data.size) {
                runCatching { codec.queueInputBuffer(index, 0, 0, frame.ptsUs, 0) }
                continue
            }
            input.clear()
            input.put(frame.data)
            runCatching {
                codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, frame.flags)
            }.onFailure {
                waitingForKey = true
                pending.clear()
            }
        }
    }

    private fun resetStreamState() {
        releaseDecoder()
        pending.clear()
        availableInputs.clear()
        csd0 = null
        csd1 = null
        waitingForKey = true
        mime = "video/avc"
        width = 1920
        height = 1080
        fps = 30
    }

    private fun releaseDecoder() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        runCatching { decoderSurface?.release() }
        decoderSurface = null
        availableInputs.clear()
        pending.clear()
    }

    private fun status(value: String) {
        textureView.post { if (!closed) onStatus(value) }
    }

    companion object {
        private const val MAX_PENDING = 8
    }
}
