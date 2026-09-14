package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.WeakHashMap

/** Lightweight handoff between the GL camera thread and the network publisher. */
object CameraGateBroadcastBridge {
    @Volatile private var streamer: CameraGateBroadcastStreamer? = null

    fun bind(value: CameraGateBroadcastStreamer?) {
        streamer = value
    }

    fun offer(packet: CameraGateBroadcastPacket) {
        streamer?.offer(packet)
    }
}

/** Creates a publisher only for a QR-assigned official timing phone. */
class CameraGateBroadcastProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val streamers = WeakHashMap<Activity, CameraGateBroadcastStreamer>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        val assignment = TimingOperatorStore.current(activity)
        if (assignment == null) {
            CameraGateBroadcastBridge.bind(null)
            return
        }
        val existing = streamers[activity]
        if (existing != null) {
            CameraGateBroadcastBridge.bind(existing)
            return
        }
        val streamer = CameraGateBroadcastStreamer(activity.applicationContext, assignment)
        streamers[activity] = streamer
        CameraGateBroadcastBridge.bind(streamer)
    }

    override fun onActivityPaused(activity: Activity) {
        // Keep the socket object through a short pause/rotation. Camera frames simply stop while the
        // underlying camera session is unavailable and resume from the next key frame.
        if (activity is CameraGateHighSpeedActivity) CameraGateBroadcastBridge.bind(streamers[activity])
    }

    override fun onActivityDestroyed(activity: Activity) {
        streamers.remove(activity)?.close()
        if (activity is CameraGateHighSpeedActivity) CameraGateBroadcastBridge.bind(null)
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
