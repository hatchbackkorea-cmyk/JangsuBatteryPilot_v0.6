package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.WeakHashMap

/**
 * Starts/stops the long-lived USB CHASE foreground service based on the active assignment.
 *
 * The service intentionally survives Activity pause/destroy so screen-off and app navigation do not
 * interrupt the Action-camera stream. While CameraGateHighSpeedActivity is visible, this provider
 * overlays a second TextureView and decodes the same USB H.264 stream locally so the operator sees
 * the Action camera rather than the phone camera. The official broadcast remains pass-through H.264.
 */
class UsbH264ChaseProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private data class PreviewState(
        val view: TextureView,
        var renderer: UsbH264LocalPreviewRenderer? = null,
    )

    private val previews = WeakHashMap<Activity, PreviewState>()

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

        if (usbChase) {
            UsbH264ChaseService.start(activity)
            if (activity is CameraGateHighSpeedActivity) attachPreview(activity)
        } else {
            if (activity is CameraGateHighSpeedActivity) detachPreview(activity)
            UsbH264ChaseService.stop(activity)
        }
    }

    private fun attachPreview(activity: CameraGateHighSpeedActivity) {
        if (activity.isFinishing || activity.isDestroyed || previews.containsKey(activity)) return
        val original = runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredField("textureView").apply { isAccessible = true }
                .get(activity) as? TextureView
        }.getOrNull() ?: return
        val parent = original.parent as? ViewGroup ?: return

        val preview = TextureView(activity).apply {
            isOpaque = true
            setBackgroundColor(Color.BLACK)
            contentDescription = "USB 액션캠 로컬 미리보기"
        }
        val state = PreviewState(preview)
        previews[activity] = state

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                startRenderer(state, surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                stopRenderer(state)
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }

        val index = (parent.indexOfChild(original) + 1).coerceIn(0, parent.childCount)
        parent.addView(
            preview,
            index,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        if (preview.isAvailable) {
            preview.surfaceTexture?.let { startRenderer(state, it) }
        }
    }

    private fun startRenderer(state: PreviewState, surfaceTexture: SurfaceTexture) {
        stopRenderer(state)
        val renderer = UsbH264LocalPreviewRenderer(Surface(surfaceTexture))
        state.renderer = renderer
        UsbH264LocalPreviewBridge.bind(renderer::offer)
    }

    private fun stopRenderer(state: PreviewState) {
        UsbH264LocalPreviewBridge.bind(null)
        state.renderer?.close()
        state.renderer = null
    }

    private fun detachPreview(activity: Activity) {
        val state = previews.remove(activity) ?: return
        stopRenderer(state)
        runCatching { (state.view.parent as? ViewGroup)?.removeView(state.view) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) detachPreview(activity)
        // Do not stop UsbH264ChaseService here. Screen-off must keep the official CHASE stream alive.
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) detachPreview(activity)
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
