package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.WeakHashMap

/**
 * Keeps the official Camera Gate screen from being closed accidentally during timing.
 *
 * Back button / back gesture is consumed while CameraGateHighSpeedActivity is active.
 * The operator can still press Home and return to the same task, and explicitly dismiss the app
 * from Android Recents when timing is actually finished. Administrative device revocation still
 * calls Activity.finish() directly and is therefore not blocked by this guard.
 */
class CameraGateStayOpenProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val callbacks = WeakHashMap<Activity, OnBackInvokedCallback>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is CameraGateHighSpeedActivity) return
        protect(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        protect(activity)
    }

    private fun protect(activity: CameraGateHighSpeedActivity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !callbacks.containsKey(activity)) {
            val callback = OnBackInvokedCallback {
                // Intentionally consume system Back. Timing ends only through an explicit task close
                // (or an administrator revoking this timing device).
            }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback
            )
            callbacks[activity] = callback
        }

        // Legacy/hardware Back fallback. On modern Android the OnBackInvoked callback above is the
        // authoritative path; this also covers devices/keyboards that still dispatch KEYCODE_BACK.
        activity.window.decorView.apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _: View, keyCode: Int, event: KeyEvent ->
                keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
            }
            requestFocus()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbacks.remove(activity)?.let { callback ->
                runCatching {
                    activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
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
