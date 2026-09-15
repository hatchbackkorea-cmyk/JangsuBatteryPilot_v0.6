package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Final field-layout pass for broadcast-only CAM phones.
 *
 * It intentionally does not replace the camera/broadcast implementation. Instead it keeps the
 * existing working click handlers and moves those views into one compact right-side vertical rail:
 * role, LIVE status, 1x/2x/3x, photo, quality, REC and exit.
 *
 * It also keeps the displayed megapixel value derived from the same Camera2 JPEG size selection
 * rule used by BroadcastCameraAppProvider and hard-locks the activity to landscape at runtime.
 */
class BroadcastCameraLayoutPolishProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, Runnable>()
    private val animatedViews = WeakHashMap<View, Boolean>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is CameraGateHighSpeedActivity && isBroadcast(activity)) {
            forceLandscape(activity)
            main.post { apply(activity) }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity && isBroadcast(activity)) {
            forceLandscape(activity)
            main.post { apply(activity) }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity || !isBroadcast(activity)) return
        forceLandscape(activity)
        stop(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    stop(activity)
                    return
                }
                if (isBroadcast(activity)) {
                    forceLandscape(activity)
                    apply(activity)
                }
                main.postDelayed(this, POLL_MS)
            }
        }
        jobs[activity] = job
        main.post(job)
    }

    override fun onActivityPaused(activity: Activity) = stop(activity)
    override fun onActivityDestroyed(activity: Activity) {
        stop(activity)
        animatedViews.keys.removeIf { it.context === activity }
    }

    private fun stop(activity: Activity) {
        jobs.remove(activity)?.let(main::removeCallbacks)
    }

    private fun isBroadcast(context: Context): Boolean =
        TimingOperatorStore.current(context)?.role?.let(TimingOperatorStore::isBroadcastRole) == true

    private fun forceLandscape(activity: Activity) {
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun apply(activity: CameraGateHighSpeedActivity) {
        val texture = readField(activity, "textureView") as? View ?: return
        val cameraBox = texture.parent as? FrameLayout ?: return
        val appUi = findTagged(cameraBox, BroadcastCameraAppProvider.TAG_CAMERA_APP_UI) as? FrameLayout ?: return

        var rail = findTagged(appUi, TAG_RIGHT_RAIL) as? LinearLayout
        if (rail == null) {
            rail = buildRail(activity, cameraBox, appUi) ?: return
        }

        for (i in 0 until appUi.childCount) {
            val child = appUi.getChildAt(i)
            child.visibility = if (child === rail) View.VISIBLE else View.GONE
        }
        rail.visibility = View.VISIBLE
        rail.bringToFront()

        refreshQualityMp(activity, rail)
        normalizeCells(activity, rail)
    }

    private fun buildRail(
        activity: CameraGateHighSpeedActivity,
        cameraBox: FrameLayout,
        appUi: FrameLayout,
    ): LinearLayout? {
        val role = findTagged(cameraBox, TAG_ROLE_LABEL) as? TextView
        val live = findText(appUi) { it.startsWith("LIVE") }
        val zoom1 = findTagged(appUi, "broadcast_camera_zoom_1") as? TextView
        val zoom2 = findTagged(appUi, "broadcast_camera_zoom_2") as? TextView
        val zoom3 = findTagged(appUi, "broadcast_camera_zoom_3") as? TextView
        val quality = findText(appUi) {
            it.startsWith("사진화질") || it.startsWith("표준") || it.startsWith("고화질") || it.startsWith("최고")
        }
        val rec = findText(appUi) { it.contains("REC") || it.contains("STOP") }
        val exit = findText(appUi) { it.trim() == "나가기" }
        val shutter = findText(appUi) { it.trim() == "●" }

        if (zoom1 == null || zoom2 == null || zoom3 == null || quality == null || rec == null || exit == null || shutter == null) {
            return null
        }

        val rail = LinearLayout(activity).apply {
            tag = TAG_RIGHT_RAIL
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
            background = null
            isClickable = false
            isFocusable = false
        }
        appUi.addView(
            rail,
            FrameLayout.LayoutParams(dp(activity, CELL_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(activity, RAIL_TOP_DP)
                rightMargin = dp(activity, RAIL_RIGHT_DP)
            }
        )

        role?.let {
            moveIntoRail(it, rail)
            styleStatus(activity, it)
        }
        live?.let {
            moveIntoRail(it, rail)
            styleStatus(activity, it)
        }

        listOf(zoom1, zoom2, zoom3).forEach { button ->
            moveIntoRail(button, rail)
            styleButton(activity, button)
            installZoomPressAnimation(button)
        }

        shutter.text = "사진"
        shutter.textSize = 13f
        moveIntoRail(shutter, rail)
        styleButton(activity, shutter)

        quality.tag = TAG_QUALITY_CELL
        moveIntoRail(quality, rail)
        styleButton(activity, quality)

        moveIntoRail(rec, rail)
        styleButton(activity, rec)

        moveIntoRail(exit, rail)
        styleButton(activity, exit)

        findAllText(appUi)
            .filter { it.parent !== rail && ZOOM_READOUT.matches(it.text?.toString().orEmpty().trim()) }
            .forEach { it.visibility = View.GONE }

        return rail
    }

    private fun moveIntoRail(view: TextView, rail: LinearLayout) {
        if (view.parent !== rail) {
            (view.parent as? ViewGroup)?.removeView(view)
            rail.addView(view)
        }
    }

    private fun normalizeCells(activity: Activity, rail: LinearLayout) {
        for (i in 0 until rail.childCount) {
            val view = rail.getChildAt(i) as? TextView ?: continue
            val status = view.tag == TAG_ROLE_LABEL || view.text?.toString()?.startsWith("LIVE") == true
            val height = if (status) STATUS_H_DP else CELL_H_DP
            view.layoutParams = LinearLayout.LayoutParams(dp(activity, CELL_W_DP), dp(activity, height)).apply {
                bottomMargin = dp(activity, CELL_GAP_DP)
            }
            view.gravity = Gravity.CENTER
        }
    }

    private fun styleStatus(activity: Activity, view: TextView) {
        view.textSize = 12f
        view.setTextColor(Color.WHITE)
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        view.gravity = Gravity.CENTER
        view.setPadding(dp(activity, 4), 0, dp(activity, 4), 0)
        view.background = cellBackground(activity, alpha = 128, strokeAlpha = 68)
        view.isClickable = false
        view.isFocusable = false
    }

    private fun styleButton(activity: Activity, view: TextView) {
        view.textSize = 13f
        view.setTextColor(Color.WHITE)
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        view.gravity = Gravity.CENTER
        view.setPadding(dp(activity, 4), 0, dp(activity, 4), 0)
        view.background = cellBackground(activity, alpha = 154, strokeAlpha = 92)
        view.isFocusable = true
    }

    private fun cellBackground(activity: Activity, alpha: Int, strokeAlpha: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.argb(alpha, 14, 14, 16))
        cornerRadius = dp(activity, 10).toFloat()
        setStroke(dp(activity, 1), Color.argb(strokeAlpha, 255, 255, 255))
    }

    private fun installZoomPressAnimation(view: TextView) {
        if (animatedViews[view] == true) return
        animatedViews[view] = true
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.animate().cancel()
                    touched.animate()
                        .scaleX(1.14f)
                        .scaleY(1.14f)
                        .alpha(0.76f)
                        .setDuration(70L)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touched.animate().cancel()
                    touched.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(150L)
                        .start()
                }
            }
            false
        }
    }

    private fun refreshQualityMp(activity: CameraGateHighSpeedActivity, rail: LinearLayout) {
        val qualityView = findTagged(rail, TAG_QUALITY_CELL) as? TextView ?: return
        val cameraId = readField(activity, "cameraId") as? String ?: return
        if (cameraId.isBlank()) return

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (sizes.isEmpty()) return

        val quality = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_QUALITY, QUALITY_HIGH)
        val selected = choosePhotoSize(quality, sizes)
        val mp = selected.width.toLong() * selected.height.toLong() / 1_000_000.0
        val label = when (quality) {
            QUALITY_STANDARD -> "표준"
            QUALITY_HIGH -> "고화질"
            else -> "최고"
        }
        val expected = "$label\n${String.format(Locale.US, "%.1f", mp)}MP"
        if (qualityView.text?.toString() != expected) qualityView.text = expected
    }

    private fun choosePhotoSize(quality: Int, sizes: List<Size>): Size {
        if (quality == QUALITY_MAX) return sizes.first()
        val targetMp = if (quality == QUALITY_STANDARD) 8_000_000L else 12_500_000L
        return sizes.filter { it.width.toLong() * it.height.toLong() <= targetMp }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.last()
    }

    private fun findText(root: View, predicate: (String) -> Boolean): TextView? {
        if (root is TextView && predicate(root.text?.toString().orEmpty())) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findText(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun findAllText(root: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (root is TextView) out += root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findAllText(root.getChildAt(i), out)
        }
        return out
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
        private const val TAG_RIGHT_RAIL = "broadcast_camera_right_rail_v2"
        private const val TAG_ROLE_LABEL = "camera_gate_actual_role_label_v1"
        private const val TAG_QUALITY_CELL = "broadcast_camera_quality_cell_v2"

        private const val PREFS = "broadcast_camera_app"
        private const val KEY_QUALITY = "photo_quality"
        private const val QUALITY_STANDARD = 0
        private const val QUALITY_HIGH = 1
        private const val QUALITY_MAX = 2

        private const val CELL_W_DP = 92
        private const val CELL_H_DP = 36
        private const val STATUS_H_DP = 32
        private const val CELL_GAP_DP = 4
        private const val RAIL_TOP_DP = 8
        private const val RAIL_RIGHT_DP = 10
        private const val POLL_MS = 90L

        private val ZOOM_READOUT = Regex("^\\d+(?:\\.\\d+)?x$")
    }
}
