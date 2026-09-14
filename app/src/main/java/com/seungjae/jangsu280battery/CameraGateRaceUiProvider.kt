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
 * v8 refreshes the clock every 15 seconds while the Camera Gate screen is visible. The shorter
 * interval gives the drift model more observations before and during a race, while syncClock's own
 * guard prevents overlapping network probes. CameraGateFrameClock v8 handles 60/120 FPS crossing
 * time with a midpoint estimate between consecutive trusted frames.
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
            activity.window.decorView.post { markV8(activity.window.decorView) }
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
        // onCreate already performs the first sync; automatic discipline starts 15 s later.
        main.postDelayed(job, AUTO_SYNC_INTERVAL_MS)
    }

    private fun stopClockDiscipline(activity: Activity) {
        autoSyncJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun invokeSyncClock(activity: CameraGateHighSpeedActivity) {
        runCatching {
            val method = CameraGateHighSpeedActivity::class.java.getDeclaredMethod("syncClock")
            method.isAccessible = true
            method.invoke(activity)
        }
    }

    private fun markV8(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v6")) {
                view.text = text.replace("CAMERA GATE BETA v6", "CAMERA GATE BETA v8")
            } else if (text.contains("CAMERA GATE BETA v7")) {
                view.text = text.replace("CAMERA GATE BETA v7", "CAMERA GATE BETA v8")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV8(view.getChildAt(i))
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
        private const val AUTO_SYNC_INTERVAL_MS = 15_000L
    }
}
