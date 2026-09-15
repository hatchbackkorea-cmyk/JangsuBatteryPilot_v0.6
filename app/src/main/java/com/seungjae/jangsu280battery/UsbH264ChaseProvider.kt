package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Owns the USB CHASE foreground service and the local Action-camera preview binding.
 *
 * In USB mode we reuse CameraGateHighSpeedActivity's existing TextureView rather than adding an
 * overlay. The phone Camera2 session is closed and the TextureView listener is replaced while USB
 * mode is active, so the phone camera cannot reclaim the preview surface. Broadcast transport stays
 * in UsbH264ChaseService and is independent from this local preview.
 */
class UsbH264ChaseProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private data class PreviewState(
        val view: TextureView,
        val originalListener: TextureView.SurfaceTextureListener?,
        var decoder: UsbH264PreviewDecoder? = null,
    )

    private val previews = WeakHashMap<CameraGateHighSpeedActivity, PreviewState>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity && activity !is BroadcastCameraEnrollmentActivity) return
        val usbChase = UsbH264ChaseUiMode.active(activity)
        if (usbChase) {
            UsbH264ChaseService.start(activity)
            if (activity is CameraGateHighSpeedActivity) attachUsbPreview(activity)
        } else {
            if (activity is CameraGateHighSpeedActivity) restorePhonePreview(activity)
            UsbH264ChaseService.stop(activity)
        }
    }

    private fun attachUsbPreview(activity: CameraGateHighSpeedActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val existing = previews[activity]
        if (existing != null) {
            closePhoneCamera(activity)
            if (existing.view.isAvailable && existing.decoder == null) {
                existing.view.surfaceTexture?.let { startDecoder(activity, existing, it) }
            }
            return
        }

        val view = readTextureView(activity) ?: return
        val state = PreviewState(view, view.surfaceTextureListener)
        previews[activity] = state

        // Stop the Camera2/GL pipeline first. This does not touch the foreground USB broadcaster.
        closePhoneCamera(activity)

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                closePhoneCamera(activity)
                startDecoder(activity, state, surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopDecoder(state)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        if (view.isAvailable) {
            view.surfaceTexture?.let { startDecoder(activity, state, it) }
        }
        setState(activity, "USB H.264 액션캠 미리보기 준비 중…")
    }

    private fun startDecoder(activity: CameraGateHighSpeedActivity, state: PreviewState, surfaceTexture: SurfaceTexture) {
        stopDecoder(state)
        if (!UsbH264ChaseUiMode.active(activity) || activity.isFinishing || activity.isDestroyed) return
        val decoder = runCatching { UsbH264PreviewDecoder(Surface(surfaceTexture)) }.getOrNull() ?: return
        state.decoder = decoder
        UsbH264PreviewTap.bind(decoder::offer)
        setState(activity, "USB H.264 액션캠 미리보기 · 방송 송출과 분리 동작")
    }

    private fun stopDecoder(state: PreviewState) {
        UsbH264PreviewTap.bind(null)
        state.decoder?.close()
        state.decoder = null
    }

    private fun restorePhonePreview(activity: CameraGateHighSpeedActivity) {
        val state = previews.remove(activity) ?: return
        stopDecoder(state)
        state.view.surfaceTextureListener = state.originalListener
        if (state.view.isAvailable) {
            state.originalListener?.onSurfaceTextureAvailable(
                state.view.surfaceTexture ?: return,
                state.view.width,
                state.view.height,
            )
        }
    }

    private fun closePhoneCamera(activity: CameraGateHighSpeedActivity) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredMethod("closeCamera").apply {
                isAccessible = true
            }.invoke(activity)
        }
    }

    private fun readTextureView(activity: CameraGateHighSpeedActivity): TextureView? = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField("textureView").apply {
            isAccessible = true
        }.get(activity) as? TextureView
    }.getOrNull()

    private fun setState(activity: CameraGateHighSpeedActivity, value: String) {
        runCatching {
            val field = CameraGateHighSpeedActivity::class.java.getDeclaredField("stateText").apply {
                isAccessible = true
            }
            (field.get(activity) as? TextView)?.text = value
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) {
            previews[activity]?.let(::stopDecoder)
            // Do not restore the original phone-camera listener here. Keeping the USB listener in
            // place prevents Camera2 from reopening while the phone is locked/backgrounded.
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) {
            previews.remove(activity)?.let(::stopDecoder)
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
