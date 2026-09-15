package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.WeakHashMap

/** Lightweight handoff between the camera source and the active network/local transports. */
object CameraGateBroadcastBridge {
    @Volatile private var fallbackStreamer: CameraGateBroadcastStreamer? = null
    @Volatile private var webRtcStreamer: CameraGateWebRtcStreamer? = null
    @Volatile private var localSink: CameraGateBroadcastPacketSink? = null
    @Volatile private var keyFrameRequester: (() -> Unit)? = null
    @Volatile private var latestCsd0: CameraGateBroadcastPacket? = null
    @Volatile private var latestCsd1: CameraGateBroadcastPacket? = null

    fun bindFallback(value: CameraGateBroadcastStreamer?) {
        fallbackStreamer = value
    }

    fun bindWebRtc(value: CameraGateWebRtcStreamer?) {
        webRtcStreamer = value
    }

    fun bindLocalSink(value: CameraGateBroadcastPacketSink?) {
        localSink = value
        if (value != null) {
            latestCsd0?.let(value::offer)
            latestCsd1?.let(value::offer)
        }
    }

    /** Warm-standby viewer promotion asks the existing encoder for an IDR immediately when supported. */
    fun bindKeyFrameRequester(value: (() -> Unit)?) {
        keyFrameRequester = value
    }

    fun requestKeyFrame() {
        runCatching { keyFrameRequester?.invoke() }
    }

    fun offer(packet: CameraGateBroadcastPacket) {
        when (packet.kind) {
            CameraGateBroadcastStreamer.KIND_CSD0 -> latestCsd0 = packet
            CameraGateBroadcastStreamer.KIND_CSD1 -> latestCsd1 = packet
        }
        localSink?.offer(packet)
        webRtcStreamer?.offer(packet)
        fallbackStreamer?.offer(packet)
    }
}

/**
 * Creates a P2P WebRTC publisher for the active TimeGate camera source.
 *
 * CameraGateHighSpeedActivity is the original phone-camera source. UsbChaseCameraActivity is an
 * experimental UVC source that already emits H.264 into CameraGateBroadcastBridge. Both therefore
 * share exactly the same WebRTC/fallback transports.
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

    private fun isCameraSource(activity: Activity): Boolean =
        activity is CameraGateHighSpeedActivity || activity is UsbChaseCameraActivity

    override fun onActivityResumed(activity: Activity) {
        if (!isCameraSource(activity)) return
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
        if (!isCameraSource(activity)) return
        CameraGateBroadcastBridge.bindWebRtc(webRtcStreamers[activity])
        CameraGateBroadcastBridge.bindFallback(fallbackStreamers[activity])
    }

    override fun onActivityDestroyed(activity: Activity) {
        fallbackStreamers.remove(activity)?.close()
        webRtcStreamers.remove(activity)?.close()
        assignments.remove(activity)
        if (isCameraSource(activity)) {
            CameraGateBroadcastBridge.bindFallback(null)
            CameraGateBroadcastBridge.bindWebRtc(null)
            CameraGateBroadcastBridge.bindLocalSink(null)
            CameraGateBroadcastBridge.bindKeyFrameRequester(null)
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
