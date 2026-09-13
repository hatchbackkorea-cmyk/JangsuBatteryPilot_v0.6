package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Camera Gate field-test v5: true high-speed stream analysis + validated frame PTS timing. */
class CameraGateHighSpeedActivity : Activity() {
    private lateinit var textureView: TextureView
    private lateinit var overlay: GateOverlay
    private lateinit var stateText: TextView
    private lateinit var phoneClockText: TextView
    private lateinit var correctedClockText: TextView
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
    private var cameraOutputSurface: Surface? = null
    private var glAnalyzer: CameraGateGlAnalyzer? = null

    private var cameraId = ""
    private var sensorOrientation = 0
    private var timestampRealtime = false
    private var regularSize = Size(1280, 720)
    private var regularRange: Range<Int>? = null
    private var highSpeedSize: Size? = null
    private var highSpeedRange: Range<Int>? = null
    private var supportLabel = ""
    private var highSpeedFallbackStarted = false
    private var fallbackReason = ""

    @Volatile private var armed = false
    @Volatile private var threshold = 18.0
    @Volatile private var metadataFps = 0.0
    @Volatile private var analysisFps = 0.0
    @Volatile private var lastScore = 0.0
    @Volatile private var highSpeedActive = false
    @Volatile private var sessionLabel = "카메라 준비 중"
    @Volatile private var lastFrameSource = "-"
    @Volatile private var clockOffsetMs = 0.0
    @Volatile private var clockUncertaintyMs = Double.POSITIVE_INFINITY
    @Volatile private var syncRunning = false

    private var frozenClockMs: Long? = null
    private var previousSamples: IntArray? = null
    private var lastTriggerElapsedMs = 0L
    private var cameraStartedElapsedMs = 0L
    private var lastMetricUiElapsedMs = 0L
    private val metadataFrameTimes = ArrayDeque<Long>()
    private val analysisFrameTimes = ArrayDeque<Long>()
    private val triggerLog = ArrayDeque<String>()
    private val frameClock = CameraGateFrameClock()
    private var triggerCount = 0

