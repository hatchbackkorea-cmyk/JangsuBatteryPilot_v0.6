package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap

/**
 * Replaces the legacy fixed "START LINE" camera label with the phone's actual timing role.
 * QR-assigned official phones use TimingOperatorStore as the source of truth, so CP/FINISH
 * cameras can never be mistaken for START at a glance.
 */
class CameraGateRoleLabelProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, Runnable>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        stop(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    stop(activity)
                    return
                }
                applyRoleLabel(activity)
                main.postDelayed(this, 500L)
            }
        }
        jobs[activity] = job
        main.post(job)
    }

    override fun onActivityPaused(activity: Activity) = stop(activity)
    override fun onActivityDestroyed(activity: Activity) = stop(activity)

    private fun stop(activity: Activity) {
        jobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun applyRoleLabel(activity: CameraGateHighSpeedActivity) {
        val overlay = readField(activity, "overlay") as? View ?: return
        val cameraBox = overlay.parent as? FrameLayout ?: return

        // GateOverlay redraws its color every frame but never resets text size. Setting text size to
        // zero cleanly removes the old hard-coded START LINE text while preserving the timing line.
        val paint = readField(overlay, "textPaint") as? Paint
        if (paint != null && paint.textSize != 0f) {
            paint.textSize = 0f
            overlay.invalidate()
        }

        val role = resolvedRole(activity)
        var label = findTagged(cameraBox, TAG_ROLE_LABEL) as? TextView
        if (label == null) {
            label = TextView(activity).apply {
                tag = TAG_ROLE_LABEL
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5))
                setBackgroundColor(Color.argb(176, 0, 0, 0))
                elevation = dp(activity, 5).toFloat()
            }
            cameraBox.addView(
                label,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(activity, 12)
                    topMargin = dp(activity, 10)
                }
            )
        }
        if (label.text?.toString() != role) label.text = role
        label.bringToFront()
    }

    private fun resolvedRole(context: Context): String {
        val assigned = TimingOperatorStore.current(context)?.role
            ?.trim()?.uppercase(Locale.US).orEmpty()
        if (assigned == "START" || assigned == "FINISH" || assigned.matches(Regex("CP[1-5]"))) {
            return assigned
        }

        val manual = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "START")
            ?.trim()?.uppercase(Locale.US).orEmpty()
        return when {
            manual == "FINISH" -> "FINISH"
            manual.matches(Regex("CP[1-5]")) -> manual
            else -> "START"
        }
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun findTagged(view: View, tagValue: String): View? {
        if (view.tag == tagValue) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTagged(view.getChildAt(i), tagValue)?.let { return it }
            }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

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
        private const val TAG_ROLE_LABEL = "camera_gate_actual_role_label_v1"
        private const val PREFS = "camera_gate_test"
        private const val KEY_ROLE = "gate_role_v16"
    }
}
