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
import java.util.WeakHashMap

/**
 * Broadcast-only CAM phones keep only the camera preview, actual role badge, and the dedicated
 * camera-app controls. Every timing-only overlay/menu remains suppressed.
 * START/CP/FINISH assignments are untouched.
 */
class BroadcastCameraCleanUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, Runnable>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is CameraGateHighSpeedActivity) main.post { apply(activity) }
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) main.post { apply(activity) }
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
                main.postDelayed(this, 180L)
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

        val texture = readField(activity, "textureView") as? View ?: return
        val overlay = readField(activity, "overlay") as? View
        val cameraBox = texture.parent as? FrameLayout ?: return
        val root = cameraBox.parent as? LinearLayout ?: return

        for (i in 0 until cameraBox.childCount) {
            val child = cameraBox.getChildAt(i)
            val keep = child === texture ||
                child.tag == TAG_ROLE_LABEL ||
                child.tag == BroadcastCameraAppProvider.TAG_CAMERA_APP_UI
            child.visibility = if (keep) View.VISIBLE else View.GONE
        }
        overlay?.visibility = View.GONE

        TIMING_TAGS.forEach { tag ->
            findTagged(cameraBox, tag)?.let { victim ->
                (victim.parent as? ViewGroup)?.removeView(victim)
            }
        }

        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            child.visibility = if (child === cameraBox) View.VISIBLE else View.GONE
        }

        (cameraBox.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = 0
            lp.weight = 1f
            cameraBox.layoutParams = lp
        }
        texture.visibility = View.VISIBLE
        findTagged(cameraBox, TAG_ROLE_LABEL)?.apply {
            visibility = View.VISIBLE
            bringToFront()
        }
        findTagged(cameraBox, BroadcastCameraAppProvider.TAG_CAMERA_APP_UI)?.apply {
            visibility = View.VISIBLE
            bringToFront()
        }
        findTagged(cameraBox, TAG_ROLE_LABEL)?.bringToFront()
        cameraBox.requestLayout()
    }

    private fun findTagged(view: View, tagValue: String): View? {
        if (view.tag == tagValue) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTagged(view.getChildAt(i), tagValue)?.let { return it }
            }
        }
        return null
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG_ROLE_LABEL = "camera_gate_actual_role_label_v1"
        private val TIMING_TAGS = listOf(
            "camera_gate_fullscreen_dock_v1",
            "camera_gate_fullscreen_log_panel_v1",
            "camera_gate_fullscreen_log_scroll_v1",
            "camera_gate_direction_row_v1",
            "camera_gate_direction_toggle_v2",
            "camera_gate_compact_controls_v1",
            "camera_gate_control_bar_v3",
            "camera_gate_control_bar_v4",
            "camera_gate_direction_cell_v4",
            "camera_gate_start_cell_v4",
            "camera_gate_sensitivity_cell_v4",
            "camera_gate_sync_cell_v4",
            "camera_gate_log_cell_v4"
        )
    }
}
