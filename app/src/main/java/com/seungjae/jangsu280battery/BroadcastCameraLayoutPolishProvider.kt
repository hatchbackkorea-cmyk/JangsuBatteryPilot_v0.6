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
import android.media.MediaActionSound
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Final camera-app polish for broadcast-only CAM phones.
 *
 * The underlying camera provider still owns camera state and click actions, while this provider owns
 * the visible field controls. Quality and REC use stable proxy views so the legacy provider can keep
 * updating its hidden source views without fighting over visible text/colors every few hundred ms.
 */
class BroadcastCameraLayoutPolishProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, Runnable>()
    private val animatedViews = WeakHashMap<View, Boolean>()
    private val shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

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
        val live = findTagged(appUi, TAG_LIVE_CENTER) as? TextView

        for (i in 0 until appUi.childCount) {
            val child = appUi.getChildAt(i)
            child.visibility = if (child === rail || child === live) View.VISIBLE else View.GONE
        }
        rail.visibility = View.VISIBLE
        rail.bringToFront()
        live?.apply {
            visibility = View.VISIBLE
            bringToFront()
        }

        refreshQualityMp(activity, rail)
        refreshZoomSelection(appUi, rail)
        refreshRecSelection(activity, appUi, rail)
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
        val qualitySource = findText(appUi) {
            it.startsWith("사진화질") || it.startsWith("표준") || it.startsWith("고화질") ||
                it.startsWith("최고") || MP_ONLY.matches(it.trim())
        }
        val recSource = findText(appUi) { it.contains("REC") || it.contains("STOP") }
        val exit = findText(appUi) { it.trim() == "나가기" }
        val shutter = findText(appUi) { it.trim() == "●" || it.trim() == "사진" }

        if (zoom1 == null || zoom2 == null || zoom3 == null || qualitySource == null ||
            recSource == null || exit == null || shutter == null
        ) return null

        live?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.tag = TAG_LIVE_CENTER
            styleLive(it)
            appUi.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(activity, LIVE_H_DP),
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(activity, LIVE_TOP_DP) }
            )
        }

        val rail = LinearLayout(activity).apply {
            tag = TAG_RIGHT_RAIL
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            background = null
            isClickable = false
            isFocusable = false
        }
        appUi.addView(
            rail,
            FrameLayout.LayoutParams(
                dp(activity, CELL_W_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(activity, RAIL_TOP_DP)
                rightMargin = dp(activity, RAIL_RIGHT_DP)
            }
        )

        role?.let {
            moveIntoRail(it, rail)
            styleStatus(activity, it)
        }

        listOf(zoom1, zoom2, zoom3).forEach { button ->
            moveIntoRail(button, rail)
            styleButton(activity, button)
            installZoomPressAnimation(button)
        }

        shutter.tag = TAG_SHUTTER_CELL
        shutter.text = ""
        moveIntoRail(shutter, rail)
        styleShutter(activity, shutter)
        installShutterFeedback(activity, shutter)

        // Keep the legacy quality TextView attached but hidden. BroadcastCameraAppProvider updates
        // that source every 260ms; the visible proxy below is therefore never overwritten/flickered.
        qualitySource.tag = TAG_QUALITY_SOURCE
        val qualityProxy = TextView(activity).apply {
            tag = TAG_QUALITY_CELL
            text = "--MP"
            isClickable = true
            setOnClickListener {
                installOneShotPulse(this)
                qualitySource.performClick()
                main.postDelayed({ refreshQualityMp(activity, rail) }, 40L)
                main.postDelayed({ refreshQualityMp(activity, rail) }, 220L)
            }
        }
        styleButton(activity, qualityProxy)
        rail.addView(qualityProxy)

        // Same idea for REC: the legacy source may freely change text/color while the visible proxy
        // remains stable. Its state is read from the hidden source, but its paint is owned here only.
        recSource.tag = TAG_REC_SOURCE
        val recProxy = TextView(activity).apply {
            tag = TAG_REC_CELL
            text = "REC"
            isClickable = true
            setOnClickListener {
                installOneShotPulse(this)
                recSource.performClick()
                main.postDelayed({ refreshRecSelection(activity, appUi, rail) }, 30L)
                main.postDelayed({ refreshRecSelection(activity, appUi, rail) }, 220L)
            }
        }
        styleButton(activity, recProxy)
        rail.addView(recProxy)

        moveIntoRail(exit, rail)
        styleButton(activity, exit)
        installPressPulse(exit, 0.90f, 1.05f)

        findAllText(appUi)
            .filter { it.parent !== rail && DECIMAL_ZOOM_READOUT.matches(it.text?.toString().orEmpty().trim()) }
            .forEach { it.visibility = View.GONE }

        normalizeCells(activity, rail)
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
            when (view.tag) {
                TAG_SHUTTER_CELL -> {
                    view.layoutParams = LinearLayout.LayoutParams(
                        dp(activity, SHUTTER_DP),
                        dp(activity, SHUTTER_DP)
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(activity, SHUTTER_GAP_DP)
                    }
                }
                TAG_ROLE_LABEL -> {
                    view.layoutParams = LinearLayout.LayoutParams(
                        dp(activity, CELL_W_DP),
                        dp(activity, STATUS_H_DP)
                    ).apply { bottomMargin = dp(activity, CELL_GAP_DP) }
                }
                else -> {
                    view.layoutParams = LinearLayout.LayoutParams(
                        dp(activity, CELL_W_DP),
                        dp(activity, CELL_H_DP)
                    ).apply { bottomMargin = dp(activity, CELL_GAP_DP) }
                }
            }
            view.gravity = Gravity.CENTER
        }
    }

    private fun styleLive(view: TextView) {
        view.text = "LIVE · 720p24"
        view.textSize = 14f
        view.setTextColor(Color.WHITE)
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        view.gravity = Gravity.CENTER
        view.setPadding(0, 0, 0, 0)
        view.background = null
        view.isClickable = false
        view.isFocusable = false
        view.setShadowLayer(3f, 0f, 1f, Color.argb(190, 0, 0, 0))
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

    private fun styleShutter(activity: Activity, view: TextView) {
        view.text = ""
        view.setPadding(0, 0, 0, 0)
        view.background = shutterBackground(activity, false)
        view.elevation = dp(activity, 3).toFloat()
        view.isClickable = true
        view.isFocusable = true
    }

    private fun shutterBackground(activity: Activity, redBorder: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(
            dp(activity, if (redBorder) 5 else 3),
            if (redBorder) Color.rgb(235, 42, 52) else Color.argb(215, 255, 255, 255)
        )
    }

    private fun cellBackground(activity: Activity, alpha: Int, strokeAlpha: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.argb(alpha, 14, 14, 16))
        cornerRadius = dp(activity, 10).toFloat()
        setStroke(dp(activity, 1), Color.argb(strokeAlpha, 255, 255, 255))
    }

    private fun selectedBackground(activity: Activity) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.WHITE)
        cornerRadius = dp(activity, 10).toFloat()
        setStroke(dp(activity, 1), Color.WHITE)
    }

    private fun recActiveBackground(activity: Activity) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.rgb(225, 45, 52))
        cornerRadius = dp(activity, 10).toFloat()
        setStroke(dp(activity, 1), Color.rgb(255, 118, 124))
    }

    private fun installShutterFeedback(activity: Activity, view: TextView) {
        if (animatedViews[view] == true) return
        animatedViews[view] = true
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.animate().cancel()
                    touched.background = shutterBackground(activity, true)
                    touched.animate()
                        .scaleX(0.78f)
                        .scaleY(0.78f)
                        .setDuration(55L)
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
                    touched.background = shutterBackground(activity, true)
                    touched.animate().cancel()
                    touched.animate()
                        .scaleX(1.18f)
                        .scaleY(1.18f)
                        .setDuration(85L)
                        .withEndAction {
                            touched.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(115L)
                                .start()
                            main.postDelayed({
                                if (touched.isAttachedToWindow) touched.background = shutterBackground(activity, false)
                            }, 130L)
                        }
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    touched.animate().cancel()
                    touched.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                    touched.background = shutterBackground(activity, false)
                }
            }
            false
        }
    }

    private fun installZoomPressAnimation(view: TextView) {
        if (animatedViews[view] == true) return
        animatedViews[view] = true
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.animate().cancel()
                    touched.animate()
                        .scaleX(0.74f)
                        .scaleY(0.74f)
                        .alpha(0.42f)
                        .setDuration(55L)
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    touched.animate().cancel()
                    touched.animate()
                        .scaleX(1.32f)
                        .scaleY(1.32f)
                        .alpha(1f)
                        .setDuration(85L)
                        .withEndAction {
                            touched.animate()
                                .scaleX(0.94f)
                                .scaleY(0.94f)
                                .setDuration(85L)
                                .withEndAction {
                                    touched.animate().scaleX(1f).scaleY(1f).setDuration(95L).start()
                                }
                                .start()
                        }
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    touched.animate().cancel()
                    touched.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(100L).start()
                }
            }
            false
        }
    }

    private fun installPressPulse(view: View, down: Float, up: Float) {
        if (animatedViews[view] == true) return
        animatedViews[view] = true
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.animate().cancel()
                    touched.animate().scaleX(down).scaleY(down).setDuration(60L).start()
                }
                MotionEvent.ACTION_UP -> {
                    touched.animate().cancel()
                    touched.animate().scaleX(up).scaleY(up).setDuration(80L).withEndAction {
                        touched.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                    }.start()
                }
                MotionEvent.ACTION_CANCEL -> touched.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
            }
            false
        }
    }

    private fun installOneShotPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 0.82f
        view.scaleY = 0.82f
        view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(95L).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
        }.start()
    }

    private fun refreshZoomSelection(appUi: FrameLayout, rail: LinearLayout) {
        val readout = findAllText(appUi)
            .firstOrNull { DECIMAL_ZOOM_READOUT.matches(it.text?.toString().orEmpty().trim()) }
            ?.text?.toString()?.trim()?.removeSuffix("x")?.toFloatOrNull()
            ?: return

        listOf(1, 2, 3).forEach { z ->
            val button = findTagged(rail, "broadcast_camera_zoom_$z") as? TextView ?: return@forEach
            val selected = abs(readout - z.toFloat()) < 0.08f
            if (button.isSelected == selected) return@forEach
            button.isSelected = selected
            if (selected) {
                button.setTextColor(Color.BLACK)
                button.background = selectedBackground(button.context as Activity)
            } else {
                button.setTextColor(Color.WHITE)
                button.background = cellBackground(button.context as Activity, alpha = 154, strokeAlpha = 92)
            }
        }
    }

    private fun refreshRecSelection(activity: Activity, appUi: FrameLayout, rail: LinearLayout) {
        val source = findTagged(appUi, TAG_REC_SOURCE) as? TextView ?: return
        val rec = findTagged(rail, TAG_REC_CELL) as? TextView ?: return
        val active = source.text?.toString()?.contains("STOP") == true
        rec.text = "REC"
        if (rec.isSelected == active) return
        rec.isSelected = active
        if (active) {
            rec.setTextColor(Color.WHITE)
            rec.background = recActiveBackground(activity)
        } else {
            rec.setTextColor(Color.BLACK)
            rec.background = selectedBackground(activity)
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
        val expected = "${String.format(Locale.US, "%.1f", mp)}MP"
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
        private const val TAG_RIGHT_RAIL = "broadcast_camera_right_rail_v4"
        private const val TAG_ROLE_LABEL = "camera_gate_actual_role_label_v1"
        private const val TAG_LIVE_CENTER = "broadcast_camera_live_center_v1"
        private const val TAG_SHUTTER_CELL = "broadcast_camera_shutter_cell_v1"
        private const val TAG_QUALITY_SOURCE = "broadcast_camera_quality_source_v1"
        private const val TAG_QUALITY_CELL = "broadcast_camera_quality_cell_v4"
        private const val TAG_REC_SOURCE = "broadcast_camera_rec_source_v1"
        private const val TAG_REC_CELL = "broadcast_camera_rec_cell_v2"

        private const val PREFS = "broadcast_camera_app"
        private const val KEY_QUALITY = "photo_quality"
        private const val QUALITY_STANDARD = 0
        private const val QUALITY_HIGH = 1
        private const val QUALITY_MAX = 2

        private const val CELL_W_DP = 92
        private const val CELL_H_DP = 38
        private const val STATUS_H_DP = 32
        private const val SHUTTER_DP = 68
        private const val SHUTTER_GAP_DP = 7
        private const val CELL_GAP_DP = 5
        private const val RAIL_TOP_DP = 8
        private const val RAIL_RIGHT_DP = 10
        private const val LIVE_H_DP = 34
        private const val LIVE_TOP_DP = 10
        private const val POLL_MS = 180L

        private val DECIMAL_ZOOM_READOUT = Regex("^\\d+\\.\\d+x$")
        private val MP_ONLY = Regex("^\\d+(?:\\.\\d+)?MP$", RegexOption.IGNORE_CASE)
    }
}
