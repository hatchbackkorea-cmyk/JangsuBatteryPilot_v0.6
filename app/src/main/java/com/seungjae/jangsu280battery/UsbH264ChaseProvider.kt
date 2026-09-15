package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.WeakHashMap

/** Starts the external Action-camera source only for a CHASE assignment explicitly set to USB H.264. */
class UsbH264ChaseProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val sources = WeakHashMap<Activity, UsbH264ChaseSource>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        val assignment = TimingOperatorStore.current(activity) ?: return
        val usbChase = assignment.role.equals("CHASE", ignoreCase = true) &&
            ChaseVideoInputStore.get(activity) == ChaseVideoInputMode.USB_H264
        if (!usbChase) {
            sources.remove(activity)?.stop()
            return
        }
        val source = sources[activity] ?: UsbH264ChaseSource(activity.applicationContext).also {
            sources[activity] = it
        }
        source.start()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        sources.remove(activity)?.stop()
    }

    override fun onActivityDestroyed(activity: Activity) {
        sources.remove(activity)?.stop()
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
}
