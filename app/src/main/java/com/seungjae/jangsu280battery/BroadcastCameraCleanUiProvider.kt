package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Broadcast-only CAM phones use the same proven Camera Gate capture/encoder pipeline as timing
 * phones, but none of the timing controls belong on their field screen.
 *
 * For CAM1..CAM12 this provider:
 *  - hides the timing detection overlay/line,
 *  - removes ARM, clock sync, sensitivity, FPS/score, trigger and log UI,
 *  - lets the camera preview fill all space below the small navigation bar,
 *  - relabels the header as a 720p24 live camera.
 *
 * START/CP/FINISH assignments are deliberately untouched, so their timing line and controls remain.
 */
class BroadcastCameraCleanUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
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
                apply(activity)
                main.postDelayed(this, 350L)
            }
        }
        jobs[activity] = job
        main.post(job)
    }

    override fun onActivityPaused(activity: Activity) = stop(activity)
    override fun onActivityDestroyed(activity: Activity) = stop(activity)

    private fun stop(activity: Activity) {
        jobs.remove(activity)?.let(main::removeCallbacks)
    }

    private fun apply(activity: CameraGateHighSpeedActivity) {
        val assignment = TimingOperatorStore.current(activity) ?: return
        if (!TimingOperatorStore.isBroadcastRole(assignment.role)) return

        val overlay = readField(activity, "overlay") as? View ?: return
        overlay.visibility = View.GONE
        val cameraBox = overlay.parent as? FrameLayout ?: return
        val root = cameraBox.parent as? LinearLayout ?: return
        val cameraIndex = root.indexOfChild(cameraBox)
        if (cameraIndex < 0) return

        // Keep only the navigation/header row and the camera itself. Any role selector or timing
        // widgets injected later by compatibility providers are hidden again on the next pass.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === cameraBox || i < cameraIndex) {
                child.visibility = View.VISIBLE
            } else {
                child.visibility = View.GONE
            }
        }

        (cameraBox.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height != 0 || lp.weight != 1f) {
                lp.height = 0
                lp.weight = 1f
                cameraBox.layoutParams = lp
            }
        }

        // Replace the beta/timing-oriented heading while keeping the back button available.
        if (cameraIndex > 0) {
            replaceHeaderText(root.getChildAt(cameraIndex - 1))
        }
    }

    private fun replaceHeaderText(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE") || text.contains("DIRECT 120")) {
                view.text = "TIMEGATE LIVE CAMERA · 720p24"
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) replaceHeaderText(view.getChildAt(i))
        }
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
