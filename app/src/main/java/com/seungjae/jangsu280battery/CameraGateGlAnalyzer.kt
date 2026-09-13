package com.seungjae.jangsu280battery

import android.graphics.SurfaceTexture
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 high-speed helper that keeps camera consumption off the display-vsync path.
 *
 * The camera writes to a private SurfaceTexture owned by this class. Every camera frame wakes the
 * camera thread, updateTexImage() is called immediately, a narrow centre strip is rendered into a
 * tiny pbuffer and read back for motion analysis. The same texture is rendered to the TextureView
 * at a reduced rate only for human preview, so a 60 Hz display does not cap the analysis stream.
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

    private var cameraTextureId = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var program = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexMatrix = -1
    private var frameCounter = 0
    private var previewEvery = 2
    private var released = false

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
        initEgl()
        // GLES objects can only be created after an EGL context is current.
        makeCurrent(analysisEglSurface)
        initProgram()

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(cameraTextureId)
        st.setDefaultBufferSize(size.width, size.height)
        st.setOnFrameAvailableListener({ consumeFrame() }, handler)
        cameraTexture = st
        cameraSurface = Surface(st)
        frameCounter = 0
        return cameraSurface!!
    }

    fun release() {
        if (Looper.myLooper() == handler.looper) {
            releaseInternal()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try { releaseInternal() } finally { latch.countDown() }
        }
        latch.await(700, TimeUnit.MILLISECONDS)
    }

    private fun consumeFrame() {
        if (released) return
        val st = cameraTexture ?: return
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
            // A dropped GL frame must never kill the camera timing thread.
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
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
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
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }
        val bl = map(left, 0f)
        val br = map(right, 0f)
        val tl = map(left, 1f)
        val tr = map(right, 1f)
        return floatArrayOf(bl.first, bl.second, br.first, br.second, tl.first, tl.second, tr.first, tr.second)
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private fun releaseInternal() {
        if (released) return
        released = true
        runCatching { cameraTexture?.setOnFrameAvailableListener(null) }
        runCatching { cameraSurface?.release() }
        runCatching { cameraTexture?.release() }
        cameraSurface = null
        cameraTexture = null
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
        cameraTextureId = 0
    }

    companion object {
        private const val ANALYSIS_W = 8
        private const val ANALYSIS_H = 160
    }
}
