package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera Gate beta. GPS and official race records are deliberately untouched.
 * The first field test measures camera trigger repeatability, actual FPS and phone/server clock error.
 */
class CameraGateTestActivity : Activity() {
    private lateinit var textureView: TextureView
    private lateinit var overlay: GateOverlay
    private lateinit var stateText: TextView
    private lateinit var phoneClockText: TextView
    private lateinit var serverClockText: TextView
    private lateinit var syncText: TextView
    private lateinit var fpsText: TextView
    private lateinit var scoreText: TextView
    private lateinit var thresholdText: TextView
    private lateinit var triggerText: TextView
    private lateinit var logText: TextView
    private lateinit var armButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private val syncHttp = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var activeCameraId: String = ""
    private var sensorOrientation: Int = 0
    private var analysisSize = Size(1280, 720)
    private var requestedFpsRange: Range<Int>? = null

    @Volatile private var armed = false
    @Volatile private var threshold = 18.0
    @Volatile private var actualFps = 0.0
    @Volatile private var lastScore = 0.0
    @Volatile private var requestedFpsLabel = "확인 중"
    @Volatile private var lastFrameSource = "-"
    @Volatile private var clockOffsetMs = 0.0
    @Volatile private var clockUncertaintyMs = Double.POSITIVE_INFINITY
    @Volatile private var syncRunning = false

    private var previousSamples: IntArray? = null
    private var lastTriggerElapsedMs = 0L
    private var cameraStartedElapsedMs = 0L
    private val frameTimes = ArrayDeque<Long>()
    private val triggerLog = ArrayDeque<String>()
    private var triggerCount = 0

