package com.seungjae.jangsu280battery

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object UsbH264ChaseUiMode {
    fun active(context: Context): Boolean {
        val assignment = TimingOperatorStore.current(context) ?: return false
        return assignment.role.equals("CHASE", ignoreCase = true) &&
            ChaseVideoInputStore.get(context) == ChaseVideoInputMode.USB_H264
    }
}

/** Local-only mirror of USB CHASE packets. The official broadcast path is never decoded/re-encoded. */
object UsbH264PreviewTap {
    private val lock = Any()
    private var sink: ((CameraGateBroadcastPacket) -> Unit)? = null
    private var csd0: CameraGateBroadcastPacket? = null
    private var csd1: CameraGateBroadcastPacket? = null
    private var latestKey: CameraGateBroadcastPacket? = null

    fun bind(value: ((CameraGateBroadcastPacket) -> Unit)?) {
        val replay = synchronized(lock) {
            sink = value
            if (value == null) emptyList() else listOfNotNull(csd0, csd1, latestKey)
        }
        if (value != null) replay.forEach { runCatching { value(it) } }
    }

    fun offer(packet: CameraGateBroadcastPacket) {
        val target = synchronized(lock) {
            when (packet.kind) {
                CameraGateBroadcastStreamer.KIND_CSD0 -> csd0 = packet
                CameraGateBroadcastStreamer.KIND_CSD1 -> csd1 = packet
                CameraGateBroadcastStreamer.KIND_FRAME -> {
                    if ((packet.flags and CameraGateBroadcastStreamer.KEY_FRAME_FLAG) != 0) latestKey = packet
                }
            }
            sink
        }
        target?.let { runCatching { it(packet) } }
    }

    fun reset() {
        synchronized(lock) {
            csd0 = null
            csd1 = null
            latestKey = null
        }
    }
}

/** Hardware decoder used only while the CHASE Activity is visible. */
class UsbH264PreviewDecoder(
    private val surface: Surface,
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    private val closed = AtomicBoolean(false)
    private val thread = HandlerThread("UsbH264PreviewDecoderV3").apply { start() }
    private val handler = Handler(thread.looper)
    private val info = MediaCodec.BufferInfo()
    private var decoder: MediaCodec? = null
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    private var width = 1280
    private var height = 720
    private var notifiedWidth = 0
    private var notifiedHeight = 0
    private var waitingForKey = true

    fun offer(packet: CameraGateBroadcastPacket) {
        if (closed.get()) return
        handler.post { if (!closed.get()) consume(packet) }
    }

    private fun consume(packet: CameraGateBroadcastPacket) {
        val packetWidth = packet.width.coerceAtLeast(1)
        val packetHeight = packet.height.coerceAtLeast(1)
        if (packetWidth != width || packetHeight != height) {
            width = packetWidth
            height = packetHeight
        }
        notifyVideoSize(width, height)

        when (packet.kind) {
            CameraGateBroadcastStreamer.KIND_CSD0 -> {
                csd0 = packet.data.copyOf()
                configureIfReady()
                return
            }
            CameraGateBroadcastStreamer.KIND_CSD1 -> {
                csd1 = packet.data.copyOf()
                configureIfReady()
                return
            }
            CameraGateBroadcastStreamer.KIND_FRAME -> Unit
            else -> return
        }

        val key = (packet.flags and CameraGateBroadcastStreamer.KEY_FRAME_FLAG) != 0
        if (waitingForKey && !key) return
        if (!configureIfReady()) return
        val codec = decoder ?: return

        try {
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex < 0) {
                drain(codec)
                return
            }
            val input = codec.getInputBuffer(inputIndex) ?: run {
                codec.queueInputBuffer(inputIndex, 0, 0, packet.ptsUs, 0)
                return
            }
            if (input.capacity() < packet.data.size) {
                codec.queueInputBuffer(inputIndex, 0, 0, packet.ptsUs, 0)
                return
            }
            input.clear()
            input.put(packet.data)
            codec.queueInputBuffer(inputIndex, 0, packet.data.size, packet.ptsUs, 0)
            if (key) waitingForKey = false
            drain(codec)
        } catch (_: Throwable) {
            resetDecoder()
        }
    }

    private fun configureIfReady(): Boolean {
        if (decoder != null) return true
        val sps = csd0 ?: return false
        val pps = csd1 ?: return false
        return runCatching {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder = MediaCodec.createDecoderByType("video/avc").apply {
                configure(format, surface, null, 0)
                start()
            }
            waitingForKey = true
            notifyVideoSize(width, height)
            true
        }.getOrElse {
            resetDecoder()
            false
        }
    }

    private fun drain(codec: MediaCodec) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> runCatching { codec.releaseOutputBuffer(index, true) }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = runCatching { codec.outputFormat }.getOrNull()
                    val outputWidth = runCatching { format?.getInteger(MediaFormat.KEY_WIDTH) ?: width }.getOrDefault(width)
                    val outputHeight = runCatching { format?.getInteger(MediaFormat.KEY_HEIGHT) ?: height }.getOrDefault(height)
                    notifyVideoSize(outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))
                }
                else -> return
            }
        }
    }

    private fun notifyVideoSize(w: Int, h: Int) {
        if (w == notifiedWidth && h == notifiedHeight) return
        notifiedWidth = w
        notifiedHeight = h
        runCatching { onVideoSize(w, h) }
    }

    private fun resetDecoder() {
        val old = decoder
        decoder = null
        waitingForKey = true
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            resetDecoder()
            runCatching { surface.release() }
            thread.quitSafely()
        }
    }
}
