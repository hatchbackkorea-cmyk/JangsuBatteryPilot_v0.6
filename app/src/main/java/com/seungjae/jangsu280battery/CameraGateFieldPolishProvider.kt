package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Field controls for Camera Gate.
 *
 * The visible field controls are fully independent from the legacy Button views. This prevents the
 * older direction/fullscreen providers from recreating a "방향 <" button over the L/R cell and
 * stealing touches. One glass capsule owns four equal clickable cells: L/R, start/stop, sync, log.
 * Reverse/unknown crossings never keep the green flash and only an approved timing trigger beeps.
 */
class CameraGateFieldPolishProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val installJobs = WeakHashMap<Activity, MutableList<Runnable>>()
    private val triggerWatchers = WeakHashMap<Activity, TextWatcher>()
    private val lastBeepedTrigger = WeakHashMap<Activity, Int>()
    private val tones = WeakHashMap<Activity, ToneGenerator>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        cancelInstall(activity)
        val jobs = mutableListOf<Runnable>()
        for (delay in longArrayOf(120L, 320L, 700L, 1_300L, 2_100L, 2_700L)) {
            val job = Runnable {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    polishDock(activity)
                    attachFeedbackGuard(activity)
                }
            }
            jobs += job
            main.postDelayed(job, delay)
        }
        installJobs[activity] = jobs
    }

    override fun onActivityPaused(activity: Activity) = cancelInstall(activity)

    override fun onActivityDestroyed(activity: Activity) {
        cancelInstall(activity)
        val watcher = triggerWatchers.remove(activity)
        if (watcher != null && activity is CameraGateHighSpeedActivity) {
            (readField(activity, "triggerText") as? TextView)?.removeTextChangedListener(watcher)
        }
        tones.remove(activity)?.release()
        lastBeepedTrigger.remove(activity)
    }

    private fun cancelInstall(activity: Activity) {
        installJobs.remove(activity)?.forEach { main.removeCallbacks(it) }
    }

    private fun polishDock(activity: CameraGateHighSpeedActivity) {
        val decor = activity.window.decorView
        val dock = findTagged(decor, TAG_DOCK) as? LinearLayout ?: return
        val left = findTagged(dock, TAG_LEFT) as? LinearLayout
        val right = findTagged(dock, TAG_RIGHT) as? LinearLayout ?: return

        removeLegacyDirectionUi(decor)

        left?.let {
            it.removeAllViews()
            it.visibility = View.GONE
        }

        // Keep the fullscreen dock invisible; only the four-cell capsule is visible.
        dock.setBackgroundColor(Color.TRANSPARENT)
        dock.gravity = Gravity.BOTTOM or Gravity.END
        dock.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 8), dp(activity, 8))
        dock.isClickable = false

        val rightLp = right.layoutParams as? LinearLayout.LayoutParams
        if (rightLp != null) {
            rightLp.weight = 1f
            rightLp.width = 0
            right.layoutParams = rightLp
        }
        right.gravity = Gravity.BOTTOM or Gravity.END
        right.setPadding(0, 0, 0, 0)
        right.visibility = View.VISIBLE

        // Never reuse the legacy Button objects. They can be re-parented by older providers and can
        // sit above the new bar. Wipe the right panel and create four fresh, explicit touch targets.
        right.removeAllViews()

        val row = LinearLayout(activity).apply {
            tag = TAG_CONTROL_BAR_V3
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(128, 14, 14, 16))
                cornerRadius = dp(activity, 11).toFloat()
                setStroke(dp(activity, 1), Color.argb(90, 255, 255, 255))
            }
            elevation = dp(activity, 3).toFloat()
        }

        val menuWidthPx = minOf(
            (activity.resources.displayMetrics.widthPixels * 0.76f).roundToInt(),
            dp(activity, 340)
        ).coerceAtLeast(dp(activity, 248))
        right.addView(
            row,
            LinearLayout.LayoutParams(menuWidthPx, dp(activity, 38)).apply {
                gravity = Gravity.END
            }
        )

        val direction = menuCell(activity, TAG_DIRECTION_CELL_V3)
        val start = menuCell(activity, TAG_START_CELL_V3)
        val sync = menuCell(activity, TAG_SYNC_CELL_V3)
        val log = menuCell(activity, TAG_LOG_CELL_V3)

        row.addView(direction, equalCellLp())
        row.addView(divider(activity))
        row.addView(start, equalCellLp())
        row.addView(divider(activity))
        row.addView(sync, equalCellLp())
        row.addView(divider(activity))
        row.addView(log, equalCellLp())

        refreshDirectionCell(activity, direction)
        refreshStartCell(activity, start)
        sync.text = "동기화"
        log.text = "로그"

        direction.setOnClickListener {
            val next = if (selectedDirection(activity) == DIR_LTR) DIR_RTL else DIR_LTR
            prefs(activity).edit().putInt(KEY_DIRECTION, next).apply()
            refreshDirectionCell(activity, direction)
            clearDirectionDetectorVisuals(activity)
        }
        start.setOnClickListener {
            invokePrivate(activity, "toggleArm")
            main.post { refreshStartCell(activity, start) }
        }
        sync.setOnClickListener {
            invokePrivate(activity, "syncClock")
        }
        log.setOnClickListener {
            val panel = findTagged(activity.window.decorView, TAG_LOG_PANEL)
            if (panel != null) {
                panel.visibility = View.VISIBLE
                panel.bringToFront()
            }
        }

        row.bringToFront()
        dock.bringToFront()
    }

    private fun menuCell(activity: Activity, tagValue: String): TextView = TextView(activity).apply {
        tag = tagValue
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, 0, 0, 0)
        background = null
        isClickable = true
        isFocusable = true
        isLongClickable = false
    }

    private fun equalCellLp() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    private fun divider(activity: Activity) = View(activity).apply {
        setBackgroundColor(Color.argb(62, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(dp(activity, 1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(activity, 7)
            bottomMargin = dp(activity, 7)
        }
    }

    private fun refreshDirectionCell(activity: Activity, view: TextView) {
        // L = travel toward screen-left (right -> left), R = travel toward screen-right (left -> right).
        view.text = if (selectedDirection(activity) == DIR_LTR) "R" else "L"
    }

    private fun refreshStartCell(activity: CameraGateHighSpeedActivity, view: TextView) {
        view.text = if (readBooleanField(activity, "armed")) "중지" else "시작"
    }

    private fun removeLegacyDirectionUi(root: View) {
        val victims = mutableListOf<View>()
        collectTagged(root, victims, TAG_DIRECTION_ROW)
        collectTagged(root, victims, TAG_DIRECTION_TOGGLE_V2)
        collectTagged(root, victims, TAG_COMPACT_ROW_V1)
        victims.distinct().forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    private fun clearDirectionDetectorVisuals(activity: CameraGateHighSpeedActivity) {
        removeLegacyDirectionUi(activity.window.decorView)
    }

    private fun attachFeedbackGuard(activity: CameraGateHighSpeedActivity) {
        if (triggerWatchers.containsKey(activity)) return
        val trigger = readField(activity, "triggerText") as? TextView ?: return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val snapshot = s?.toString().orEmpty()
                if (snapshot.startsWith("역방향") || snapshot.startsWith("방향 판정 불명")) {
                    main.post { clearGateFlash(activity) }
                    return
                }
                val index = Regex("^TRIGGER #(\\d+)")
                    .find(snapshot)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
                main.postDelayed({
                    val current = trigger.text?.toString().orEmpty()
                    if (!current.startsWith("TRIGGER #$index")) {
                        clearGateFlash(activity)
                        return@postDelayed
                    }
                    if (lastBeepedTrigger[activity] == index) return@postDelayed
                    lastBeepedTrigger[activity] = index
                    playLoudBeep(activity)
                }, 24L)
            }
        }
        trigger.addTextChangedListener(watcher)
        triggerWatchers[activity] = watcher
    }

    private fun clearGateFlash(activity: CameraGateHighSpeedActivity) {
        val overlay = readField(activity, "overlay") as? View ?: return
        runCatching {
            overlay.javaClass.getDeclaredField("flashUntil").apply { isAccessible = true }.setLong(overlay, 0L)
            overlay.invalidate()
        }
        runCatching { (activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() }
    }

    private fun playLoudBeep(activity: Activity) {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
            if (max > 0) audio?.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
        val tone = tones[activity]
            ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tones[activity] = it }
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) }
    }

    private fun selectedDirection(context: Context): Int =
        prefs(context).getInt(KEY_DIRECTION, DIR_RTL).let { if (it == DIR_LTR) DIR_LTR else DIR_RTL }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun invokePrivate(target: Any, method: String) {
        runCatching {
            target.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(target)
        }
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun readBooleanField(target: Any, name: String): Boolean = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(target)
    }.getOrDefault(false)

    private fun findTagged(view: View, tagValue: String): View? {
        if (view.tag == tagValue) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTagged(view.getChildAt(i), tagValue)?.let { return it }
            }
        }
        return null
    }

    private fun collectTagged(view: View, out: MutableList<View>, tagValue: String) {
        if (view.tag == tagValue) out += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTagged(view.getChildAt(i), out, tagValue)
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

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
        private const val DIR_RTL = -1
        private const val DIR_LTR = 1
        private const val BEEP_MS = 700

        private const val TAG_DOCK = "camera_gate_fullscreen_dock_v1"
        private const val TAG_LEFT = "camera_gate_fullscreen_left_v1"
        private const val TAG_RIGHT = "camera_gate_fullscreen_right_v1"
        private const val TAG_LOG_PANEL = "camera_gate_fullscreen_log_panel_v1"

        private const val TAG_DIRECTION_ROW = "camera_gate_direction_row_v1"
        private const val TAG_DIRECTION_TOGGLE_V2 = "camera_gate_direction_toggle_v2"
        private const val TAG_COMPACT_ROW_V1 = "camera_gate_compact_controls_v1"

        private const val TAG_CONTROL_BAR_V3 = "camera_gate_control_bar_v3"
        private const val TAG_DIRECTION_CELL_V3 = "camera_gate_direction_cell_v3"
        private const val TAG_START_CELL_V3 = "camera_gate_start_cell_v3"
        private const val TAG_SYNC_CELL_V3 = "camera_gate_sync_cell_v3"
        private const val TAG_LOG_CELL_V3 = "camera_gate_log_cell_v3"
    }
}