    private val clockTicker = object : Runnable {
        override fun run() {
            renderClock(frozenClockMs ?: System.currentTimeMillis())
            ui.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraThread = HandlerThread("CameraGateHighSpeedV5").apply { start() }
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
            text = "CAMERA GATE BETA v5 · DIRECT 120"
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
            setOnClickListener { toggleArm() }
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
        correctedClockText = metric(info, "기준 보정 시간  -", 18f, Color.rgb(120, 210, 255), true)
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
        metric(
            info,
            "120 FPS 녹화스트림을 화면 주사율과 분리해 직접 분석하고, 프레임 PTS가 BOOTTIME과 실제로 정렬되는지 런타임 검증한 뒤 계측 시각으로 사용합니다. GPS와 공식 경기 기록은 건드리지 않습니다.",
            11f,
            Color.GRAY,
            false
        )
    }

    private fun toggleArm() {
        armed = !armed
        previousSamples = null
        lastTriggerElapsedMs = 0L
        if (armed) {
            frozenClockMs = null
            armButton.text = "■ 계측 중지"
            stateText.text = "ARMED · $sessionLabel · 빨간 START 선을 통과시키세요."
        } else {
            val stoppedAt = System.currentTimeMillis()
            frozenClockMs = stoppedAt
            renderClock(stoppedAt)
            armButton.text = "계측 대기 ARM"
            stateText.text = "STOPPED · 계측 종료 시각 고정 · 다시 ARM하면 재개합니다."
        }
        overlay.armed = armed
        overlay.invalidate()
    }

    private fun renderClock(now: Long) {
        phoneClockText.text = "폰 내부 시간  ${formatClock(now)}"
        correctedClockText.text = if (clockUncertaintyMs.isFinite()) {
            "기준 보정 시간  ${formatClock((now + clockOffsetMs).toLong())}"
        } else "기준 보정 시간  동기화 필요"
    }

    private fun metric(parent: LinearLayout, value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    private data class CameraChoice(
        val id: String,
        val regularSize: Size,
        val regularRange: Range<Int>?,
        val highSpeedSize: Size?,
        val highSpeedRange: Range<Int>?,
        val sensorOrientation: Int,
        val timestampRealtime: Boolean,
        val supportLabel: String
    )

    private fun openCamera() {
        if (cameraDevice != null || !textureView.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        stateText.text = "카메라 연결 중…"
        highSpeedFallbackStarted = false
        fallbackReason = ""

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val selected = runCatching { selectBackCamera(manager) }.getOrElse {
            stateText.text = "후면 카메라 확인 실패 · ${it.message ?: it.javaClass.simpleName}"
            return
        }
        cameraId = selected.id
        regularSize = selected.regularSize
        regularRange = selected.regularRange
        highSpeedSize = selected.highSpeedSize
        highSpeedRange = selected.highSpeedRange
        sensorOrientation = selected.sensorOrientation
        timestampRealtime = selected.timestampRealtime
        supportLabel = selected.supportLabel
        frameClock.reset(timestampRealtime)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val hsSize = highSpeedSize
                    val hsRange = highSpeedRange
                    if (hsSize != null && hsRange != null) createHighSpeedSession(camera, hsSize, hsRange)
                    else createRegularSession(camera)
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

    private fun selectBackCamera(manager: CameraManager): CameraChoice {
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

            val previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
            val exactRegular = previewSizes.firstOrNull { it.width == 1280 && it.height == 720 }
            val regularUsable = previewSizes.filter { it.width <= 1280 && it.height <= 720 }
            val regSize = exactRegular
                ?: regularUsable.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: previewSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
                ?: continue

            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            val regRange = chooseStableRange(ranges)

            val highPairs = runCatching {
                map.highSpeedVideoSizes.flatMap { size ->
                    map.getHighSpeedVideoFpsRangesFor(size).map { range -> size to range }
                }
            }.getOrDefault(emptyList())
            val exact120 = highPairs.filter { (_, r) -> r.lower == 120 && r.upper == 120 }
            val target = exact120.firstOrNull { (s, _) -> s.width == 1920 && s.height == 1080 }
                ?: exact120.firstOrNull { (s, _) -> s.width == 1280 && s.height == 720 }
                ?: exact120.minByOrNull { (s, _) -> s.width.toLong() * s.height.toLong() }

            val support = if (highPairs.isEmpty()) {
                "고속지원 없음"
            } else {
                val entries = highPairs
                    .sortedWith(compareBy<Pair<Size, Range<Int>>> { it.first.width * it.first.height }.thenBy { it.second.upper })
                    .map { (s, r) -> "${s.width}×${s.height}:${r.lower}-${r.upper}" }
                    .distinct()
                "고속지원 ${entries.takeLast(8).joinToString(", ")}"
            }

            val tsRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

            return CameraChoice(
                id = id,
                regularSize = regSize,
                regularRange = regRange,
                highSpeedSize = target?.first,
                highSpeedRange = target?.second,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                timestampRealtime = tsRealtime,
                supportLabel = support
            )
        }
        error("사용 가능한 후면 카메라가 없습니다.")
    }

    private fun chooseStableRange(ranges: List<Range<Int>>): Range<Int>? {
        ranges.firstOrNull { it.lower == 60 && it.upper == 60 }?.let { return it }
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        return ranges.filter { it.upper >= 30 }
            .sortedWith(compareByDescending<Range<Int>> { it.lower }.thenBy { abs(it.upper - 60) })
            .firstOrNull()
            ?: ranges.maxByOrNull { it.upper * 100 + it.lower }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                trackMetadataFps(ts)
                frameClock.observeCapture(ts, SystemClock.elapsedRealtimeNanos())
            }
        }
    }

    private fun createAnalyzerSurface(size: Size, targetFps: Int): Surface {
        val previewSt = textureView.surfaceTexture ?: error("미리보기 SurfaceTexture가 없습니다.")
        glAnalyzer?.release()
        glAnalyzer = CameraGateGlAnalyzer(previewSt, cameraHandler, sensorOrientation) { timestampNs, samples ->
            analyzeDirectFrame(timestampNs, samples)
        }
        return glAnalyzer!!.start(size, targetFps).also { cameraOutputSurface = it }
    }

    private fun resetFpsCounters() {
        metadataFrameTimes.clear()
        analysisFrameTimes.clear()
        metadataFps = 0.0
        analysisFps = 0.0
    }

    private fun createHighSpeedSession(camera: CameraDevice, size: Size, fps: Range<Int>) {
        val surface = try {
            createAnalyzerSurface(size, fps.upper)
        } catch (e: Throwable) {
            fallbackToRegular(camera, "GL/코덱 분석 Surface 준비 실패 · ${e.message ?: e.javaClass.simpleName}")
            return
        }
        highSpeedActive = false
        resetFpsCounters()
        sessionLabel = "DIRECT 120 FPS 고속세션 연결 중"
        ui.post { stateText.text = sessionLabel }

        try {
            camera.createConstrainedHighSpeedCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        val hs = session as? CameraConstrainedHighSpeedCaptureSession
                        if (hs == null) {
                            fallbackToRegular(camera, "고속 세션 형식 불일치")
                            return
                        }
                        captureSession = hs
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        }.build()
                        try {
                            val burst = hs.createHighSpeedRequestList(request)
                            hs.setRepeatingBurst(burst, captureCallback, cameraHandler)
                            highSpeedActive = true
                            cameraStartedElapsedMs = SystemClock.elapsedRealtime()
                            previousSamples = null
                            sessionLabel = "DIRECT 고속 ${fps.lower}-${fps.upper} FPS · ${size.width}×${size.height}"
                            ui.post { stateText.text = "$sessionLabel 정상 · ARM을 눌러 테스트하세요." }
                        } catch (e: Throwable) {
                            fallbackToRegular(camera, "120 FPS 시작 실패 · ${e.message ?: e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        fallbackToRegular(camera, "120 FPS 세션 구성 실패")
                    }
                },
                cameraHandler
            )
        } catch (e: Throwable) {
            fallbackToRegular(camera, "120 FPS 세션 거부 · ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fallbackToRegular(camera: CameraDevice, reason: String) {
        if (highSpeedFallbackStarted || cameraDevice == null) return
        highSpeedFallbackStarted = true
        fallbackReason = reason
        highSpeedActive = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { glAnalyzer?.release() }
        glAnalyzer = null
        cameraOutputSurface = null
        ui.post { stateText.text = "$reason\n60 FPS 일반세션으로 자동 전환 중…" }
        cameraHandler.postDelayed({
            if (cameraDevice != null) createRegularSession(camera)
        }, 200L)
    }

    private fun createRegularSession(camera: CameraDevice) {
        val targetFps = regularRange?.upper ?: 60
        val surface = try {
            createAnalyzerSurface(regularSize, targetFps)
        } catch (e: Throwable) {
            ui.post { stateText.text = "GL/코덱 분석 Surface 준비 실패 · ${e.message ?: e.javaClass.simpleName}" }
            return
        }
        highSpeedActive = false
        resetFpsCounters()

        try {
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        regularRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    try {
                        session.setRepeatingRequest(request, captureCallback, cameraHandler)
                        cameraStartedElapsedMs = SystemClock.elapsedRealtime()
                        previousSamples = null
                        val r = regularRange
                        val base = if (r != null) {
                            "DIRECT 일반 ${r.lower}-${r.upper} FPS · ${regularSize.width}×${regularSize.height}"
                        } else "DIRECT 일반 자동 FPS"
                        sessionLabel = if (fallbackReason.isBlank()) base else "$base · 폴백: $fallbackReason"
                        ui.post { stateText.text = "$sessionLabel 정상 · ARM을 눌러 테스트하세요." }
                    } catch (e: Throwable) {
                        ui.post { stateText.text = "일반 카메라 시작 실패 · ${e.message ?: e.javaClass.simpleName}" }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    ui.post { stateText.text = "일반 카메라 세션 구성 실패" }
                }
            }, cameraHandler)
        } catch (e: Throwable) {
            ui.post { stateText.text = "일반 카메라 세션 시작 실패 · ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    private fun closeCamera() {
        highSpeedActive = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        captureSession = null
        cameraDevice = null
        runCatching { glAnalyzer?.release() }
        glAnalyzer = null
        cameraOutputSurface = null
        previousSamples = null
        resetFpsCounters()
    }

    private fun analyzeDirectFrame(timestampNs: Long, samples: IntArray) {
        if (cameraDevice == null || timestampNs <= 0L || samples.isEmpty()) return
        trackAnalysisFps(timestampNs)

        val previous = previousSamples
        val score = if (previous != null && previous.size == samples.size) {
            var sum = 0L
            for (i in samples.indices) sum += abs(samples[i] - previous[i])
            sum.toDouble() / samples.size
        } else 0.0
        previousSamples = samples
        lastScore = score

        val nowElapsed = SystemClock.elapsedRealtime()
        val receiveWall = System.currentTimeMillis()
        val receiveMonoNs = SystemClock.elapsedRealtimeNanos()
        val resolved = frameClock.resolve(timestampNs, receiveMonoNs, receiveWall)

        if (armed && nowElapsed - cameraStartedElapsedMs >= 900L && score >= threshold && nowElapsed - lastTriggerElapsedMs >= 650L) {
            lastTriggerElapsedMs = nowElapsed
            lastFrameSource = resolved.sourceLabel
            onTrigger(resolved.localWallMs, score)
        }

        if (nowElapsed - lastMetricUiElapsedMs >= 150L) {
            lastMetricUiElapsedMs = nowElapsed
            val mode = if (highSpeedActive) "DIRECT 고속세션" else "DIRECT 일반세션"
            val streamFps = glAnalyzer?.streamFps ?: 0.0
            ui.post {
                fpsText.text = "$mode\n메타데이터 ${"%.1f".format(Locale.US, metadataFps)} FPS · 녹화스트림 ${"%.1f".format(Locale.US, streamFps)} FPS · 직접분석 ${"%.1f".format(Locale.US, analysisFps)} FPS\n$sessionLabel\n프레임시각 · ${frameClock.statusLabel}\n$supportLabel"
                scoreText.text = "모션 점수 · ${"%.1f".format(Locale.US, lastScore)} · 임계 ${"%.1f".format(Locale.US, threshold)}"
            }
        }
    }

    private fun trackMetadataFps(timestampNs: Long) {
        if (timestampNs <= 0L) return
        metadataFrameTimes.addLast(timestampNs)
        while (metadataFrameTimes.size > 180) metadataFrameTimes.removeFirst()
        if (metadataFrameTimes.size >= 2) {
            val span = metadataFrameTimes.last() - metadataFrameTimes.first()
            if (span > 0L) metadataFps = (metadataFrameTimes.size - 1) * 1_000_000_000.0 / span
        }
    }

    private fun trackAnalysisFps(timestampNs: Long) {
        analysisFrameTimes.addLast(timestampNs)
        while (analysisFrameTimes.size > 240) analysisFrameTimes.removeFirst()
        if (analysisFrameTimes.size >= 2) {
            val span = analysisFrameTimes.last() - analysisFrameTimes.first()
            if (span > 0L) analysisFps = (analysisFrameTimes.size - 1) * 1_000_000_000.0 / span
        }
    }

    private fun onTrigger(localMs: Long, score: Double) {
        val corrected = if (clockUncertaintyMs.isFinite()) (localMs + clockOffsetMs).toLong() else localMs
        val uncertainty = if (clockUncertaintyMs.isFinite()) "±${"%.0f".format(Locale.US, clockUncertaintyMs)} ms" else "-"
        triggerCount += 1
        val line = "#$triggerCount local ${formatClock(localMs)} → corrected ${formatClock(corrected)} · score ${"%.1f".format(Locale.US, score)} · $uncertainty"
        synchronized(triggerLog) {
            triggerLog.addFirst(line)
            while (triggerLog.size > 20) triggerLog.removeLast()
        }
        ui.post {
            triggerText.text = "TRIGGER #$triggerCount\n폰 ${formatClock(localMs)}\n기준보정 ${formatClock(corrected)}\n오프셋 ${if (clockUncertaintyMs.isFinite()) signedMs(clockOffsetMs) else "미동기화"} · 동기화추정 $uncertainty\n$lastFrameSource"
            triggerText.setTextColor(Color.rgb(100, 255, 140))
            logText.text = synchronized(triggerLog) { "[이벤트 로그]\n" + triggerLog.joinToString("\n") }
            overlay.flash()
            vibrateTrigger()
        }
    }

    private data class ClockResult(val offsetMs: Double, val uncertaintyMs: Double, val rttMs: Long, val source: String)
    private data class Sample(val offsetMs: Double, val rttMs: Long)

    private fun syncClock() {
        if (syncRunning) return
        syncRunning = true
        syncText.text = "시간 동기화 · 정밀 샘플 수집 중…"
        syncText.setTextColor(Color.LTGRAY)
        syncExecutor.execute {
            val result = runCatching { measureClockOffset() }
            ui.post {
                syncRunning = false
                result.onSuccess { s ->
                    clockOffsetMs = s.offsetMs
                    clockUncertaintyMs = s.uncertaintyMs
                    renderClock(frozenClockMs ?: System.currentTimeMillis())
                    syncText.setTextColor(
                        when {
                            s.uncertaintyMs <= 15.0 -> Color.rgb(100, 255, 140)
                            s.uncertaintyMs <= 50.0 -> Color.rgb(255, 210, 80)
                            else -> Color.rgb(255, 145, 70)
                        }
                    )
                    syncText.text = "시간 동기화 · ${s.source}\n오프셋 ${signedMs(s.offsetMs)} · 추정오차 ±${"%.0f".format(Locale.US, s.uncertaintyMs)} ms · 최저 RTT ${s.rttMs} ms\nRACE 서버 ${RaceServerClient(this).baseUrl()}"
                }.onFailure { e ->
                    clockUncertaintyMs = Double.POSITIVE_INFINITY
                    renderClock(frozenClockMs ?: System.currentTimeMillis())
                    syncText.setTextColor(Color.rgb(255, 95, 95))
                    syncText.text = "시간 동기화 실패 · ${e.message ?: e.javaClass.simpleName}\n서버 ${RaceServerClient(this).baseUrl()}"
                }
            }
        }
    }

    private fun measureClockOffset(): ClockResult {
        val base = RaceServerClient(this).baseUrl().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "RACE 서버 주소가 없습니다." }

        val precise = mutableListOf<Sample>()
        repeat(10) {
            preciseClockSample(base)?.let(precise::add)
            Thread.sleep(25L)
        }
        if (precise.isNotEmpty()) return combineBest(precise, "정밀 RACE 서버시각 API")

        for (host in listOf("time.google.com", "time.cloudflare.com")) {
            val ntp = mutableListOf<Sample>()
            repeat(4) { ntpSample(host)?.let(ntp::add) }
            if (ntp.isNotEmpty()) return combineBest(ntp, "NTP 기준시각 · $host")
        }

        return httpDateFallback(base)
    }

