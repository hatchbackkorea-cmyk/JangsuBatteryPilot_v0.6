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
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Registers the Camera Gate RACE-home injector and keeps Camera Gate clocks disciplined.
 *
 * While the Camera Gate beta screen is visible, v7 asks the existing sync routine to refresh every
 * 30 seconds. CameraGateClockSync then uses those repeated observations to learn each phone's
 * monotonic-clock drift, keeping START and FINISH phones on the same time axis during long races.
 */
class CameraGateRaceUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val autoSyncJobs = WeakHashMap<Activity, Runnable>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is RaceActivity) {
            activity.window.decorView.post { CameraGateRaceUiInstaller.install(activity) }
            return
        }
        if (activity is CameraGateHighSpeedActivity) {
            activity.window.decorView.post { markV7(activity.window.decorView) }
            startClockDiscipline(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        stopClockDiscipline(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stopClockDiscipline(activity)
        if (activity is RaceActivity) CameraGateRaceUiInstaller.uninstall(activity)
    }

    private fun startClockDiscipline(activity: CameraGateHighSpeedActivity) {
        stopClockDiscipline(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    autoSyncJobs.remove(activity)
                    return
                }
                invokeSyncClock(activity)
                main.postDelayed(this, AUTO_SYNC_INTERVAL_MS)
            }
        }
        autoSyncJobs[activity] = job
        // onCreate already performs the first sync; the first automatic refresh happens 30 s later.
        main.postDelayed(job, AUTO_SYNC_INTERVAL_MS)
    }

    private fun stopClockDiscipline(activity: Activity) {
        autoSyncJobs.remove(activity)?.let(main::removeCallbacks)
    }

    private fun invokeSyncClock(activity: CameraGateHighSpeedActivity) {
        runCatching {
            val method = CameraGateHighSpeedActivity::class.java.getDeclaredMethod("syncClock")
            method.isAccessible = true
            method.invoke(activity)
        }
    }

    private fun markV7(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v6")) {
                view.text = text.replace("CAMERA GATE BETA v6", "CAMERA GATE BETA v7")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV7(view.getChildAt(i))
        }
    }

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
        private const val AUTO_SYNC_INTERVAL_MS = 30_000L
    }
}
