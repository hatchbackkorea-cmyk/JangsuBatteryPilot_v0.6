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
import java.util.WeakHashMap

/**
 * Camera Gate broadcast quality governor.
 *
 * The in-app START/CP/FINISH video viewer was intentionally removed. Timing phones publish video
 * only for the PC operator tool. The secondary broadcast encoder is restarted at 1280x720/30fps
 * while the primary timing stream remains untouched.
 *
 * This provider keeps the historical class name so older manifests remain compatible.
 */
class RaceBroadcastCameraProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val retryJobs = WeakHashMap<Activity, Runnable>()
    private val configuredAnalyzers = WeakHashMap<Activity, Any>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        retryJobs.remove(activity)?.let(main::removeCallbacks)
        scheduleQualityCheck(activity, 80L, 0)
    }

    private fun scheduleQualityCheck(activity: CameraGateHighSpeedActivity, delayMs: Long, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) return
        val job = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            if (!force720pBroadcast(activity)) {
                scheduleQualityCheck(activity, RETRY_DELAY_MS, attempt + 1)
            }
        }
        retryJobs[activity] = job
        main.postDelayed(job, delayMs)
    }

    private fun force720pBroadcast(activity: CameraGateHighSpeedActivity): Boolean {
        val analyzer = runCatching {
            activity.javaClass.getDeclaredField("glAnalyzer").apply { isAccessible = true }.get(activity)
        }.getOrNull() ?: return false

        if (configuredAnalyzers[activity] === analyzer) return true

        val cameraHandler = runCatching {
            activity.javaClass.getDeclaredField("cameraHandler").apply { isAccessible = true }.get(activity) as? Handler
        }.getOrNull() ?: return false

        // Mark before posting so multiple lifecycle retries cannot queue duplicate reconfiguration.
        configuredAnalyzers[activity] = analyzer
        cameraHandler.post {
            val ok = runCatching {
                val release = analyzer.javaClass.getDeclaredMethod("releaseBroadcastEncoder").apply { isAccessible = true }
                val start = analyzer.javaClass.getDeclaredMethod(
                    "startBroadcastEncoderSafely",
                    Size::class.java,
                ).apply { isAccessible = true }
                release.invoke(analyzer)
                start.invoke(analyzer, Size(HD_WIDTH, HD_HEIGHT))
            }.isSuccess

            if (!ok) {
                main.post {
                    if (configuredAnalyzers[activity] === analyzer) configuredAnalyzers.remove(activity)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        scheduleQualityCheck(activity, RETRY_DELAY_MS, 0)
                    }
                }
            }
        }
        return true
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        retryJobs.remove(activity)?.let(main::removeCallbacks)
        configuredAnalyzers.remove(activity)
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
        sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val HD_WIDTH = 1280
        private const val HD_HEIGHT = 720
        private const val MAX_ATTEMPTS = 18
        private const val RETRY_DELAY_MS = 180L
    }
}