    private fun combineBest(samples: List<Sample>, source: String): ClockResult {
        val best = samples.sortedBy { it.rttMs }.take(min(5, samples.size))
        val offsets = best.map { it.offsetMs }.sorted()
        val offset = offsets[offsets.size / 2]
        val spread = (offsets.last() - offsets.first()) / 2.0
        val minRtt = best.minOf { it.rttMs }
        val uncertainty = max(1.0, max(spread, minRtt / 2.0))
        return ClockResult(offset, uncertainty, minRtt, source)
    }

    private fun preciseClockSample(base: String): Sample? = try {
        val t0Wall = System.currentTimeMillis()
        val t0Mono = SystemClock.elapsedRealtimeNanos()
        val req = Request.Builder()
            .url("$base/api/race/clock?probe=${System.nanoTime()}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache")
            .build()
        syncHttp.newCall(req).execute().use { response ->
            val t1Mono = SystemClock.elapsedRealtimeNanos()
            val t1Wall = t0Wall + (t1Mono - t0Mono) / 1_000_000.0
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val keys = listOf("server_time_ms", "server_ms", "serverTimeMs", "epochMs", "now_ms", "nowMs", "time_ms", "timeMs", "timestamp_ms")
            val serverMs = keys.firstNotNullOfOrNull { key ->
                if (!json.has(key)) null else json.optLong(key).takeIf { it > 1_000_000_000_000L }
            } ?: return null
            val rtt = ((t1Mono - t0Mono) / 1_000_000L).coerceAtLeast(0L)
            Sample(serverMs - (t0Wall + t1Wall) / 2.0, rtt)
        }
    } catch (_: Throwable) {
        null
    }

