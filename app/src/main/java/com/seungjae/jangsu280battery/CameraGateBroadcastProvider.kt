package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.WeakHashMap

/** Lightweight handoff between the GL camera thread and the active network transports. */
object CameraGateBroadcastBridge {
    @Volatile private var fallbackStreamer: CameraGateBroadcastStreamer? = null
    @Volatile private var webRtcStreamer: CameraGateWebRtcStreamer? = null

    fun bindFallback(value: CameraGateBroadcastStreamer?) {
        fallbackStreamer = value
    }

    fun bindWebRtc(value: CameraGateWebRtcStreamer?) {
        webRtcStreamer = value
    }

    fun offer(packet: CameraGateBroadcastPacket) {
        webRtcStreamer?.offer(packet)
        fallbackStreamer?.offer(packet)
    }
}

/**
 * Creates a P2P WebRTC publisher for a QR-assigned timing phone.
 *
 * The older WebSocket video publisher is not started during normal operation. It is created only
 * when a WebRTC viewer explicitly requests fallback or P2P establishment times out, avoiding the
 * previous duplicate upload and keeping the phone's uplink focused on the direct P2P stream.
 */
class CameraGateBroadcastProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val fallbackStreamers = WeakHashMap<Activity, CameraGateBroadcastStreamer>()
    private val webRtcStreamers = WeakHashMap<Activity, CameraGateWebRtcStreamer>()
    private val assignments = WeakHashMap<Activity, TimingOperatorStore.Assignment>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        val assignment = TimingOperatorStore.current(activity)
        if (assignment == null) {
            CameraGateBroadcastBridge.bindFallback(null)
            CameraGateBroadcastBridge.bindWebRtc(null)
            return
        }
        assignments[activity] = assignment

        val rtc = webRtcStreamers[activity] ?: CameraGateWebRtcStreamer(
            activity.applicationContext,
            assignment,
        ) {
            activity.runOnUiThread { ensureFallback(activity) }
        }.also { webRtcStreamers[activity] = it }

        CameraGateBroadcastBridge.bindWebRtc(rtc)
        CameraGateBroadcastBridge.bindFallback(fallbackStreamers[activity])
    }

    private fun ensureFallback(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val assignment = assignments[activity] ?: TimingOperatorStore.current(activity) ?: return
        val existing = fallbackStreamers[activity]
        if (existing != null) {
            CameraGateBroadcastBridge.bindFallback(existing)
            return
        }
        val streamer = CameraGateBroadcastStreamer(activity.applicationContext, assignment)
        fallbackStreamers[activity] = streamer
        CameraGateBroadcastBridge.bindFallback(streamer)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        CameraGateBroadcastBridge.bindWebRtc(webRtcStreamers[activity])
        CameraGateBroadcastBridge.bindFallback(fallbackStreamers[activity])
    }

    override fun onActivityDestroyed(activity: Activity) {
        fallbackStreamers.remove(activity)?.close()
        webRtcStreamers.remove(activity)?.close()
        assignments.remove(activity)
        if (activity is CameraGateHighSpeedActivity) {
            CameraGateBroadcastBridge.bindFallback(null)
            CameraGateBroadcastBridge.bindWebRtc(null)
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
}
