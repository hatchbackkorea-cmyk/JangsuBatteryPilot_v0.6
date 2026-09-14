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
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Keeps Camera Gate preview at the camera stream's native aspect ratio.
 *
 * The previous UI stretched TextureView to the whole camera box, so a rotated 16:9 stream could
 * look horizontally/vertically distorted on a portrait phone. This shim uses FIT_CENTER semantics:
 * the entire camera image remains visible, nothing is cropped, and unused space is simply black.
 * The gate overlay is resized to the exact same rectangle so the visible timing line stays aligned
 * with the analyzed camera frame.
 */
class CameraGatePreviewAspectProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
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
        val delays = longArrayOf(0L, 80L, 220L, 500L, 1_000L, 1_800L)
        val list = mutableListOf<Runnable>()
        delays.forEach { delay ->
            val job = Runnable {
                if (!activity.isFinishing && !activity.isDestroyed) applyFit(activity)
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

    private fun applyFit(activity: CameraGateHighSpeedActivity) {
        val texture = readField(activity, "textureView") as? TextureView ?: return
        val overlay = readField(activity, "overlay") as? View ?: return
        val parent = texture.parent as? FrameLayout ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return

        val regular = readField(activity, "regularSize") as? Size ?: Size(1280, 720)
        val highSpeed = readField(activity, "highSpeedSize") as? Size
        val source = highSpeed ?: regular
        val orientation = readIntField(activity, "sensorOrientation")
        val rotated = orientation == 90 || orientation == 270
        val displayW = if (rotated) source.height else source.width
        val displayH = if (rotated) source.width else source.height
        if (displayW <= 0 || displayH <= 0) return

        val aspect = displayW.toDouble() / displayH.toDouble()
        val parentAspect = parentW.toDouble() / parentH.toDouble()
        val fitW: Int
        val fitH: Int
        if (parentAspect > aspect) {
            fitH = parentH
            fitW = (fitH * aspect).roundToInt().coerceAtLeast(1)
        } else {
            fitW = parentW
            fitH = (fitW / aspect).roundToInt().coerceAtLeast(1)
        }

        setCenteredSize(texture, fitW, fitH)
        setCenteredSize(overlay, fitW, fitH)

        // High-speed preview uses an EGL viewport cached by CameraGateGlAnalyzer. Keep that cache
        // synchronized with the resized TextureView so the producer also renders without stretching.
        val analyzer = readField(activity, "glAnalyzer")
        if (analyzer != null) {
            writeIntField(analyzer, "previewWidth", fitW)
            writeIntField(analyzer, "previewHeight", fitH)
        }
    }

    private fun setCenteredSize(view: View, width: Int, height: Int) {
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        if (lp.width == width && lp.height == height && lp.gravity == Gravity.CENTER) return
        lp.width = width
        lp.height = height
        lp.gravity = Gravity.CENTER
        view.layoutParams = lp
        view.requestLayout()
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun readIntField(target: Any, name: String): Int = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(target)
    }.getOrDefault(0)

    private fun writeIntField(target: Any, name: String, value: Int) {
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(target, value)
        }
    }

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
}