    private fun ntpSample(host: String): Sample? {
        val packet = ByteArray(48)
        packet[0] = 0x23
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = 1300
            val address = InetAddress.getByName(host)
            val t1 = System.currentTimeMillis().toDouble()
            writeNtpTimestamp(packet, 40, t1)
            socket.send(DatagramPacket(packet, packet.size, address, 123))
            val response = DatagramPacket(packet, packet.size)
            socket.receive(response)
            val t4 = System.currentTimeMillis().toDouble()
            val t2 = readNtpTimestamp(packet, 32)
            val t3 = readNtpTimestamp(packet, 40)
            if (t2 <= 0.0 || t3 <= 0.0) return null
            val delay = ((t4 - t1) - (t3 - t2)).coerceAtLeast(0.0)
            val offset = ((t2 - t1) + (t3 - t4)) / 2.0
            Sample(offset, delay.toLong())
        } catch (_: Throwable) {
            null
        } finally {
            socket.close()
        }
    }

    private fun writeNtpTimestamp(bytes: ByteArray, offset: Int, unixMs: Double) {
        val ntpSeconds = unixMs / 1000.0 + NTP_EPOCH_OFFSET_SECONDS
        val seconds = ntpSeconds.toLong()
        val fraction = ((ntpSeconds - seconds) * 4294967296.0).toLong()
        for (i in 0..3) bytes[offset + i] = (seconds shr (24 - i * 8)).toByte()
        for (i in 0..3) bytes[offset + 4 + i] = (fraction shr (24 - i * 8)).toByte()
    }

    private fun readNtpTimestamp(bytes: ByteArray, offset: Int): Double {
        var seconds = 0L
        var fraction = 0L
        for (i in 0..3) seconds = (seconds shl 8) or (bytes[offset + i].toLong() and 0xff)
        for (i in 0..3) fraction = (fraction shl 8) or (bytes[offset + 4 + i].toLong() and 0xff)
        return ((seconds - NTP_EPOCH_OFFSET_SECONDS) * 1000.0) + (fraction * 1000.0 / 4294967296.0)
    }

    private fun httpDateFallback(base: String): ClockResult {
        var lower = Long.MIN_VALUE / 4
        var upper = Long.MAX_VALUE / 4
        var bestRtt = Long.MAX_VALUE
        val centers = mutableListOf<Double>()
        repeat(16) { i ->
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
            Thread.sleep(40L)
        }
        if (lower <= upper) {
            val offset = (lower + upper) / 2.0
            val uncertainty = max((upper - lower) / 2.0, bestRtt.coerceAtLeast(0L) / 2.0)
            return ClockResult(offset, uncertainty, bestRtt.coerceAtMost(9_999L), "HTTP Date 참고용 · 정밀계측 불가")
        }
        require(centers.isNotEmpty()) { "서버 시간 샘플을 얻지 못했습니다." }
        val sorted = centers.sorted()
        return ClockResult(
            sorted[sorted.size / 2],
            500.0 + bestRtt.coerceAtLeast(0L) / 2.0,
            bestRtt.coerceAtMost(9_999L),
            "HTTP Date 근사 · 정밀계측 불가"
        )
    }

    private fun vibrateTrigger() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(45L, 120)) }
    }

    private fun signedMs(value: Double): String = String.format(Locale.US, "%+.1f ms", value)

    private fun formatClock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND)
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class GateOverlay(context: Context) : View(context) {
        var armed = false
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
        private const val REQ_CAMERA = 4705
        private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L
    }
}
