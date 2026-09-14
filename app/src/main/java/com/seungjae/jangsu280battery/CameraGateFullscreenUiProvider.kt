package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Field UI for Camera Gate.
 *
 * The timing camera owns the whole working surface. All legacy controls are hidden before the
 * activity gets its first visible frame. The compact field-control provider owns the visible
 * lower-right controls, while telemetry/event data live in the LOG drawer.
 */
class CameraGateFullscreenUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, MutableList<Runnable>>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is CameraGateHighSpeedActivity) return
        // ActivityLifecycleCallbacks reaches here before the first frame is presented. Hide the
        // original ARM / time-resync row immediately so it can never flash during QR -> camera.
        hideLegacyControls(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) hideLegacyControls(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        cancel(activity)
        hideLegacyControls(activity)

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        runCatching {
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        val delays = longArrayOf(0L, 80L, 180L, 360L, 700L, 1_100L, 1_900L)
        val list = mutableListOf<Runnable>()
        delays.forEach { delay ->
            val job = Runnable {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    hideLegacyControls(activity)
                    install(activity)
                }
            }
            list += job
            main.postDelayed(job, delay)
        }
        jobs[activity] = list
    }

    override fun onActivityPaused(activity: Activity) = cancel(activity)
    override fun onActivityDestroyed(activity: Activity) = cancel(activity)

    private fun cancel(activity: Activity) {
        jobs.remove(activity)?.forEach { main.removeCallbacks(it) }
    }

    private fun hideLegacyControls(activity: CameraGateHighSpeedActivity) {
        val arm = readField(activity, "armButton") as? Button ?: return
        (arm.parent as? View)?.visibility = View.GONE
        findTagged(activity.window.decorView, TAG_DIRECTION_ROW)?.visibility = View.GONE
    }

    private fun install(activity: CameraGateHighSpeedActivity) {
        val texture = readField(activity, "textureView") as? View ?: return
        val cameraBox = texture.parent as? FrameLayout ?: return
        val root = cameraBox.parent as? LinearLayout ?: return

        val dock = findTagged(cameraBox, TAG_DOCK) as? LinearLayout
            ?: createDock(activity, cameraBox)
        val leftPanel = findTagged(dock, TAG_LEFT) as? LinearLayout ?: return
        val rightPanel = findTagged(dock, TAG_RIGHT) as? LinearLayout ?: return

        // Remove every old non-camera section from the normal vertical flow. The compact field
        // provider creates its own independent menu cells, so legacy ARM/sync views never need to
        // be re-parented or made visible again.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child !== cameraBox) child.visibility = View.GONE
        }
        val lp = cameraBox.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = 0
        lp.weight = 1f
        cameraBox.layoutParams = lp

        // Explicitly keep all legacy field controls gone on every install pass.
        hideLegacyControls(activity)
        leftPanel.removeAllViews()
        leftPanel.visibility = View.GONE
        rightPanel.visibility = View.VISIBLE

        val logPanel = findTagged(cameraBox, TAG_LOG_PANEL) as? FrameLayout
            ?: createLogPanel(activity, root, cameraBox)

        adoptInfoScroll(root, logPanel)

        dock.bringToFront()
        logPanel.bringToFront()
        cameraBox.requestLayout()
    }

    private fun createDock(activity: CameraGateHighSpeedActivity, cameraBox: FrameLayout): LinearLayout {
        val dock = LinearLayout(activity).apply {
            tag = TAG_DOCK
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 8))
            setBackgroundColor(Color.TRANSPARENT)
        }

        val left = LinearLayout(activity).apply {
            tag = TAG_LEFT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            visibility = View.GONE
        }
        val right = LinearLayout(activity).apply {
            tag = TAG_RIGHT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(activity, 6), 0, 0, 0)
        }

        dock.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
        dock.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Keep a hidden compatibility log button because older code may still look it up by tag.
        // The new field-control provider exposes the actual visible LOG cell.
        val actionRow = LinearLayout(activity).apply {
            tag = TAG_ACTIONS
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        val logButton = Button(activity).apply {
            tag = TAG_LOG_BUTTON
            text = "로그"
            visibility = View.GONE
            setOnClickListener {
                findTagged(cameraBox, TAG_LOG_PANEL)?.visibility = View.VISIBLE
            }
        }
        actionRow.addView(logButton, LinearLayout.LayoutParams(1, 1))
        right.addView(actionRow, LinearLayout.LayoutParams(1, 1))

        cameraBox.addView(
            dock,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        return dock
    }

    private fun createLogPanel(
        activity: CameraGateHighSpeedActivity,
        root: LinearLayout,
        cameraBox: FrameLayout
    ): FrameLayout {
        val panel = FrameLayout(activity).apply {
            tag = TAG_LOG_PANEL
            visibility = View.GONE
            setBackgroundColor(Color.argb(232, 0, 0, 0))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 10), dp(activity, 8))
            setBackgroundColor(Color.argb(238, 14, 14, 14))
        }
        header.addView(TextView(activity).apply {
            text = "로그"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(Button(activity).apply {
            text = "닫기"
            isAllCaps = false
            setOnClickListener { panel.visibility = View.GONE }
        }, LinearLayout.LayoutParams(dp(activity, 86), dp(activity, 48)))
        panel.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        cameraBox.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        adoptInfoScroll(root, panel)
        return panel
    }

    private fun adoptInfoScroll(root: LinearLayout, panel: FrameLayout) {
        if (findTagged(panel, TAG_LOG_SCROLL) != null) return
        val scroll = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filterIsInstance<ScrollView>()
            .firstOrNull() ?: return

        (scroll.parent as? ViewGroup)?.removeView(scroll)
        scroll.tag = TAG_LOG_SCROLL
        scroll.visibility = View.VISIBLE
        scroll.setBackgroundColor(Color.TRANSPARENT)
        panel.addView(
            scroll,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(scroll.context as Activity, 66)
                bottomMargin = dp(scroll.context as Activity, 10)
            }
        )
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

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG_DOCK = "camera_gate_fullscreen_dock_v1"
        private const val TAG_LEFT = "camera_gate_fullscreen_left_v1"
        private const val TAG_RIGHT = "camera_gate_fullscreen_right_v1"
        private const val TAG_ACTIONS = "camera_gate_fullscreen_actions_v1"
        private const val TAG_LOG_BUTTON = "camera_gate_fullscreen_log_button_v1"
        private const val TAG_LOG_PANEL = "camera_gate_fullscreen_log_panel_v1"
        private const val TAG_LOG_SCROLL = "camera_gate_fullscreen_log_scroll_v1"
        private const val TAG_DIRECTION_ROW = "camera_gate_direction_row_v1"
    }
}
