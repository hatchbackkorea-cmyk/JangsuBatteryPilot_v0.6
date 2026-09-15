package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.WeakHashMap

/** Lightweight handoff between camera sources and the active network/local transports. */
object CameraGateBroadcastBridge {
    @Volatile private var fallbackStreamer: CameraGateBroadcastStreamer? = null
    @Volatile private var webRtcStreamer: CameraGateWebRtcStreamer? = null
    @Volatile private var localSink: CameraGateBroadcastPacketSink? = null
    @Volatile private var keyFrameRequester: (() -> Unit)? = null
    @Volatile private var publisherAvailabilityController: ((Boolean) -> Unit)? = null
    @Volatile private var latestCsd0: CameraGateBroadcastPacket? = null
    @Volatile private var latestCsd1: CameraGateBroadcastPacket? = null
    @Volatile private var externalSourceRequired = false
    @Volatile private var externalAvailable = false

    fun bindFallback(value: CameraGateBroadcastStreamer?) {
        fallbackStreamer = value
        if (value != null) {
            latestCsd0?.let(value::offer)
            latestCsd1?.let(value::offer)
        }
    }

    fun bindWebRtc(value: CameraGateWebRtcStreamer?) {
        webRtcStreamer = value
        if (value != null) {
            latestCsd0?.let(value::offer)
            latestCsd1?.let(value::offer)
        }
    }

    fun bindLocalSink(value: CameraGateBroadcastPacketSink?) {
        localSink = value
        if (value != null) {
            latestCsd0?.let(value::offer)
            latestCsd1?.let(value::offer)
        }
    }

    /** Warm-standby promotion may ask the phone encoder for an IDR. USB H.264 owns its own GOP. */
    fun bindKeyFrameRequester(value: (() -> Unit)?) {
        keyFrameRequester = value
    }

    fun requestKeyFrame() {
        if (!externalSourceRequired) runCatching { keyFrameRequester?.invoke() }
    }

    fun bindPublisherAvailabilityController(value: ((Boolean) -> Unit)?) {
        publisherAvailabilityController = value
    }

    /** USB mode suppresses every encoded frame produced by the phone camera. */
    fun setExternalSourceRequired(required: Boolean) {
        if (externalSourceRequired == required) return
        externalSourceRequired = required
        latestCsd0 = null
        latestCsd1 = null
        if (!required) {
            externalAvailable = false
            publisherAvailabilityController?.invoke(true)
        }
    }

    fun setExternalAvailable(available: Boolean) {
        externalAvailable = available
        if (externalSourceRequired) publisherAvailabilityController?.invoke(available)
    }

    fun isExternalSourceRequired(): Boolean = externalSourceRequired
    fun isExternalAvailable(): Boolean = externalAvailable

    /** Existing internal camera/MediaCodec path. */
    fun offer(packet: CameraGateBroadcastPacket) {
        if (externalSourceRequired) return
        offerAccepted(packet)
    }

    /** Pre-encoded UVC H.264 path. No decode or re-encode occurs here. */
    fun offerExternal(packet: CameraGateBroadcastPacket) {
        if (!externalSourceRequired) return
        offerAccepted(packet)
    }

    private fun offerAccepted(packet: CameraGateBroadcastPacket) {
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
 * Activity-owned publisher for the normal phone-camera path.
 *
 * USB_H264 CHASE is deliberately excluded here because UsbH264ChaseService owns that source and its
 * publisher independently from the Activity lifecycle. That prevents onPause/onDestroy from
 * clearing the background CHASE transport when the phone locks or the user navigates away.
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
            clearActivityOwnedBindings(activity)
            return
        }
        assignments[activity] = assignment

        if (usesUsbChase(activity, assignment)) {
            closeActivityStreamers(activity, unbind = false)
            CameraGateBroadcastBridge.setExternalSourceRequired(true)
            UsbH264ChaseService.start(activity)
            return
        }

        UsbH264ChaseService.stop(activity)
        CameraGateBroadcastBridge.bindPublisherAvailabilityController(null)
        CameraGateBroadcastBridge.setExternalSourceRequired(false)
        ensurePublishers(activity)
    }

    private fun usesUsbChase(activity: Activity, assignment: TimingOperatorStore.Assignment): Boolean =
        assignment.role.equals("CHASE", ignoreCase = true) &&
            ChaseVideoInputStore.get(activity) == ChaseVideoInputMode.USB_H264

    private fun ensurePublishers(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val assignment = assignments[activity] ?: TimingOperatorStore.current(activity) ?: return
        val rtc = webRtcStreamers[activity] ?: CameraGateWebRtcStreamer(
            activity.applicationContext,
            assignment,
        ) {
            activity.runOnUiThread { ensureFallback(activity) }
        }.also { webRtcStreamers[activity] = it }

        CameraGateBroadcastBridge.bindWebRtc(rtc)
        CameraGateBroadcastBridge.bindFallback(fallbackStreamers[activity])
    }

    private fun closeActivityStreamers(activity: Activity, unbind: Boolean) {
        fallbackStreamers.remove(activity)?.close()
        webRtcStreamers.remove(activity)?.close()
        if (unbind) {
            CameraGateBroadcastBridge.bindFallback(null)
            CameraGateBroadcastBridge.bindWebRtc(null)
        }
    }

    private fun ensureFallback(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val assignment = assignments[activity] ?: TimingOperatorStore.current(activity) ?: return
        if (usesUsbChase(activity, assignment)) return
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
        val assignment = assignments[activity] ?: TimingOperatorStore.current(activity)
        if (assignment != null && usesUsbChase(activity, assignment)) return
        CameraGateBroadcastBridge.bindWebRtc(webRtcStreamers[activity])
        CameraGateBroadcastBridge.bindFallback(fallbackStreamers[activity])
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        val assignment = assignments.remove(activity) ?: TimingOperatorStore.current(activity)
        val usbOwned = assignment != null && usesUsbChase(activity, assignment)
        if (usbOwned) {
            closeActivityStreamers(activity, unbind = false)
            CameraGateBroadcastBridge.bindLocalSink(null)
            CameraGateBroadcastBridge.bindKeyFrameRequester(null)
            return
        }
        clearActivityOwnedBindings(activity)
    }

    private fun clearActivityOwnedBindings(activity: Activity) {
        closeActivityStreamers(activity, unbind = true)
        assignments.remove(activity)
        CameraGateBroadcastBridge.bindLocalSink(null)
        CameraGateBroadcastBridge.bindKeyFrameRequester(null)
        CameraGateBroadcastBridge.bindPublisherAvailabilityController(null)
        CameraGateBroadcastBridge.setExternalSourceRequired(false)
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
