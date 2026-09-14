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
import android.widget.Spinner
import android.widget.TextView

/** Locks the Camera Gate role selector when the phone was enrolled by an official operator QR. */
class TimingOperatorLockProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        applyLock(activity)
        main.postDelayed({ applyLock(activity) }, 500L)
        main.postDelayed({ applyLock(activity) }, 1_700L)
    }

    private fun applyLock(activity: CameraGateHighSpeedActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val assignment = TimingOperatorStore.current(activity) ?: return
        val status = findText(activity.window.decorView as? ViewGroup) ?: return
        status.text = "게이트 역할 · 🔒 ${assignment.role} · ${assignment.eventCode} · QR 배정"
        val parent = status.parent as? ViewGroup
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is Spinner) {
                    child.isEnabled = false
                    child.visibility = View.GONE
                }
            }
        }
    }

    private fun findText(root: ViewGroup?): TextView? {
        if (root == null) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && child.text?.toString()?.startsWith("게이트 역할 ·") == true) return child
            if (child is ViewGroup) findText(child)?.let { return it }
        }
        return null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
