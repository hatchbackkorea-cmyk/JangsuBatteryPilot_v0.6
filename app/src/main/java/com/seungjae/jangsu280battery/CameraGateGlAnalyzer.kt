package com.seungjae.jangsu280battery

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * High-speed Camera Gate bridge.
 *
 * A SurfaceTexture-only Camera2 high-speed target is treated as a preview surface on some Samsung
 * devices and is therefore delivered near 30 FPS even when the requested range is 120-120. This
 * class instead returns a real MediaCodec recording surface to Camera2. The encoded stream is
 * decoded immediately to a private SurfaceTexture, then OpenGL reads only a tiny centre strip for
 * motion analysis. Presentation timestamps survive the encode/decode bridge, so trigger timing
 * still uses the camera stream timestamp rather than decoder arrival time.
 *
 * The decoded texture is also copied to the app TextureView at a reduced cadence for human preview.
 * This keeps the timing/analyser path independent from the phone display refresh rate.
 */
class CameraGateGlAnalyzer(
    private val previewTexture: SurfaceTexture,
    private val handler: Handler,
    private val sensorOrientation: Int,
    private val onFrame: (timestampNs: Long, samples: IntArray) -> Unit
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var analysisEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewNativeSurface: Surface? = null

    private var decodedTextureId = 0
    private var decodedTexture: SurfaceTexture? = null
    private var decoderOutputSurface: Surface? = null
    private var cameraSurface: Surface? = null

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var codecMime = ""
    private val decoderInputs = ArrayDeque<Int>()
    private val encodedFrames = ArrayDeque<EncodedFrame>()
    private val encodedTimesUs = ArrayDeque<Long>()

    @Volatile var streamFps: Double = 0.0
        private set

    private var program = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexMatrix = -1
    private var frameCounter = 0
    private var previewEvery = 2
    private var released = false

    private data class EncodedFrame(
        val bytes: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    private val positions = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
    )
    private val previewCoords by lazy { floatBuffer(textureCoords(0f, 1f)) }
    private val stripCoords by lazy { floatBuffer(textureCoords(0.485f, 0.515f)) }
    private val transform = FloatArray(16)
    private val pixels = ByteBuffer.allocateDirect(ANALYSIS_W * ANALYSIS_H * 4).order(ByteOrder.nativeOrder())

    fun start(size: Size, targetFps: Int): Surface {
        check(Looper.myLooper() == handler.looper) { "GL analyzer must start on camera thread" }
        releaseInternal()
        released = false
        previewEvery = if (targetFps >= 100) 2 else 1
        streamFps = 0.0
        encodedTimesUs.clear()

        initEgl()
        makeCurrent(analysisEglSurface)
        initProgram()
        initDecodedTexture(size)

        cameraSurface = startCodecBridge(size, targetFps)
        frameCounter = 0
        return cameraSurface ?: error("고속 기록 Surface 생성 실패")
    }

    fun release() {
        if (Looper.myLooper() == handler.looper) {
            releaseInternal()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseInternal()
            } finally {
                latch.countDown()
            }
        }
        latch.await(900, TimeUnit.MILLISECONDS)
    }

    private fun initDecodedTexture(size: Size) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        decodedTextureId = tex[0]
        check(decodedTextureId != 0) { "GL texture 생성 실패" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decodedTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(decodedTextureId)
        st.setDefaultBufferSize(size.width, size.height)
        st.setOnFrameAvailableListener({ consumeDecodedFrame() }, handler)
        decodedTexture = st
        decoderOutputSurface = Surface(st)
    }

    private fun startCodecBridge(size: Size, targetFps: Int): Surface {
        var last: Throwable? = null
        for (mime in listOf("video/avc", "video/hevc")) {
            try {
                codecMime = mime
                return startEncoder(mime, size, targetFps)
            } catch (e: Throwable) {
                last = e
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
                encoder = null
                runCatching { cameraSurface?.release() }
                cameraSurface = null
            }
        }
        throw IllegalStateException("120 FPS 하드웨어 인코더 준비 실패: ${last?.message ?: "지원 코덱 없음"}")
    }

    private fun startEncoder(mime: String, size: Size, targetFps: Int): Surface {
        val c = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(size, targetFps))
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, targetFps.toFloat())
        }
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = c.createInputSurface()
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        trackEncodedFps(info.presentationTimeUs)
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            encodedFrames.addLast(EncodedFrame(bytes, info.presentationTimeUs, info.flags))
                            while (encodedFrames.size > MAX_PENDING_FRAMES) encodedFrames.removeFirst()
                        }
                    }
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
                pumpDecoder()
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                // Activity FPS counters will make a stalled codec visible without crashing timing.
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (!released) configureDecoder(format)
            }
        }, handler)
        c.start()
        encoder = c
        cameraSurface = input
        return input
    }

    private fun configureDecoder(encodedFormat: MediaFormat) {
        if (decoder != null || released) return
        val output = decoderOutputSurface ?: return
        val d = MediaCodec.createDecoderByType(codecMime)
        d.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (released) return
                decoderInputs.addLast(index)
                pumpDecoder()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                // Keep the camera session alive; zero decoded FPS clearly exposes the failure.
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        d.configure(encodedFormat, output, null, 0)
        d.start()
        decoder = d
        pumpDecoder()
    }

    private fun pumpDecoder() {
        val d = decoder ?: return
        while (!released && decoderInputs.isNotEmpty() && encodedFrames.isNotEmpty()) {
            val inputIndex = decoderInputs.removeFirst()
            val frame = encodedFrames.removeFirst()
            val input = d.getInputBuffer(inputIndex)
            if (input == null || input.capacity() < frame.bytes.size) {
                runCatching { d.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0) }
                continue
            }
            input.clear()
            input.put(frame.bytes)
            runCatching {
                d.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, frame.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private fun consumeDecodedFrame() {
        if (released) return
        val st = decodedTexture ?: return
        try {
            makeCurrent(analysisEglSurface)
            st.updateTexImage()
            st.getTransformMatrix(transform)
            val timestamp = st.timestamp

            draw(stripCoords, analysisEglSurface, ANALYSIS_W, ANALYSIS_H)
            pixels.position(0)
            GLES20.glReadPixels(0, 0, ANALYSIS_W, ANALYSIS_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val samples = IntArray(ANALYSIS_W * ANALYSIS_H)
            pixels.position(0)
            var p = 0
            while (p < samples.size) {
                val r = pixels.get().toInt() and 0xff
                val g = pixels.get().toInt() and 0xff
                val b = pixels.get().toInt() and 0xff
                pixels.get()
                samples[p++] = (77 * r + 150 * g + 29 * b) shr 8
            }
            onFrame(timestamp, samples)

            frameCounter++
            if (frameCounter % previewEvery == 0 && previewEglSurface != EGL14.EGL_NO_SURFACE) {
                val w = IntArray(1)
                val h = IntArray(1)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, w, 0)
                EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, h, 0)
                draw(previewCoords, previewEglSurface, w[0].coerceAtLeast(1), h[0].coerceAtLeast(1))
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
            }
        } catch (_: Throwable) {
            // A dropped decode/GL frame must never kill the timing thread.
        }
    }

    private fun trackEncodedFps(ptsUs: Long) {
        if (ptsUs <= 0L) return
        if (encodedTimesUs.isNotEmpty() && ptsUs <= encodedTimesUs.last()) return
        encodedTimesUs.addLast(ptsUs)
        while (encodedTimesUs.size > 360) encodedTimesUs.removeFirst()
        if (encodedTimesUs.size >= 2) {
            val spanUs = encodedTimesUs.last() - encodedTimesUs.first()
            if (spanUs > 0L) streamFps = (encodedTimesUs.size - 1) * 1_000_000.0 / spanUs
        }
    }

    private fun draw(coords: FloatBuffer, target: EGLSurface, width: Int, height: Int) {
        makeCurrent(target)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        positions.position(0)
        coords.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decodedTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig failed" }
        eglConfig = configs[0]
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, ANALYSIS_W, EGL14.EGL_HEIGHT, ANALYSIS_H, EGL14.EGL_NONE)
        analysisEglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttrs, 0)
        check(analysisEglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        previewNativeSurface = Surface(previewTexture)
        previewEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            previewNativeSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
    }

    private fun initProgram() {
        val vertex = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "GL link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "GL compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun textureCoords(left: Float, right: Float): FloatArray {
        fun map(u: Float, v: Float): Pair<Float, Float> = when ((sensorOrientation % 360 + 360) % 360) {
            90 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            270 -> v to (1f - u)
            else -> u to v
        }
        val bl = map(left, 0f)
        val br = map(right, 0f)
        val tl = map(left, 1f)
        val tr = map(right, 1f)
        return floatArrayOf(bl.first, bl.second, br.first, br.second, tl.first, tl.second, tr.first, tr.second)
    }

    private fun bitrateFor(size: Size, fps: Int): Int {
        val pixelsPerSecond = size.width.toLong() * size.height.toLong() * fps.toLong()
        return (pixelsPerSecond / 16L).coerceIn(8_000_000L, 28_000_000L).toInt()
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private fun releaseInternal() {
        if (released) return
        released = true

        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        decoderInputs.clear()
        encodedFrames.clear()
        encodedTimesUs.clear()
        streamFps = 0.0

        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { cameraSurface?.release() }
        cameraSurface = null

        runCatching { decodedTexture?.setOnFrameAvailableListener(null) }
        runCatching { decoderOutputSurface?.release() }
        runCatching { decodedTexture?.release() }
        decoderOutputSurface = null
        decodedTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, previewEglSurface) }
            if (analysisEglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, analysisEglSurface) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        runCatching { previewNativeSurface?.release() }
        previewNativeSurface = null
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        analysisEglSurface = EGL14.EGL_NO_SURFACE
        previewEglSurface = EGL14.EGL_NO_SURFACE
        eglConfig = null
        program = 0
        decodedTextureId = 0
        codecMime = ""
    }

    companion object {
        private const val ANALYSIS_W = 8
        private const val ANALYSIS_H = 160
        private const val MAX_PENDING_FRAMES = 16
    }
}
