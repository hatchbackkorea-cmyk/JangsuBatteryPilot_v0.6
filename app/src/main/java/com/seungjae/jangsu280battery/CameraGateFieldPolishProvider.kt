package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
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
 * - Compact lower-right controls: direction, start/stop, sync, log.
 * - Direction is one toggle button (방향 > / 방향 <).
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
        val sync = findButton(decor) { it.text?.toString()?.contains("재동기화") == true } ?: return
        val log = findTagged(dock, TAG_LOG_BUTTON) as? Button ?: return

        // Hide the old two-button direction row. The compact single toggle below owns direction UI.
        findTagged(decor, TAG_DIRECTION_ROW)?.visibility = View.GONE

        left?.let {
            it.removeAllViews()
            val lp = it.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.weight = 0.35f
                lp.width = 0
                it.layoutParams = lp
            }
        }
        val rightLp = right.layoutParams as? LinearLayout.LayoutParams
        if (rightLp != null) {
            rightLp.weight = 1.65f
            rightLp.width = 0
            right.layoutParams = rightLp
        }

        var row = findTagged(right, TAG_COMPACT_ROW) as? LinearLayout
        if (row == null) {
            row = LinearLayout(activity).apply {
                tag = TAG_COMPACT_ROW
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(activity, 2))
            }
            right.removeAllViews()
            right.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        var direction = findTagged(row, TAG_DIRECTION_TOGGLE) as? Button
        if (direction == null) {
            direction = Button(activity).apply {
                tag = TAG_DIRECTION_TOGGLE
                isAllCaps = false
                textSize = 12f
                minHeight = 0
                minWidth = 0
                setPadding(dp(activity, 6), 0, dp(activity, 6), 0)
                setOnClickListener {
                    val next = if (selectedDirection(activity) == DIR_LTR) DIR_RTL else DIR_LTR
                    prefs(activity).edit().putInt(KEY_DIRECTION, next).apply()
                    refreshDirectionButton(activity, this)
                    resetDirectionDetector(activity)
                }
            }
        }

        // Re-home existing buttons so their original behavior survives.
        for (button in listOf(direction, arm, sync, log)) {
            (button.parent as? ViewGroup)?.removeView(button)
        }
        row.removeAllViews()

        compact(direction, activity, 1.05f)
        compact(arm, activity, 0.85f)
        compact(sync, activity, 0.95f)
        compact(log, activity, 0.78f)

        row.addView(direction, weightLp(activity, 1.05f))
        row.addView(arm, weightLp(activity, 0.85f))
        row.addView(sync, weightLp(activity, 0.95f))
        row.addView(log, weightLp(activity, 0.78f))

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

    private fun compact(button: Button, activity: Activity, weight: Float) {
        button.textSize = 12f
        button.minHeight = 0
        button.minWidth = 0
        button.setPadding(dp(activity, 5), 0, dp(activity, 5), 0)
        button.layoutParams = weightLp(activity, weight)
    }

    private fun weightLp(activity: Activity, weight: Float) =
        LinearLayout.LayoutParams(0, dp(activity, 38), weight).apply {
            marginStart = dp(activity, 2)
            marginEnd = dp(activity, 2)
        }

    private fun refreshDirectionButton(activity: Activity, button: Button) {
        button.text = if (selectedDirection(activity) == DIR_LTR) "방향 >" else "방향 <"
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
                val index = Regex("^TRIGGER #(\\d+)").find(snapshot)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
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
        // Reject feedback should also not keep buzzing after the direction guard rejected it.
        runCatching { (activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() }
    }

    private fun playLoudBeep(activity: Activity) {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
            if (max > 0) audio?.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
        val tone = tones[activity] ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tones[activity] = it }
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) }
    }

    private fun resetDirectionDetector(activity: CameraGateHighSpeedActivity) {
        // Existing provider owns the detector. Resetting by toggling its visible row is unnecessary;
        // changing the shared preference is enough, and stale direction expires in <1 second.
        findTagged(activity.window.decorView, TAG_DIRECTION_ROW)?.visibility = View.GONE
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

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
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
