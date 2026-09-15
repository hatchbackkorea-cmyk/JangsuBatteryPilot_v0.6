package com.seungjae.jangsu280battery

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Saves the already-encoded 720p24 broadcast stream to MP4 without starting another video encoder.
 * offer() is non-blocking so local recording can never stall the camera/broadcast thread.
 */
class BroadcastLocalRecorder(
    context: Context,
    private val onFinished: (Uri?, String?) -> Unit,
) : CameraGateBroadcastPacketSink {
    private val app = context.applicationContext
    private val queue = ArrayBlockingQueue<CameraGateBroadcastPacket>(120)
    @Volatile private var accepting = true
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var waitingForKeyFrame = true
    private var firstPtsUs = -1L
    private var outputUri: Uri? = null
    private var outputName: String? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null

    private val worker = thread(name = "TimeGateLocalRecorder", start = true) {
        try {
            prepareOutput()
            while (accepting || queue.isNotEmpty()) {
                val packet = queue.poll(120, TimeUnit.MILLISECONDS) ?: continue
                handle(packet)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (_: Throwable) {
            // Finalizer below still closes a partially-created item safely.
        } finally {
            finalizeOutput()
        }
    }

    override fun offer(packet: CameraGateBroadcastPacket) {
        if (!accepting) return
        if (queue.offer(packet)) return
        // Never back-pressure live video. If local storage falls behind, discard the oldest delta
        // frame and continue from fresh packets; the network stream remains completely independent.
        val drained = mutableListOf<CameraGateBroadcastPacket>()
        queue.drainTo(drained)
        drained.filter { it.kind != CameraGateBroadcastStreamer.KIND_FRAME || (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
            .takeLast(4)
            .forEach { queue.offer(it) }
        queue.offer(packet)
    }

    fun stop() {
        accepting = false
    }

    fun isRecording(): Boolean = accepting

    private fun prepareOutput() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "TimeGate_$stamp.mp4"
        outputName = name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TimeGate")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("동영상 저장 위치를 만들 수 없습니다.")
            outputUri = uri
            val pfd = app.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("동영상 파일을 열 수 없습니다.")
            outputPfd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TimeGate").apply { mkdirs() }
            val file = File(dir, name)
            outputUri = Uri.fromFile(file)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun handle(packet: CameraGateBroadcastPacket) {
        when (packet.kind) {
            CameraGateBroadcastStreamer.KIND_CSD0 -> {
                csd0 = packet.data.copyOf()
                ensureTrack(packet)
            }
            CameraGateBroadcastStreamer.KIND_CSD1 -> {
                csd1 = packet.data.copyOf()
                ensureTrack(packet)
            }
            CameraGateBroadcastStreamer.KIND_FRAME -> {
                ensureTrack(packet)
                if (!started || trackIndex < 0 || packet.ptsUs <= 0L) return
                val key = (packet.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (waitingForKeyFrame) {
                    if (!key) return
                    waitingForKeyFrame = false
                    firstPtsUs = packet.ptsUs
                }
                val pts = (packet.ptsUs - firstPtsUs).coerceAtLeast(0L)
                val info = MediaCodec.BufferInfo().apply {
                    set(0, packet.data.size, pts, packet.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                }
                muxer?.writeSampleData(trackIndex, ByteBuffer.wrap(packet.data), info)
            }
        }
    }

    private fun ensureTrack(packet: CameraGateBroadcastPacket) {
        if (started) return
        val a = csd0 ?: return
        val b = csd1 ?: return
        val format = MediaFormat.createVideoFormat(packet.mime, packet.width, packet.height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, packet.fps)
            setInteger(MediaFormat.KEY_BIT_RATE, packet.bitrate)
            setByteBuffer("csd-0", ByteBuffer.wrap(a))
            setByteBuffer("csd-1", ByteBuffer.wrap(b))
        }
        trackIndex = muxer?.addTrack(format) ?: return
        muxer?.start()
        started = true
        waitingForKeyFrame = true
    }

    private fun finalizeOutput() {
        runCatching { if (started) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { outputPfd?.close() }
        outputPfd = null
        val uri = outputUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            runCatching {
                app.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
        }
        onFinished(uri, outputName)
    }
}

interface CameraGateBroadcastPacketSink {
    fun offer(packet: CameraGateBroadcastPacket)
}
