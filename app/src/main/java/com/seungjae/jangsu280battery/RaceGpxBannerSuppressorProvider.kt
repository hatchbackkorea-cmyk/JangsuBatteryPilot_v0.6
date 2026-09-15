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
import java.util.WeakHashMap

/**
 * Keeps the RaceDebugUiInstaller diagnostics but hides its legacy bright GPX banner.
 *
 * The banner view remains in the hierarchy as GONE so the older installer sees its tag and does
 * not recreate it every refresh cycle. The DEBUG button and GPX download/apply pipeline are
 * intentionally untouched.
 */
class RaceGpxBannerSuppressorProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = WeakHashMap<Activity, MutableList<Runnable>>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is RaceActivity) return
        clearJobs(activity)
        val pending = mutableListOf<Runnable>()
        listOf(0L, 250L, 700L, 1_600L).forEach { delay ->
            val job = Runnable { hideBanner(activity) }
            pending += job
            main.postDelayed(job, delay)
        }
        jobs[activity] = pending
    }

    private fun hideBanner(activity: RaceActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG_BANNER)?.visibility = View.GONE
    }

    private fun clearJobs(activity: Activity) {
        jobs.remove(activity)?.forEach(main::removeCallbacks)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = clearJobs(activity)
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
        private const val TAG_BANNER = "timegate_gpx_status_banner_v03449"
    }
}
