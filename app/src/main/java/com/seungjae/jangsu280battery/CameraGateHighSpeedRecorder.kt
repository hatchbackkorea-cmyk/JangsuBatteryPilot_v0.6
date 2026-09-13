package com.seungjae.jangsu280battery

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.view.Surface
import java.util.ArrayDeque

/**
 * Creates a real MediaCodec recording surface for constrained high-speed Camera2 sessions.
 *
 * Some vendor camera HALs throttle a SurfaceTexture-only high-speed session to the preview cadence
 * (typically 30 FPS), even when CONTROL_AE_TARGET_FPS_RANGE is fixed at 120-120. Supplying a real
 * encoder surface lets Camera2 classify the second target as the high-rate recording stream.
 * Encoded buffers are immediately discarded; presentation timestamps are kept only to measure the
 * actual recording-stream FPS without writing a video file.
 */
class CameraGateHighSpeedRecorder(private val handler: Handler) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val outputTimesUs = ArrayDeque<Long>()

    @Volatile var fps: Double = 0.0
        private set
    @Volatile var lastError: String = ""
        private set

    fun start(width: Int, height: Int, targetFps: Int): Surface {
        release()
        fps = 0.0
        lastError = ""
        outputTimesUs.clear()

        val encoder = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, targetFps))
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setFloat(MediaFormat.KEY_OPERATING_RATE, targetFps.toFloat())
            setFloat(MediaFormat.KEY_CAPTURE_RATE, targetFps.toFloat())
        }

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0 && info.presentationTimeUs > 0L &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        track(info.presentationTimeUs)
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                lastError = "${e.diagnosticInfo ?: e.message ?: "MediaCodec error"}"
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        encoder.start()

        codec = encoder
        inputSurface = surface
        return surface
    }

    fun release() {
        val c = codec
        codec = null
        runCatching { c?.stop() }
        runCatching { c?.release() }
        runCatching { inputSurface?.release() }
        inputSurface = null
        outputTimesUs.clear()
        fps = 0.0
    }

    private fun track(ptsUs: Long) {
        if (outputTimesUs.isNotEmpty() && ptsUs <= outputTimesUs.last()) return
        outputTimesUs.addLast(ptsUs)
        while (outputTimesUs.size > 240) outputTimesUs.removeFirst()
        if (outputTimesUs.size >= 2) {
            val spanUs = outputTimesUs.last() - outputTimesUs.first()
            if (spanUs > 0L) fps = (outputTimesUs.size - 1) * 1_000_000.0 / spanUs
        }
    }

    private fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
        return (pixelsPerSecond / 18L).coerceIn(6_000_000L, 24_000_000L).toInt()
    }

    companion object {
        private const val MIME = "video/avc"
    }
}
