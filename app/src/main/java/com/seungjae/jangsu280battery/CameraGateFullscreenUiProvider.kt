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
 * The timing camera owns the whole working surface. Only the controls needed during a race stay
 * over the preview at the lower left/right. All telemetry, clock/FPS/motion values and the event
 * log live in a single LOG drawer that can be opened on demand.
 *
 * CameraGatePreviewAspectProvider still owns FIT_CENTER sizing, so fullscreen here means the entire
 * display is allocated to the camera surface without stretching/cropping the source image.
 */
class CameraGateFullscreenUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, MutableList<Runnable>>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        cancel(activity)

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

        val delays = longArrayOf(120L, 280L, 600L, 1_100L, 1_900L)
        val list = mutableListOf<Runnable>()
        delays.forEach { delay ->
            val job = Runnable {
                if (!activity.isFinishing && !activity.isDestroyed) install(activity)
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

    private fun install(activity: CameraGateHighSpeedActivity) {
        val texture = readField(activity, "textureView") as? View ?: return
        val cameraBox = texture.parent as? FrameLayout ?: return
        val arm = readField(activity, "armButton") as? Button ?: return
        val root = cameraBox.parent as? LinearLayout ?: return

        val dock = findTagged(cameraBox, TAG_DOCK) as? LinearLayout
            ?: createDock(activity, cameraBox)
        val leftPanel = findTagged(dock, TAG_LEFT) as? LinearLayout ?: return
        val rightPanel = findTagged(dock, TAG_RIGHT) as? LinearLayout ?: return

        // Remove every old non-camera section from the normal vertical flow. We re-use the actual
        // controls/log views inside overlays so all existing listeners and telemetry updates survive.
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

        val controls = arm.parent as? LinearLayout
        if (controls != null && controls.parent !== rightPanel) {
            (controls.parent as? ViewGroup)?.removeView(controls)
            controls.visibility = View.VISIBLE
            controls.setPadding(0, 0, 0, dp(activity, 4))
            rightPanel.addView(
                controls,
                0,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        // The direction selector is created by CameraGatePreviewAspectProvider. It may appear a few
        // milliseconds after this provider, so repeated install passes move it into the lower-left.
        val direction = findTagged(activity.window.decorView, TAG_DIRECTION_ROW)
        if (direction != null && direction.parent !== leftPanel) {
            (direction.parent as? ViewGroup)?.removeView(direction)
            direction.visibility = View.VISIBLE
            leftPanel.addView(
                direction,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        val logPanel = findTagged(cameraBox, TAG_LOG_PANEL) as? FrameLayout
            ?: createLogPanel(activity, root, cameraBox)

        // If the old ScrollView was not ready during the first pass, adopt it now.
        adoptInfoScroll(root, logPanel)

        // Keep the overlay above the camera preview/gate line while leaving the line itself visible.
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
            setBackgroundColor(Color.argb(76, 0, 0, 0))
        }

        val left = LinearLayout(activity).apply {
            tag = TAG_LEFT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(0, 0, dp(activity, 6), 0)
        }
        val right = LinearLayout(activity).apply {
            tag = TAG_RIGHT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(activity, 6), 0, 0, 0)
        }

        dock.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dock.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actionRow = LinearLayout(activity).apply {
            tag = TAG_ACTIONS
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val logButton = Button(activity).apply {
            tag = TAG_LOG_BUTTON
            text = "로그"
            isAllCaps = false
            textSize = 15f
            setOnClickListener {
                val panel = findTagged(cameraBox, TAG_LOG_PANEL)
                if (panel != null) panel.visibility = View.VISIBLE
            }
        }
        val backButton = Button(activity).apply {
            text = "닫기"
            isAllCaps = false
            textSize = 15f
            setOnClickListener { activity.finish() }
        }
        actionRow.addView(logButton, LinearLayout.LayoutParams(0, dp(activity, 46), 1f).apply { marginEnd = dp(activity, 4) })
        actionRow.addView(backButton, LinearLayout.LayoutParams(0, dp(activity, 46), 1f).apply { marginStart = dp(activity, 4) })
        right.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
