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
import android.view.Surface
import java.nio.FloatBuffer
import java.util.WeakHashMap

/**
 * Keeps the GL camera preview and the timing strip aligned with the current display rotation.
 *
 * CameraGateGlAnalyzer originally builds its preview/analysis texture coordinates from the camera
 * sensor orientation only. That is correct in portrait, but after the timing activity rotates to
 * landscape the gate overlay follows the display while the GL image keeps the old orientation.
 *
 * This provider updates both mutable FloatBuffers used by the analyzer on the camera thread:
 *   - previewCoords: what the operator sees
 *   - stripCoords: the centre timing strip used for motion timing
 *
 * Updating both is important: rotating only the visible TextureView would make the red gate line
 * disagree with the strip that actually generates the trigger.
 */
class CameraGateRotationSyncProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, Runnable>()
    private val applied = WeakHashMap<Activity, AppliedState>()

    private data class AppliedState(
        val analyzerIdentity: Int,
        val relativeOrientation: Int
    )

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
                syncRotation(activity)
                main.postDelayed(this, POLL_MS)
            }
        }
        jobs[activity] = job
        main.post(job)
    }

    override fun onActivityPaused(activity: Activity) = stop(activity)

    override fun onActivityDestroyed(activity: Activity) {
        stop(activity)
        applied.remove(activity)
    }

    private fun stop(activity: Activity) {
        jobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun syncRotation(activity: CameraGateHighSpeedActivity) {
        val analyzer = readField(activity, "glAnalyzer") ?: return
        val sensorOrientation = readIntField(activity, "sensorOrientation")
        val displayDegrees = when (activity.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relative = normalize(sensorOrientation - displayDegrees)
        val next = AppliedState(System.identityHashCode(analyzer), relative)
        if (applied[activity] == next) return

        val cameraHandler = readField(activity, "cameraHandler") as? Handler ?: return
        applied[activity] = next
        cameraHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (readField(activity, "glAnalyzer") !== analyzer) return@post

            updateLazyFloatBuffer(analyzer, "previewCoords\$delegate", textureCoords(relative, 0f, 1f))
            updateLazyFloatBuffer(analyzer, "stripCoords\$delegate", textureCoords(relative, 0.485f, 0.515f))

            // A 90/180-degree coordinate change produces a huge one-frame image difference.
            // Forget the pre-rotation sample so rotating the phone can never look like a racer.
            writeField(activity, "previousSamples", null)
        }
    }

    private fun updateLazyFloatBuffer(target: Any, delegateField: String, values: FloatArray) {
        val delegate = readField(target, delegateField) as? Lazy<*> ?: return
        val buffer = delegate.value as? FloatBuffer ?: return
        if (buffer.capacity() < values.size) return
        buffer.position(0)
        buffer.put(values)
        buffer.position(0)
    }

    private fun textureCoords(orientation: Int, left: Float, right: Float): FloatArray {
        fun map(u: Float, v: Float): Pair<Float, Float> = when (normalize(orientation)) {
            90 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            270 -> v to (1f - u)
            else -> u to v
        }
        val bl = map(left, 0f)
        val br = map(right, 0f)
        val tl = map(left, 1f)
        val tr = map(right, 1f)
        return floatArrayOf(
            bl.first, bl.second,
            br.first, br.second,
            tl.first, tl.second,
            tr.first, tr.second
        )
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun readIntField(target: Any, name: String): Int = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(target)
    }.getOrDefault(0)

    private fun writeField(target: Any, name: String, value: Any?) {
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
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

    companion object {
        private const val POLL_MS = 120L
    }
}
