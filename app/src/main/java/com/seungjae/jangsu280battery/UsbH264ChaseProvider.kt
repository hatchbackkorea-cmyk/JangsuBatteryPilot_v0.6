package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Starts/stops the long-lived USB CHASE foreground service based on the active assignment.
 * The service intentionally survives Activity pause/destroy so screen-off and app navigation do not
 * interrupt the Action-camera stream.
 */
class UsbH264ChaseProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity && activity !is BroadcastCameraEnrollmentActivity) return
        val assignment = TimingOperatorStore.current(activity)
        val usbChase = assignment != null &&
            assignment.role.equals("CHASE", ignoreCase = true) &&
            ChaseVideoInputStore.get(activity) == ChaseVideoInputMode.USB_H264
        if (usbChase) UsbH264ChaseService.start(activity)
        else UsbH264ChaseService.stop(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
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
