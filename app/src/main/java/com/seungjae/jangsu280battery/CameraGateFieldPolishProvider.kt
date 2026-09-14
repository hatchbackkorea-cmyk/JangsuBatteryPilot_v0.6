package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Final field polish for Camera Gate.
 *
 * - One compact lower-right glass control bar split into four equal cells.
 * - Direction cell shows only L/R and toggles the selected travel direction.
 * - Start/stop, sync and log occupy the remaining three equal cells.
 * - Old direction/status UI at lower-left is removed completely.
 * - Reverse/unknown crossings never show the green gate flash.
 * - A loud alarm-stream beep is played only for a direction-approved timing trigger.
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
        for (delay in longArrayOf(120L, 320L, 700L, 1_300L, 2_100L)) {
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
        val arm = readField(activity, "armButton") as? Button ?: return
        val sync = findButton(decor) {
            it.text?.toString()?.contains("재동기화") == true || it.text?.toString() == "동기화"
        } ?: return
        val log = findTagged(dock, TAG_LOG_BUTTON) as? Button ?: return

        // Delete the old lower-left direction/status block completely. The detector itself continues
        // to use the same shared preference, so no timing logic is lost.
        findTagged(decor, TAG_DIRECTION_ROW)?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }
        left?.let {
            it.removeAllViews()
            it.visibility = View.GONE
        }

        // The fullscreen dock itself must not look like a bar. Only the compact four-cell capsule is visible.
        dock.setBackgroundColor(Color.TRANSPARENT)
        dock.gravity = Gravity.BOTTOM or Gravity.END
        dock.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 8), dp(activity, 8))

        val rightLp = right.layoutParams as? LinearLayout.LayoutParams
        if (rightLp != null) {
            rightLp.weight = 1f
            rightLp.width = 0
            right.layoutParams = rightLp
        }
        right.gravity = Gravity.BOTTOM or Gravity.END
        right.setPadding(0, 0, 0, 0)

        var row = findTagged(right, TAG_COMPACT_ROW) as? LinearLayout
        if (row == null) {
            row = LinearLayout(activity).apply {
                tag = TAG_COMPACT_ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipToOutline = true
            }
            right.removeAllViews()
            right.addView(row)
        }

        val menuWidthPx = minOf(
            (activity.resources.displayMetrics.widthPixels * 0.76f).roundToInt(),
            dp(activity, 340)
        ).coerceAtLeast(dp(activity, 248))
        row.layoutParams = LinearLayout.LayoutParams(menuWidthPx, dp(activity, 36))
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(128, 14, 14, 16))
            cornerRadius = dp(activity, 11).toFloat()
            setStroke(dp(activity, 1), Color.argb(90, 255, 255, 255))
        }
        row.elevation = dp(activity, 2).toFloat()

        var direction = findTagged(row, TAG_DIRECTION_TOGGLE) as? Button
        if (direction == null) {
            direction = Button(activity).apply {
                tag = TAG_DIRECTION_TOGGLE
                isAllCaps = false
                setOnClickListener {
                    val next = if (selectedDirection(activity) == DIR_LTR) DIR_RTL else DIR_LTR
                    prefs(activity).edit().putInt(KEY_DIRECTION, next).apply()
                    refreshDirectionButton(activity, this)
                    resetDirectionDetector(activity)
                }
            }
        }

        // Re-home the existing functional buttons, preserving their original sync/log behavior.
        for (button in listOf(direction, arm, sync, log)) {
            (button.parent as? ViewGroup)?.removeView(button)
        }
        row.removeAllViews()

        styleCell(direction)
        styleCell(arm)
        styleCell(sync)
        styleCell(log)

        row.addView(direction, equalCellLp())
        row.addView(divider(activity))
        row.addView(arm, equalCellLp())
        row.addView(divider(activity))
        row.addView(sync, equalCellLp())
        row.addView(divider(activity))
        row.addView(log, equalCellLp())

        refreshDirectionButton(activity, direction)
        arm.text = if (readBooleanField(activity, "armed")) "중지" else "시작"
        arm.setOnClickListener {
            invokePrivate(activity, "toggleArm")
            arm.text = if (readBooleanField(activity, "armed")) "중지" else "시작"
        }
        sync.text = "동기화"
        log.text = "로그"

        // Remove only the field-screen close button. The close button inside the LOG drawer stays.
        removeBottomCloseButtons(dock)
    }

    private fun styleCell(button: Button) {
        button.textSize = 12f
        button.minHeight = 0
        button.minWidth = 0
        button.setTextColor(Color.WHITE)
        button.setPadding(0, 0, 0, 0)
        button.setBackgroundColor(Color.TRANSPARENT)
        button.elevation = 0f
        button.gravity = Gravity.CENTER
    }

    private fun equalCellLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    private fun divider(activity: Activity) = View(activity).apply {
        setBackgroundColor(Color.argb(62, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(dp(activity, 1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(activity, 7)
            bottomMargin = dp(activity, 7)
        }
    }

    private fun refreshDirectionButton(activity: Activity, button: Button) {
        // L means race travel toward screen-left; R means race travel toward screen-right.
        button.text = if (selectedDirection(activity) == DIR_LTR) "R" else "L"
    }

    private fun removeBottomCloseButtons(dock: ViewGroup) {
        val victims = mutableListOf<View>()
        collectButtons(dock, victims) { it.text?.toString() == "닫기" }
        victims.forEach { (it.parent as? ViewGroup)?.removeView(it) }
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
                    // Base trigger code may already have scheduled a green flash. Clear it before the
                    // next frame is drawn, so rejected travel stays red on screen.
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

    private fun resetDirectionDetector(activity: CameraGateHighSpeedActivity) {
        // Stale approach direction expires in under one second in the existing direction detector.
        // The old visual row intentionally stays removed.
        findTagged(activity.window.decorView, TAG_DIRECTION_ROW)?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }
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

    private fun findTagged(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findTagged(view.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun findButton(view: View, predicate: (Button) -> Boolean): Button? {
        if (view is Button && predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findButton(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun collectButtons(view: View, out: MutableList<View>, predicate: (Button) -> Boolean) {
        if (view is Button && predicate(view)) out += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), out, predicate)
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
        private const val TAG_LOG_BUTTON = "camera_gate_fullscreen_log_button_v1"
        private const val TAG_DIRECTION_ROW = "camera_gate_direction_row_v1"
        private const val TAG_COMPACT_ROW = "camera_gate_compact_controls_v1"
        private const val TAG_DIRECTION_TOGGLE = "camera_gate_direction_toggle_v2"
    }
}
