package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.abs

/**
 * Camera Gate v13 regular-FPS compatibility shim.
 *
 * Some phones expose both a fixed 30-30 range and a variable 30-60 range, but do not expose a
 * fixed 60-60 range through Camera2. The original selector preferred fixed 30 before considering
 * the variable 60-capable range, so those phones unnecessarily fell back to 30 FPS even though
 * their camera pipeline can deliver 60 FPS.
 *
 * This provider runs before the high-speed health fallback fires, promotes the regular fallback
 * range to the best range that contains 60 FPS (60-60 first, then 30-60/15-60/etc.), and leaves
 * 30 FPS as the final fallback only when Camera2 exposes no 60-capable regular range.
 */
class CameraGateRegularFpsProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        main.post { markV13(activity.window.decorView) }
        promoteRegular60WithRetry(activity, 0)
    }

    private fun promoteRegular60WithRetry(activity: CameraGateHighSpeedActivity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val promoted = promoteRegular60(activity)
        if (!promoted && attempt < MAX_DISCOVERY_RETRY) {
            main.postDelayed({ promoteRegular60WithRetry(activity, attempt + 1) }, DISCOVERY_RETRY_MS)
        }
    }

    private fun promoteRegular60(activity: CameraGateHighSpeedActivity): Boolean = runCatching {
        val cameraId = readField(activity, "cameraId")?.toString().orEmpty()
        if (cameraId.isBlank()) return@runCatching false

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val ranges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        val best60 = chooseBest60Range(ranges) ?: return@runCatching true

        val current = readField(activity, "regularRange") as? Range<*>
        if (current?.lower == best60.lower && current.upper == best60.upper) return@runCatching true

        writeField(activity, "regularRange", best60)
        true
    }.getOrDefault(false)

    private fun chooseBest60Range(ranges: List<Range<Int>>): Range<Int>? {
        ranges.firstOrNull { it.lower == 60 && it.upper == 60 }?.let { return it }

        return ranges
            .filter { it.lower <= 60 && it.upper >= 60 }
            .sortedWith(
                compareBy<Range<Int>> { abs(it.upper - 60) }
                    .thenByDescending { it.lower }
                    .thenBy { it.upper }
            )
            .firstOrNull()
    }

    private fun readField(activity: CameraGateHighSpeedActivity, name: String): Any? = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(activity)
    }.getOrNull()

    private fun writeField(activity: CameraGateHighSpeedActivity, name: String, value: Any?) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(activity, value)
        }
    }

    private fun markV13(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v")) {
                view.text = text.replace(Regex("CAMERA GATE BETA v\\d+"), "CAMERA GATE BETA v13")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV13(view.getChildAt(i))
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

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
        private const val DISCOVERY_RETRY_MS = 250L
        private const val MAX_DISCOVERY_RETRY = 8
    }
}
