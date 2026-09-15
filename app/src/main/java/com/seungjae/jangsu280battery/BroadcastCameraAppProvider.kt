package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Turns a broadcast-only CAM1..CAM12 phone into a camera-app-like field screen.
 *
 * - Uses a normal Camera2 session instead of the timing-only constrained high-speed session.
 * - Keeps the existing 720p24 broadcast encoder.
 * - Adds pinch zoom and 1x/2x/3x shortcuts.
 * - Captures real Camera2 JPEG stills at a selectable sensor resolution/quality.
 * - Records the already-encoded 720p24 broadcast stream locally without a second video encoder.
 * - Leaves START/CP/FINISH timing phones completely untouched.
 */
class BroadcastCameraAppProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<Activity, CamState>()
    private val jobs = WeakHashMap<Activity, Runnable>()
    private val photoIo = Executors.newSingleThreadExecutor()

    private data class CamState(
        var requestedRegular: Boolean = false,
        var configuredAnalyzerId: Int = 0,
        var configuring: Boolean = false,
        var imageReader: ImageReader? = null,
        var session: CameraCaptureSession? = null,
        var repeatBuilder: CaptureRequest.Builder? = null,
        var activeArray: Rect? = null,
        var maxZoom: Float = 1f,
        var zoom: Float = 1f,
        var photoSizes: List<Size> = emptyList(),
        var photoSize: Size? = null,
        var recorder: BroadcastLocalRecorder? = null,
        var scaleDetector: ScaleGestureDetector? = null,
        var controls: View? = null,
        var recText: TextView? = null,
        var qualityText: TextView? = null,
        var zoomText: TextView? = null,
    )

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity || !isBroadcast(activity)) return
        val state = states.getOrPut(activity) { CamState() }
        installUi(activity, state)
        stopJob(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    stopJob(activity)
                    return
                }
                if (isBroadcast(activity)) {
                    installUi(activity, state)
                    ensureBroadcastCameraSession(activity, state)
                }
                main.postDelayed(this, 260L)
            }
        }
        jobs[activity] = job
        main.postDelayed(job, 500L)
    }

    override fun onActivityPaused(activity: Activity) {
        stopJob(activity)
        stopRecording(activity)
        closeStateSession(states[activity], closeSession = false)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stopJob(activity)
        stopRecording(activity)
        states.remove(activity)?.let { closeStateSession(it, closeSession = false) }
    }

    private fun stopJob(activity: Activity) {
        jobs.remove(activity)?.let(main::removeCallbacks)
    }

    private fun isBroadcast(context: Context): Boolean =
        TimingOperatorStore.current(context)?.role?.let(TimingOperatorStore::isBroadcastRole) == true

    private fun installUi(activity: CameraGateHighSpeedActivity, state: CamState) {
        val texture = readField(activity, "textureView") as? View ?: return
        val cameraBox = texture.parent as? FrameLayout ?: return
        var controls = cameraBox.findViewWithTag<View>(TAG_CAMERA_APP_UI)
        if (controls == null) {
            controls = createCameraControls(activity, state)
            cameraBox.addView(
                controls,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            state.controls = controls
        }
        controls.visibility = View.VISIBLE
        controls.bringToFront()
        cameraBox.findViewWithTag<View>(TAG_ROLE_LABEL)?.bringToFront()

        if (state.scaleDetector == null) {
            val detector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoom(activity, state, state.zoom * detector.scaleFactor)
                    return true
                }
            })
            state.scaleDetector = detector
            texture.setOnTouchListener { _, event: MotionEvent ->
                detector.onTouchEvent(event)
                true
            }
        }
        refreshUi(state)
    }

    private fun createCameraControls(activity: CameraGateHighSpeedActivity, state: CamState): FrameLayout {
        val overlay = FrameLayout(activity).apply {
            tag = TAG_CAMERA_APP_UI
            isClickable = false
            isFocusable = false
        }

        val live = pill(activity, "LIVE · 720p24").apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        overlay.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(activity, 12)
        })

        val exit = pill(activity, "나가기").apply {
            isClickable = true
            setOnClickListener { activity.finish() }
        }
        overlay.addView(exit, FrameLayout.LayoutParams(dp(activity, 86), dp(activity, 38), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(activity, 10)
            rightMargin = dp(activity, 12)
        })

        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 7), dp(activity, 16), dp(activity, 10))
            background = GradientDrawable().apply {
                setColor(Color.argb(118, 0, 0, 0))
                cornerRadius = dp(activity, 20).toFloat()
            }
        }

        val zoomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(1f, 2f, 3f).forEach { z ->
            zoomRow.addView(pill(activity, if (z == 1f) "1x" else "${z.toInt()}x").apply {
                tag = "${TAG_ZOOM_PREFIX}${z.toInt()}"
                isClickable = true
                setOnClickListener { setZoom(activity, state, z) }
            }, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 34)).apply {
                leftMargin = dp(activity, 3)
                rightMargin = dp(activity, 3)
            })
        }
        state.zoomText = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
        }
        zoomRow.addView(state.zoomText, LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 34)))
        bottom.addView(zoomRow)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val rec = pill(activity, "● REC").apply {
            setTextColor(Color.rgb(255, 90, 90))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            isClickable = true
            setOnClickListener { toggleRecording(activity, state) }
        }
        state.recText = rec
        actions.addView(rec, LinearLayout.LayoutParams(0, dp(activity, 52), 1f))

        val shutter = TextView(activity).apply {
            text = "●"
            textSize = 56f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { takePhoto(activity, state) }
        }
        actions.addView(shutter, LinearLayout.LayoutParams(dp(activity, 92), dp(activity, 72)))

        val quality = pill(activity, "사진화질").apply {
            isClickable = true
            setOnClickListener {
                cycleQuality(activity)
                reconfigureForQuality(activity, state)
            }
        }
        state.qualityText = quality
        actions.addView(quality, LinearLayout.LayoutParams(0, dp(activity, 52), 1f))

        bottom.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(bottom, FrameLayout.LayoutParams(dp(activity, 440), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(activity, 12)
        })
        return overlay
    }

    private fun pill(activity: Activity, value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
        background = GradientDrawable().apply {
            setColor(Color.argb(150, 18, 18, 20))
            cornerRadius = dp(activity, 18).toFloat()
            setStroke(dp(activity, 1), Color.argb(80, 255, 255, 255))
        }
    }

    private fun ensureBroadcastCameraSession(activity: CameraGateHighSpeedActivity, state: CamState) {
        if (state.configuring) return
        val camera = readField(activity, "cameraDevice") as? CameraDevice ?: return
        val analyzer = readField(activity, "glAnalyzer") ?: return
        val output = readField(activity, "cameraOutputSurface") as? Surface ?: return
        val sessionLabel = readField(activity, "sessionLabel") as? String ?: ""
        val highSpeed = readBooleanField(activity, "highSpeedActive")

        if (highSpeed && !state.requestedRegular) {
            state.requestedRegular = true
            invokeFallbackToRegular(activity, camera)
            return
        }
        if (sessionLabel.contains("고속세션 연결 중")) return
        if (!sessionLabel.contains("일반") && state.requestedRegular) return

        val analyzerId = System.identityHashCode(analyzer)
        if (state.configuredAnalyzerId == analyzerId && state.session != null && state.imageReader != null) return

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { manager.getCameraCharacteristics(camera.id) }.getOrNull() ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (jpegSizes.isEmpty()) return

        state.photoSizes = jpegSizes
        state.activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        state.maxZoom = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
        state.zoom = state.zoom.coerceIn(1f, state.maxZoom)
        val size = choosePhotoSize(activity, jpegSizes)
        state.photoSize = size

        closeStateSession(state, closeSession = true)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        state.imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            } finally {
                image.close()
            }
            savePhoto(activity.applicationContext, bytes, size)
        }, readField(activity, "cameraHandler") as? Handler)

        val handler = readField(activity, "cameraHandler") as? Handler ?: return
        state.configuring = true
        runCatching { (readField(activity, "captureSession") as? CameraCaptureSession)?.close() }
        camera.createCaptureSession(
            listOf(output, reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (activity.isFinishing || activity.isDestroyed || !isBroadcast(activity)) {
                        runCatching { session.close() }
                        state.configuring = false
                        return
                    }
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(output)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        chooseCamRange(chars)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        applyCrop(this, state)
                    }
                    runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
                        .onSuccess {
                            state.session = session
                            state.repeatBuilder = builder
                            state.configuredAnalyzerId = analyzerId
                            state.configuring = false
                            writeField(activity, "captureSession", session)
                            writeBooleanField(activity, "highSpeedActive", false)
                            main.post { refreshUi(state) }
                        }
                        .onFailure {
                            state.configuring = false
                            runCatching { session.close() }
                        }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    state.configuring = false
                    runCatching { session.close() }
                }
            },
            handler
        )
    }

    private fun chooseCamRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        ranges.firstOrNull { it.lower <= 30 && it.upper >= 30 }?.let { return it }
        return ranges.minByOrNull { kotlin.math.abs(it.upper - 30) + kotlin.math.abs(it.lower - 30) }
    }

    private fun invokeFallbackToRegular(activity: CameraGateHighSpeedActivity, camera: CameraDevice) {
        runCatching {
            activity.javaClass.getDeclaredMethod("fallbackToRegular", CameraDevice::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(activity, camera, "CAM 전용 일반 카메라 모드")
        }
    }

    private fun setZoom(activity: CameraGateHighSpeedActivity, state: CamState, requested: Float) {
        val next = requested.coerceIn(1f, state.maxZoom.coerceAtLeast(1f))
        state.zoom = next
        val handler = readField(activity, "cameraHandler") as? Handler
        handler?.post {
            val builder = state.repeatBuilder ?: return@post
            val session = state.session ?: return@post
            applyCrop(builder, state)
            runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
        }
        refreshUi(state)
    }

    private fun applyCrop(builder: CaptureRequest.Builder, state: CamState) {
        val active = state.activeArray ?: return
        val zoom = state.zoom.coerceAtLeast(1f)
        val w = (active.width() / zoom).roundToInt().coerceAtLeast(2)
        val h = (active.height() / zoom).roundToInt().coerceAtLeast(2)
        val cx = active.centerX()
        val cy = active.centerY()
        val crop = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
    }

    private fun takePhoto(activity: CameraGateHighSpeedActivity, state: CamState) {
        val camera = readField(activity, "cameraDevice") as? CameraDevice ?: return
        val session = state.session ?: run {
            Toast.makeText(activity, "카메라 준비 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val reader = state.imageReader ?: return
        val handler = readField(activity, "cameraHandler") as? Handler ?: return
        val quality = qualityLevel(activity)
        val sensorOrientation = readIntField(activity, "sensorOrientation")
        handler.post {
            runCatching {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_QUALITY, when (quality) {
                        QUALITY_STANDARD -> 88.toByte()
                        QUALITY_HIGH -> 95.toByte()
                        else -> 100.toByte()
                    })
                    set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                    applyCrop(this, state)
                }.build()
                session.capture(request, null, handler)
            }.onFailure {
                main.post { Toast.makeText(activity, "사진 촬영에 실패했습니다.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun savePhoto(context: Context, bytes: ByteArray, size: Size) {
        photoIo.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val name = "TimeGate_${stamp}_${size.width}x${size.height}.jpg"
            var ok = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TimeGate")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ok = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        context.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }, null, null)
                    }.isSuccess
                }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "TimeGate").apply { mkdirs() }
                ok = runCatching { FileOutputStream(File(dir, name)).use { it.write(bytes) } }.isSuccess
            }
            main.post {
                Toast.makeText(context, if (ok) "사진 저장 완료 · $name" else "사진 저장 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleRecording(activity: CameraGateHighSpeedActivity, state: CamState) {
        if (state.recorder != null) {
            stopRecording(activity)
            refreshUi(state)
            return
        }
        val recorder = BroadcastLocalRecorder(activity.applicationContext) { _, name ->
            main.post {
                Toast.makeText(activity.applicationContext, "동영상 저장 완료 · ${name ?: "TimeGate"}", Toast.LENGTH_LONG).show()
            }
        }
        state.recorder = recorder
        CameraGateBroadcastBridge.bindLocalSink(recorder)
        refreshUi(state)
    }

    private fun stopRecording(activity: Activity) {
        val state = states[activity] ?: return
        val recorder = state.recorder ?: return
        CameraGateBroadcastBridge.bindLocalSink(null)
        state.recorder = null
        recorder.stop()
        refreshUi(state)
    }

    private fun reconfigureForQuality(activity: CameraGateHighSpeedActivity, state: CamState) {
        state.configuredAnalyzerId = 0
        closeStateSession(state, closeSession = true)
        main.postDelayed({ ensureBroadcastCameraSession(activity, state) }, 120L)
        refreshUi(state)
    }

    private fun cycleQuality(context: Context) {
        val next = when (qualityLevel(context)) {
            QUALITY_STANDARD -> QUALITY_HIGH
            QUALITY_HIGH -> QUALITY_MAX
            else -> QUALITY_STANDARD
        }
        prefs(context).edit().putInt(KEY_QUALITY, next).apply()
    }

    private fun qualityLevel(context: Context): Int = prefs(context).getInt(KEY_QUALITY, QUALITY_HIGH)

    private fun choosePhotoSize(context: Context, sizes: List<Size>): Size {
        val quality = qualityLevel(context)
        if (quality == QUALITY_MAX) return sizes.first()
        val targetMp = if (quality == QUALITY_STANDARD) 8_000_000L else 12_500_000L
        return sizes.filter { it.width.toLong() * it.height.toLong() <= targetMp }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.last()
    }

    private fun refreshUi(state: CamState) {
        state.zoomText?.text = String.format(Locale.US, "%.1fx", state.zoom)
        state.recText?.apply {
            text = if (state.recorder != null) "■ STOP" else "● REC"
            setTextColor(if (state.recorder != null) Color.rgb(255, 70, 70) else Color.rgb(255, 110, 110))
        }
        val size = state.photoSize
        val mp = if (size != null) size.width.toLong() * size.height.toLong() / 1_000_000.0 else 0.0
        state.qualityText?.text = when (qualityLevel(state.qualityText?.context ?: return)) {
            QUALITY_STANDARD -> if (size != null) "표준\n${"%.1f".format(Locale.US, mp)}MP" else "표준"
            QUALITY_HIGH -> if (size != null) "고화질\n${"%.1f".format(Locale.US, mp)}MP" else "고화질"
            else -> if (size != null) "최고\n${"%.1f".format(Locale.US, mp)}MP" else "최고"
        }
    }

    private fun closeStateSession(state: CamState?, closeSession: Boolean) {
        if (state == null) return
        if (closeSession) runCatching { state.session?.close() }
        state.session = null
        state.repeatBuilder = null
        runCatching { state.imageReader?.close() }
        state.imageReader = null
        state.configuredAnalyzerId = 0
        state.configuring = false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun readBooleanField(target: Any, name: String): Boolean = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(target)
    }.getOrDefault(false)

    private fun readIntField(target: Any, name: String): Int = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(target)
    }.getOrDefault(0)

    private fun writeField(target: Any, name: String, value: Any?) {
        runCatching { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
    }

    private fun writeBooleanField(target: Any, name: String, value: Boolean) {
        runCatching { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setBoolean(target, value) }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val TAG_CAMERA_APP_UI = "broadcast_camera_app_controls_v1"
        private const val TAG_ROLE_LABEL = "camera_gate_actual_role_label_v1"
        private const val TAG_ZOOM_PREFIX = "broadcast_camera_zoom_"
        private const val PREFS = "broadcast_camera_app"
        private const val KEY_QUALITY = "photo_quality"
        private const val QUALITY_STANDARD = 0
        private const val QUALITY_HIGH = 1
        private const val QUALITY_MAX = 2
    }
}
