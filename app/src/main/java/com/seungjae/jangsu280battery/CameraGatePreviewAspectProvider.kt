package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Camera Gate preview/orientation/direction guard.
 *
 * - Keeps the camera preview FIT_CENTER: no crop and no stretch.
 * - Lets the gate activity follow the physical phone orientation (portrait/landscape).
 * - Adds a visible race direction selector. Default is RIGHT -> LEFT.
 * - Uses the displayed preview only to classify travel direction. The existing high-speed analyzer
 *   remains authoritative for the crossing timestamp. Reverse/unknown passes are removed from the
 *   TRIGGER text before the timing-relay watcher sees them, so they are not sent as race records.
 */
class CameraGatePreviewAspectProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, MutableList<Runnable>>()
    private val samplerJobs = WeakHashMap<Activity, Runnable>()
    private val motionStates = WeakHashMap<Activity, MotionState>()
    private val triggerWatchers = WeakHashMap<Activity, TextWatcher>()

    private data class MotionState(
        var bitmap: Bitmap? = null,
        var pixels: IntArray = IntArray(SAMPLE_W * SAMPLE_H),
        var previousGray: IntArray? = null,
        var approachSide: Int = SIDE_NONE,
        var approachAtMs: Long = 0L,
        var lastDirection: Int = DIR_UNKNOWN,
        var directionAtMs: Long = 0L,
        var lastMotionAtMs: Long = 0L,
        var mutatingTriggerText: Boolean = false,
        var lastStatus: String = "진행방향 감지 대기"
    )

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return

        // The manifest historically locked Camera Gate to portrait. FULL_SENSOR makes the whole
        // timing screen rotate with the phone, which naturally keeps the gate line top-to-bottom
        // on the rotated screen. This also works when the system auto-rotate switch is locked.
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        cancelScheduled(activity)
        installDirectionUi(activity)
        attachTriggerGuard(activity)
        startDirectionSampler(activity)

        val delays = longArrayOf(0L, 80L, 220L, 500L, 1_000L, 1_800L)
        val list = mutableListOf<Runnable>()
        delays.forEach { delay ->
            val job = Runnable {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    applyFit(activity)
                    installDirectionUi(activity)
                    attachTriggerGuard(activity)
                }
            }
            list += job
            main.postDelayed(job, delay)
        }
        jobs[activity] = list
    }

    override fun onActivityPaused(activity: Activity) {
        cancelScheduled(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        cancelScheduled(activity)
        val watcher = triggerWatchers.remove(activity)
        if (watcher != null && activity is CameraGateHighSpeedActivity) {
            (readField(activity, "triggerText") as? TextView)?.removeTextChangedListener(watcher)
        }
        motionStates.remove(activity)?.bitmap?.recycle()
    }

    private fun cancelScheduled(activity: Activity) {
        jobs.remove(activity)?.forEach { main.removeCallbacks(it) }
        samplerJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun applyFit(activity: CameraGateHighSpeedActivity) {
        val texture = readField(activity, "textureView") as? TextureView ?: return
        val overlay = readField(activity, "overlay") as? View ?: return
        val parent = texture.parent as? FrameLayout ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return

        val regular = readField(activity, "regularSize") as? Size ?: Size(1280, 720)
        val highSpeed = readField(activity, "highSpeedSize") as? Size
        val source = highSpeed ?: regular
        val sensorOrientation = readIntField(activity, "sensorOrientation")
        val displayDegrees = when (activity.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relative = ((sensorOrientation - displayDegrees) % 360 + 360) % 360
        val rotated = relative == 90 || relative == 270
        val displayW = if (rotated) source.height else source.width
        val displayH = if (rotated) source.width else source.height
        if (displayW <= 0 || displayH <= 0) return

        val aspect = displayW.toDouble() / displayH.toDouble()
        val parentAspect = parentW.toDouble() / parentH.toDouble()
        val fitW: Int
        val fitH: Int
        if (parentAspect > aspect) {
            fitH = parentH
            fitW = (fitH * aspect).roundToInt().coerceAtLeast(1)
        } else {
            fitW = parentW
            fitH = (fitW / aspect).roundToInt().coerceAtLeast(1)
        }

        setCenteredSize(texture, fitW, fitH)
        setCenteredSize(overlay, fitW, fitH)

        val analyzer = readField(activity, "glAnalyzer")
        if (analyzer != null) {
            writeIntField(analyzer, "previewWidth", fitW)
            writeIntField(analyzer, "previewHeight", fitH)
        }
    }

    private fun installDirectionUi(activity: CameraGateHighSpeedActivity) {
        if (findTagged(activity.window.decorView, TAG_DIRECTION_ROW) != null) {
            refreshDirectionUi(activity)
            return
        }

        val arm = readField(activity, "armButton") as? Button ?: return
        val controls = arm.parent as? LinearLayout ?: return
        val root = controls.parent as? LinearLayout ?: return
        val controlsIndex = root.indexOfChild(controls)
        if (controlsIndex < 0) return

        val row = LinearLayout(activity).apply {
            tag = TAG_DIRECTION_ROW
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 2), dp(activity, 10), dp(activity, 6))
        }
        val status = TextView(activity).apply {
            tag = TAG_DIRECTION_STATUS
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(activity, 2), 0, dp(activity, 4))
        }
        row.addView(status, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rtl = Button(activity).apply {
            tag = TAG_RTL_BUTTON
            isAllCaps = false
            text = "← 우→좌"
            setOnClickListener { setDirection(activity, DIR_RTL) }
        }
        val ltr = Button(activity).apply {
            tag = TAG_LTR_BUTTON
            isAllCaps = false
            text = "좌→우 →"
            setOnClickListener { setDirection(activity, DIR_LTR) }
        }
        buttons.addView(rtl, LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply { marginEnd = dp(activity, 4) })
        buttons.addView(ltr, LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply { marginStart = dp(activity, 4) })
        row.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        root.addView(row, (controlsIndex + 1).coerceAtMost(root.childCount))
        refreshDirectionUi(activity)
    }

    private fun setDirection(activity: CameraGateHighSpeedActivity, direction: Int) {
        prefs(activity).edit().putInt(KEY_DIRECTION, direction).apply()
        motionStates.getOrPut(activity) { MotionState() }.apply {
            approachSide = SIDE_NONE
            approachAtMs = 0L
            lastDirection = DIR_UNKNOWN
            directionAtMs = 0L
            lastStatus = "진행방향 감지 대기"
        }
        refreshDirectionUi(activity)
    }

    private fun refreshDirectionUi(activity: CameraGateHighSpeedActivity) {
        val selected = selectedDirection(activity)
        val status = findTagged(activity.window.decorView, TAG_DIRECTION_STATUS) as? TextView
        val rtl = findTagged(activity.window.decorView, TAG_RTL_BUTTON) as? Button
        val ltr = findTagged(activity.window.decorView, TAG_LTR_BUTTON) as? Button
        val state = motionStates.getOrPut(activity) { MotionState() }
        status?.text = when (selected) {
            DIR_LTR -> "진행방향 · 왼쪽 → 오른쪽 · ${state.lastStatus}"
            else -> "진행방향 · 오른쪽 → 왼쪽 · ${state.lastStatus}"
        }
        rtl?.text = if (selected == DIR_RTL) "✓ ← 우→좌" else "← 우→좌"
        ltr?.text = if (selected == DIR_LTR) "✓ 좌→우 →" else "좌→우 →"
    }

    private fun startDirectionSampler(activity: CameraGateHighSpeedActivity) {
        samplerJobs.remove(activity)?.let { main.removeCallbacks(it) }
        val state = motionStates.getOrPut(activity) { MotionState() }
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    samplerJobs.remove(activity)
                    return
                }
                sampleDirection(activity, state)
                main.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }
        samplerJobs[activity] = job
        main.postDelayed(job, 180L)
    }

    private fun sampleDirection(activity: CameraGateHighSpeedActivity, state: MotionState) {
        val texture = readField(activity, "textureView") as? TextureView ?: return
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0) return

        val bitmap = state.bitmap?.takeIf { !it.isRecycled }
            ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888).also { state.bitmap = it }
        val captured = runCatching { texture.getBitmap(bitmap) }.getOrNull() ?: return
        captured.getPixels(state.pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)

        val previous = state.previousGray
        val current = IntArray(SAMPLE_W * SAMPLE_H)
        var p = 0
        while (p < current.size) {
            val c = state.pixels[p]
            current[p] = ((Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8)
            p++
        }
        state.previousGray = current
        if (previous == null || previous.size != current.size) return

        var leftSum = 0L
        var leftN = 0
        var centerSum = 0L
        var centerN = 0
        var rightSum = 0L
        var rightN = 0
        val y0 = (SAMPLE_H * 0.12).toInt()
        val y1 = (SAMPLE_H * 0.88).toInt().coerceAtMost(SAMPLE_H)
        val left0 = (SAMPLE_W * 0.18).toInt()
        val left1 = (SAMPLE_W * 0.43).toInt()
        val center0 = (SAMPLE_W * 0.43).toInt()
        val center1 = (SAMPLE_W * 0.57).toInt()
        val right0 = (SAMPLE_W * 0.57).toInt()
        val right1 = (SAMPLE_W * 0.82).toInt()

        for (y in y0 until y1) {
            val base = y * SAMPLE_W
            for (x in left0 until left1) {
                leftSum += abs(current[base + x] - previous[base + x]); leftN++
            }
            for (x in center0 until center1) {
                centerSum += abs(current[base + x] - previous[base + x]); centerN++
            }
            for (x in right0 until right1) {
                rightSum += abs(current[base + x] - previous[base + x]); rightN++
            }
        }

        val left = if (leftN > 0) leftSum.toDouble() / leftN else 0.0
        val center = if (centerN > 0) centerSum.toDouble() / centerN else 0.0
        val right = if (rightN > 0) rightSum.toDouble() / rightN else 0.0
        val peak = max(left, max(center, right))
        val now = SystemClock.elapsedRealtime()

        if (peak >= MOTION_NOISE_FLOOR) {
            state.lastMotionAtMs = now
        } else if (state.lastMotionAtMs > 0L && now - state.lastMotionAtMs > MOTION_RESET_MS) {
            state.approachSide = SIDE_NONE
            state.approachAtMs = 0L
            state.lastDirection = DIR_UNKNOWN
            state.directionAtMs = 0L
        }

        if (right >= SIDE_MOTION_THRESHOLD && right > left * SIDE_DOMINANCE && right >= center * 0.88) {
            if (state.approachSide == SIDE_NONE || now - state.approachAtMs > APPROACH_TIMEOUT_MS) {
                state.approachSide = SIDE_RIGHT
                state.approachAtMs = now
            }
        } else if (left >= SIDE_MOTION_THRESHOLD && left > right * SIDE_DOMINANCE && left >= center * 0.88) {
            if (state.approachSide == SIDE_NONE || now - state.approachAtMs > APPROACH_TIMEOUT_MS) {
                state.approachSide = SIDE_LEFT
                state.approachAtMs = now
            }
        }

        if (center >= CENTER_MOTION_THRESHOLD && state.approachSide != SIDE_NONE && now - state.approachAtMs <= APPROACH_TIMEOUT_MS) {
            state.lastDirection = if (state.approachSide == SIDE_RIGHT) DIR_RTL else DIR_LTR
            state.directionAtMs = now
            state.lastStatus = if (state.lastDirection == DIR_RTL) "감지 ←" else "감지 →"
            refreshDirectionUi(activity)
        }
    }

    private fun attachTriggerGuard(activity: CameraGateHighSpeedActivity) {
        if (triggerWatchers.containsKey(activity)) return
        val trigger = readField(activity, "triggerText") as? TextView ?: return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val state = motionStates.getOrPut(activity) { MotionState() }
                if (state.mutatingTriggerText) return
                val text = s?.toString().orEmpty()
                if (!text.startsWith("TRIGGER #")) return

                val now = SystemClock.elapsedRealtime()
                val inferred = when {
                    state.lastDirection != DIR_UNKNOWN && now - state.directionAtMs <= DIRECTION_FRESH_MS -> state.lastDirection
                    state.approachSide == SIDE_RIGHT && now - state.approachAtMs <= APPROACH_TIMEOUT_MS -> DIR_RTL
                    state.approachSide == SIDE_LEFT && now - state.approachAtMs <= APPROACH_TIMEOUT_MS -> DIR_LTR
                    else -> DIR_UNKNOWN
                }
                val selected = selectedDirection(activity)

                if (inferred == selected) {
                    state.lastStatus = if (selected == DIR_RTL) "정방향 ← 확인" else "정방향 → 확인"
                    refreshDirectionUi(activity)
                    return
                }

                state.lastStatus = if (inferred == DIR_UNKNOWN) "방향 불명 · 기록 안 함" else "역방향 · 기록 안 함"
                refreshDirectionUi(activity)
                state.mutatingTriggerText = true
                try {
                    val chosen = if (selected == DIR_RTL) "← 오른쪽→왼쪽" else "왼쪽→오른쪽 →"
                    val seen = when (inferred) {
                        DIR_RTL -> "← 오른쪽→왼쪽"
                        DIR_LTR -> "왼쪽→오른쪽 →"
                        else -> "판정 불명"
                    }
                    trigger.text = if (inferred == DIR_UNKNOWN) {
                        "방향 판정 불명 · 기록 안 함\n선택 진행방향 $chosen"
                    } else {
                        "역방향 통과 · 기록 안 함\n감지 $seen · 선택 $chosen"
                    }
                    trigger.setTextColor(Color.rgb(255, 120, 95))
                } finally {
                    state.mutatingTriggerText = false
                }
            }
        }
        trigger.addTextChangedListener(watcher)
        triggerWatchers[activity] = watcher
    }

    private fun selectedDirection(context: Context): Int =
        prefs(context).getInt(KEY_DIRECTION, DIR_RTL).let { if (it == DIR_LTR) DIR_LTR else DIR_RTL }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun setCenteredSize(view: View, width: Int, height: Int) {
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        if (lp.width == width && lp.height == height && lp.gravity == Gravity.CENTER) return
        lp.width = width
        lp.height = height
        lp.gravity = Gravity.CENTER
        view.layoutParams = lp
        view.requestLayout()
    }

    private fun findTagged(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTagged(view.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun readIntField(target: Any, name: String): Int = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(target)
    }.getOrDefault(0)

    private fun writeIntField(target: Any, name: String, value: Int) {
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(target, value)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val PREFS = "camera_gate_test"
        private const val KEY_DIRECTION = "gate_direction_v1"

        private const val DIR_UNKNOWN = 0
        private const val DIR_RTL = -1
        private const val DIR_LTR = 1
        private const val SIDE_NONE = 0
        private const val SIDE_LEFT = -1
        private const val SIDE_RIGHT = 1

        private const val TAG_DIRECTION_ROW = "camera_gate_direction_row_v1"
        private const val TAG_DIRECTION_STATUS = "camera_gate_direction_status_v1"
        private const val TAG_RTL_BUTTON = "camera_gate_direction_rtl_v1"
        private const val TAG_LTR_BUTTON = "camera_gate_direction_ltr_v1"

        private const val SAMPLE_W = 64
        private const val SAMPLE_H = 32
        private const val SAMPLE_INTERVAL_MS = 35L
        private const val MOTION_NOISE_FLOOR = 3.5
        private const val SIDE_MOTION_THRESHOLD = 6.5
        private const val CENTER_MOTION_THRESHOLD = 7.0
        private const val SIDE_DOMINANCE = 1.18
        private const val MOTION_RESET_MS = 320L
        private const val APPROACH_TIMEOUT_MS = 850L
        private const val DIRECTION_FRESH_MS = 650L
    }
}