    private val clockTicker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            phoneClockText.text = "폰 내부 시간  ${formatClock(now)}"
            serverClockText.text = if (clockUncertaintyMs.isFinite()) {
                "서버 보정 시간  ${formatClock((now + clockOffsetMs).toLong())}"
            } else "서버 보정 시간  동기화 필요"
            ui.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraThread = HandlerThread("CameraGateCamera").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        buildUi()
        ui.post(clockTicker)
        syncClock()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        closeCamera()
        syncExecutor.shutdownNow()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable) openCamera()
            } else {
                stateText.text = "카메라 권한이 필요합니다."
                Toast.makeText(this, "카메라 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        top.addView(Button(this).apply {
            text = "←"
            textSize = 22f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(60), dp(48)))
        top.addView(TextView(this).apply {
            text = "CAMERA GATE BETA · START 트리거"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(top)

        val cameraBox = FrameLayout(this).apply { setBackgroundColor(Color.rgb(18, 18, 18)) }
        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            }
        }
        overlay = GateOverlay(this)
        cameraBox.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        cameraBox.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(cameraBox, LinearLayout.LayoutParams(-1, 0, 0.48f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }
        armButton = Button(this).apply {
            text = "계측 대기 ARM"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                armed = !armed
                previousSamples = null
                lastTriggerElapsedMs = 0L
                text = if (armed) "■ 계측 중지" else "계측 대기 ARM"
                stateText.text = if (armed) "ARMED · 손이나 물체를 빨간 START 선을 가로질러 휙 지나가세요." else "대기 · ARM을 누르면 트리거를 검사합니다."
                overlay.armed = armed
                overlay.invalidate()
            }
        }
        controls.addView(armButton, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(5) })
        controls.addView(Button(this).apply {
            text = "시간 재동기화"
            setOnClickListener { syncClock() }
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(5) })
        root.addView(controls)

        val scroll = ScrollView(this)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll.addView(info)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 0.52f))

        stateText = metric(info, "카메라 준비 중…", 14f, Color.LTGRAY, true)
        phoneClockText = metric(info, "폰 내부 시간  -", 18f, Color.WHITE, true)
        serverClockText = metric(info, "서버 보정 시간  -", 18f, Color.rgb(120, 210, 255), true)
        syncText = metric(info, "시간 동기화 · 시작 중…", 14f, Color.LTGRAY, false)
        fpsText = metric(info, "카메라 FPS · 확인 중", 14f, Color.LTGRAY, false)
        scoreText = metric(info, "모션 점수 · 0.0", 14f, Color.LTGRAY, false)

        val sensitivity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thresholdText = TextView(this).apply {
            text = "감지 임계값 · ${"%.1f".format(Locale.US, threshold)}"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        sensitivity.addView(thresholdText, LinearLayout.LayoutParams(0, dp(48), 1f))
        sensitivity.addView(Button(this).apply {
            text = "더 민감"
            setOnClickListener {
                threshold = max(6.0, threshold - 2.0)
                thresholdText.text = "감지 임계값 · ${"%.1f".format(Locale.US, threshold)}"
            }
        }, LinearLayout.LayoutParams(dp(96), dp(44)).apply { marginEnd = dp(4) })
        sensitivity.addView(Button(this).apply {
            text = "덜 민감"
            setOnClickListener {
                threshold = min(50.0, threshold + 2.0)
                thresholdText.text = "감지 임계값 · ${"%.1f".format(Locale.US, threshold)}"
            }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        info.addView(sensitivity)

        triggerText = metric(info, "TRIGGER RESULT · 아직 없음", 17f, Color.rgb(255, 205, 70), true)
        logText = metric(info, "[이벤트 로그]\n-", 12f, Color.LTGRAY, false)
        metric(info, "이 베타판은 GPS와 공식 경기 기록을 건드리지 않습니다. 실제 분석 FPS, 모션 트리거, 폰↔서버 시간 오프셋만 검증합니다. 화면은 테스트 중 켜짐 유지됩니다.", 11f, Color.GRAY, false)
    }

    private fun metric(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun openCamera() {
        if (cameraDevice != null || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        stateText.text = "카메라 연결 중…"
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val selected = runCatching { selectBackCamera(manager) }.getOrElse {
            stateText.text = "후면 카메라 확인 실패 · ${it.message ?: it.javaClass.simpleName}"
            return
        }
        activeCameraId = selected.first
        analysisSize = selected.second
        requestedFpsRange = selected.third
        sensorOrientation = selected.fourth
        requestedFpsLabel = requestedFpsRange?.let { "요청 ${it.lower}-${it.upper} FPS" } ?: "자동 FPS"
        try {
            manager.openCamera(activeCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    ui.post { stateText.text = "카메라 연결 끊김" }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    ui.post { stateText.text = "카메라 오류 · $error" }
                }
            }, cameraHandler)
        } catch (e: Throwable) {
            stateText.text = "카메라 열기 실패 · ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private data class CameraChoice(val id: String, val size: Size, val fps: Range<Int>?, val sensorOrientation: Int)

    private fun selectBackCamera(manager: CameraManager): CameraChoice {
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val exact = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            val usable = sizes.filter { it.width <= 1280 && it.height <= 720 }
            val size = exact ?: usable.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() } ?: continue
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            val fps = ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
                ?: ranges.filter { it.upper >= 60 }.minByOrNull { (it.upper - it.lower) * 100 + abs(it.upper - 60) }
                ?: ranges.maxByOrNull { it.upper }
            return CameraChoice(id, size, fps, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
        }
        error("사용 가능한 후면 카메라가 없습니다.")
    }

    private fun createSession(camera: CameraDevice) {
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(analysisSize.width, analysisSize.height)
        val surface = Surface(st)
        previewSurface = surface
        val reader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            analyzeImage(image)
        }, cameraHandler)

        try {
            camera.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        requestedFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    try {
                        session.setRepeatingRequest(request, null, cameraHandler)
                        cameraStartedElapsedMs = SystemClock.elapsedRealtime()
                        previousSamples = null
                        ui.post { stateText.text = "카메라 정상 · ARM을 눌러 테스트하세요." }
                    } catch (e: Throwable) {
                        ui.post { stateText.text = "카메라 반복촬영 실패 · ${e.message ?: e.javaClass.simpleName}" }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    ui.post { stateText.text = "카메라 세션 구성 실패" }
                }
            }, cameraHandler)
        } catch (e: Throwable) {
            stateText.text = "카메라 세션 시작 실패 · ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun closeCamera() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { previewSurface?.release() }
        captureSession = null
        cameraDevice = null
        imageReader = null
        previewSurface = null
        previousSamples = null
        frameTimes.clear()
        actualFps = 0.0
    }

    private fun analyzeImage(image: Image) {
        try {
            trackFps(image.timestamp)
            val samples = sampleGateStrip(image)
            val previous = previousSamples
            val score = if (previous != null && previous.size == samples.size && samples.isNotEmpty()) {
                var sum = 0L
                for (i in samples.indices) sum += abs(samples[i] - previous[i])
                sum.toDouble() / samples.size.toDouble()
            } else 0.0
            previousSamples = samples
            lastScore = score

            val nowElapsed = SystemClock.elapsedRealtime()
            if (armed && nowElapsed - cameraStartedElapsedMs >= 900L && score >= threshold && nowElapsed - lastTriggerElapsedMs >= 650L) {
                lastTriggerElapsedMs = nowElapsed
                val receiveWall = System.currentTimeMillis()
                val receiveMonoNs = SystemClock.elapsedRealtimeNanos()
                val frameAgeNs = receiveMonoNs - image.timestamp
                val localMs = if (frameAgeNs in 0L..2_000_000_000L) {
                    lastFrameSource = "카메라 프레임 타임스탬프"
                    receiveWall - frameAgeNs / 1_000_000L
                } else {
                    lastFrameSource = "분석 수신 시각(프레임 시각 보정 불가)"
                    receiveWall
                }
                onTrigger(localMs, score)
            }
            ui.post {
                fpsText.text = "카메라 FPS · 실제 ${"%.1f".format(Locale.US, actualFps)} · $requestedFpsLabel · ${analysisSize.width}×${analysisSize.height}"
                scoreText.text = "모션 점수 · ${"%.1f".format(Locale.US, lastScore)} · 임계 ${"%.1f".format(Locale.US, threshold)}"
            }
        } catch (_: Throwable) {
            // A malformed frame must never kill the timing loop.
        } finally {
            image.close()
        }
    }

    private fun sampleGateStrip(image: Image): IntArray {
        val plane = image.planes.firstOrNull() ?: return IntArray(0)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val horizontalInBuffer = sensorOrientation == 90 || sensorOrientation == 270
        val values = ArrayList<Int>(2500)
        if (horizontalInBuffer) {
            val cy = image.height / 2
            val y0 = max(0, cy - 3)
            val y1 = min(image.height - 1, cy + 3)
            var x = 0
            while (x < image.width) {
                for (y in y0..y1) {
                    val index = y * rowStride + x * pixelStride
                    if (index in 0 until buffer.limit()) values += (buffer.get(index).toInt() and 0xff)
                }
                x += 4
            }
        } else {
            val cx = image.width / 2
            val x0 = max(0, cx - 3)
            val x1 = min(image.width - 1, cx + 3)
            var y = 0
            while (y < image.height) {
                for (x in x0..x1) {
                    val index = y * rowStride + x * pixelStride
                    if (index in 0 until buffer.limit()) values += (buffer.get(index).toInt() and 0xff)
                }
                y += 4
            }
        }
        return IntArray(values.size) { values[it] }
    }

    private fun trackFps(timestampNs: Long) {
        if (timestampNs <= 0L) return
        frameTimes.addLast(timestampNs)
        while (frameTimes.size > 45) frameTimes.removeFirst()
        if (frameTimes.size >= 2) {
            val span = frameTimes.last() - frameTimes.first()
            if (span > 0L) actualFps = (frameTimes.size - 1) * 1_000_000_000.0 / span.toDouble()
        }
    }

    private fun onTrigger(localMs: Long, score: Double) {
        val corrected = if (clockUncertaintyMs.isFinite()) (localMs + clockOffsetMs).toLong() else localMs
        val offsetLabel = if (clockUncertaintyMs.isFinite()) signedMs(clockOffsetMs) else "미동기화"
        val uncertainty = if (clockUncertaintyMs.isFinite()) "±${"%.0f".format(Locale.US, clockUncertaintyMs)} ms" else "-"
        triggerCount += 1
        val line = "#$triggerCount  local ${formatClock(localMs)} → server ${formatClock(corrected)} · score ${"%.1f".format(Locale.US, score)} · $uncertainty"
        synchronized(triggerLog) {
            triggerLog.addFirst(line)
            while (triggerLog.size > 20) triggerLog.removeLast()
        }
        ui.post {
            triggerText.text = "TRIGGER #$triggerCount\n폰 ${formatClock(localMs)}\n서버보정 ${formatClock(corrected)}\n폰↔서버 $offsetLabel · 동기화추정 $uncertainty\n$lastFrameSource"
            triggerText.setTextColor(Color.rgb(100, 255, 140))
            logText.text = synchronized(triggerLog) { "[이벤트 로그]\n" + triggerLog.joinToString("\n") }
            overlay.flash()
            vibrateTrigger()
        }
    }

    private fun syncClock() {
        if (syncRunning) return
        syncRunning = true
        syncText.text = "시간 동기화 · 서버 왕복 샘플 수집 중…"
        syncText.setTextColor(Color.LTGRAY)
        syncExecutor.execute {
            val result = runCatching { measureClockOffset() }
            ui.post {
                syncRunning = false
                result.onSuccess { s ->
                    clockOffsetMs = s.offsetMs
                    clockUncertaintyMs = s.uncertaintyMs
                    val color = when {
                        s.uncertaintyMs <= 15.0 -> Color.rgb(100, 255, 140)
                        s.uncertaintyMs <= 50.0 -> Color.rgb(255, 210, 80)
                        else -> Color.rgb(255, 145, 70)
                    }
                    syncText.setTextColor(color)
                    syncText.text = "시간 동기화 · ${s.source}\n오프셋 ${signedMs(s.offsetMs)} · 추정오차 ±${"%.0f".format(Locale.US, s.uncertaintyMs)} ms · 최저 RTT ${s.rttMs} ms\n서버 ${RaceServerClient(this).baseUrl()}"
                }.onFailure { e ->
                    clockUncertaintyMs = Double.POSITIVE_INFINITY
                    syncText.setTextColor(Color.rgb(255, 95, 95))
                    syncText.text = "시간 동기화 실패 · ${e.message ?: e.javaClass.simpleName}\n서버 ${RaceServerClient(this).baseUrl()}"
                }
            }
        }
    }

    private data class ClockResult(val offsetMs: Double, val uncertaintyMs: Double, val rttMs: Long, val source: String)
    private data class PreciseSample(val offsetMs: Double, val rttMs: Long)

    private fun measureClockOffset(): ClockResult {
        val base = RaceServerClient(this).baseUrl().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "RACE 서버 주소가 없습니다." }
        val preciseFirst = preciseClockSample(base)
        if (preciseFirst != null) {
            val samples = mutableListOf(preciseFirst)
            repeat(6) { preciseClockSample(base)?.let(samples::add) }
            val best = samples.minByOrNull { it.rttMs } ?: preciseFirst
            return ClockResult(best.offsetMs, max(1.0, best.rttMs / 2.0), best.rttMs, "정밀 서버시각 API")
        }

        var lower = Long.MIN_VALUE / 4
        var upper = Long.MAX_VALUE / 4
        var bestRtt = Long.MAX_VALUE
        val centers = mutableListOf<Double>()
        repeat(28) { i ->
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url("$base/api/race/events?clock_probe=${System.nanoTime()}_$i")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Cache-Control", "no-cache")
                .build()
            syncHttp.newCall(req).execute().use { response ->
                val t1 = System.currentTimeMillis()
                val rtt = (t1 - t0).coerceAtLeast(0L)
                bestRtt = min(bestRtt, rtt)
                val date = response.header("Date") ?: error("서버 응답에 Date 헤더가 없습니다.")
                val d = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                val lo = d - t1
                val hi = d + 999L - t0
                lower = max(lower, lo)
                upper = min(upper, hi)
                centers += (lo + hi) / 2.0
            }
            Thread.sleep(55L)
        }
        if (lower <= upper) {
            val offset = (lower + upper) / 2.0
            val uncertainty = max((upper - lower) / 2.0, bestRtt.coerceAtLeast(0L) / 2.0)
            return ClockResult(offset, uncertainty, bestRtt.coerceAtMost(9_999L), "HTTP Date 교차추정")
        }
        require(centers.isNotEmpty()) { "서버 시간 샘플을 얻지 못했습니다." }
        val sorted = centers.sorted()
        return ClockResult(sorted[sorted.size / 2], 500.0 + bestRtt.coerceAtLeast(0L) / 2.0, bestRtt.coerceAtMost(9_999L), "HTTP Date 근사")
    }

    private fun preciseClockSample(base: String): PreciseSample? {
        return try {
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url("$base/api/race/clock?probe=${System.nanoTime()}")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Cache-Control", "no-cache")
                .build()
            syncHttp.newCall(req).execute().use { response ->
                val t1 = System.currentTimeMillis()
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                var serverMs: Long? = null
                for (key in listOf("server_time_ms", "server_ms", "now_ms", "time_ms", "timestamp_ms")) {
                    if (json.has(key)) {
                        val candidate = json.optLong(key)
                        if (candidate > 1_000_000_000_000L) {
                            serverMs = candidate
                            break
                        }
                    }
                }
                val value = serverMs ?: return null
                PreciseSample(value - (t0 + t1) / 2.0, (t1 - t0).coerceAtLeast(0L))
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun vibrateTrigger() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(45L, 120))
        }
    }

    private fun signedMs(value: Double): String = String.format(Locale.US, "%+.1f ms", value)

    private fun formatClock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND), cal.get(java.util.Calendar.MILLISECOND))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class GateOverlay(context: Context) : View(context) {
        var armed: Boolean = false
        private var flashUntil = 0L
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val flash = SystemClock.elapsedRealtime() < flashUntil
            val color = when {
                flash -> Color.rgb(80, 255, 120)
                armed -> Color.rgb(255, 55, 55)
                else -> Color.rgb(255, 170, 40)
            }
            linePaint.color = color
            textPaint.color = color
            val x = width / 2f
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
            canvas.drawText(if (armed) "START LINE · ARMED" else "START LINE", 22f, 48f, textPaint)
        }
        fun flash() {
            flashUntil = SystemClock.elapsedRealtime() + 220L
            invalidate()
            postDelayed({ invalidate() }, 240L)
        }
    }

    companion object {
        private const val REQ_CAMERA = 4701
    }
}
