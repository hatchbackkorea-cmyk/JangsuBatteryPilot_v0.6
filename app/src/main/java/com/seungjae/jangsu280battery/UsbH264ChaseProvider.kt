package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Owns the USB CHASE foreground service and the local Action-camera preview binding.
 *
 * USB CHASE reuses CameraGateHighSpeedActivity's existing TextureView. The phone Camera2 session is
 * closed and cannot reclaim the surface while USB mode is active. The local decoder is only a
 * preview tap; the official broadcast remains direct H.264 pass-through in UsbH264ChaseService.
 */
class UsbH264ChaseProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private data class UiSnapshot(
        val view: View,
        val visibility: Int,
        val text: CharSequence? = (view as? TextView)?.text,
    )

    private data class PreviewState(
        val view: TextureView,
        val originalListener: TextureView.SurfaceTextureListener?,
        val uiSnapshots: MutableList<UiSnapshot> = mutableListOf(),
        var decoder: UsbH264PreviewDecoder? = null,
        var videoWidth: Int = 1280,
        var videoHeight: Int = 720,
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
            configureAction5Ui(activity, existing)
            applyFitCenter(existing)
            if (existing.view.isAvailable && existing.decoder == null) {
                existing.view.surfaceTexture?.let { startDecoder(activity, existing, it) }
            }
            return
        }

        val view = readTextureView(activity) ?: return
        val state = PreviewState(view, view.surfaceTextureListener)
        previews[activity] = state

        closePhoneCamera(activity)
        configureAction5Ui(activity, state)
        view.setBackgroundColor(Color.BLACK)

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                closePhoneCamera(activity)
                applyFitCenter(state)
                startDecoder(activity, state, surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyFitCenter(state)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopDecoder(state)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        applyFitCenter(state)
        if (view.isAvailable) {
            view.surfaceTexture?.let { startDecoder(activity, state, it) }
        }
        setAction5Status(activity, state, live = UsbH264ChaseRuntime.streaming)
    }

    private fun startDecoder(activity: CameraGateHighSpeedActivity, state: PreviewState, surfaceTexture: SurfaceTexture) {
        stopDecoder(state)
        if (!UsbH264ChaseUiMode.active(activity) || activity.isFinishing || activity.isDestroyed) return

        val outputSurface = runCatching { Surface(surfaceTexture) }.getOrNull() ?: return
        val decoder = runCatching {
            UsbH264PreviewDecoder(outputSurface) { width, height ->
                state.videoWidth = width.coerceAtLeast(1)
                state.videoHeight = height.coerceAtLeast(1)
                state.view.post {
                    if (activity.isFinishing || activity.isDestroyed || previews[activity] !== state) return@post
                    runCatching { surfaceTexture.setDefaultBufferSize(state.videoWidth, state.videoHeight) }
                    applyFitCenter(state)
                    setAction5Status(activity, state, live = true)
                }
            }
        }.getOrElse {
            runCatching { outputSurface.release() }
            return
        }
        state.decoder = decoder
        UsbH264PreviewTap.bind(decoder::offer)
        setAction5Status(activity, state, live = UsbH264ChaseRuntime.streaming)
    }

    /**
     * TextureView normally stretches its decoded buffer to the whole view. In portrait that makes a
     * 16:9 Action-camera picture look zoomed/cropped. Scale only the overflowing axis down so the
     * entire Action 5 field of view remains visible, centered, with black letterbox space.
     */
    private fun applyFitCenter(state: PreviewState) {
        val view = state.view
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        val videoWidth = state.videoWidth.toFloat()
        val videoHeight = state.videoHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0f || videoHeight <= 0f) return

        val viewAspect = viewWidth / viewHeight
        val videoAspect = videoWidth / videoHeight
        val matrix = Matrix()
        if (videoAspect > viewAspect) {
            val fittedHeight = viewWidth / videoAspect
            val scaleY = (fittedHeight / viewHeight).coerceIn(0.01f, 1f)
            matrix.setScale(1f, scaleY, viewWidth / 2f, viewHeight / 2f)
        } else if (videoAspect < viewAspect) {
            val fittedWidth = viewHeight * videoAspect
            val scaleX = (fittedWidth / viewWidth).coerceIn(0.01f, 1f)
            matrix.setScale(scaleX, 1f, viewWidth / 2f, viewHeight / 2f)
        }
        view.setTransform(matrix)
    }

    private fun stopDecoder(state: PreviewState) {
        UsbH264PreviewTap.bind(null)
        state.decoder?.close()
        state.decoder = null
    }

    private fun restorePhonePreview(activity: CameraGateHighSpeedActivity) {
        val state = previews.remove(activity) ?: return
        stopDecoder(state)
        state.view.setTransform(Matrix())
        restoreUi(state)
        state.view.surfaceTextureListener = state.originalListener
        if (state.view.isAvailable) {
            state.originalListener?.onSurfaceTextureAvailable(
                state.view.surfaceTexture ?: return,
                state.view.width,
                state.view.height,
            )
        }
    }

    /** USB CHASE is an Action 5 operator screen, not a phone Camera2/timing screen. */
    private fun configureAction5Ui(activity: CameraGateHighSpeedActivity, state: PreviewState) {
        if (state.uiSnapshots.isEmpty()) {
            snapshotAndHideField(activity, state, "overlay")
            snapshotAndHideParentField(activity, state, "armButton")
            snapshotAndHideParentField(activity, state, "thresholdText")
            listOf(
                "phoneClockText",
                "correctedClockText",
                "syncText",
                "fpsText",
                "scoreText",
                "triggerText",
                "logText",
            ).forEach { snapshotAndHideField(activity, state, it) }

            findTextView(activity.window.decorView) { text -> text.contains("CAMERA GATE BETA") }?.let { title ->
                snapshot(state, title)
                title.text = "ACTION 5 · USB H.264 CHASE"
            }
            findTextView(activity.window.decorView) { text -> text.contains("120 FPS 녹화스트림") }?.let { explanation ->
                snapshot(state, explanation)
                explanation.visibility = View.GONE
            }
        }
        setAction5Status(activity, state, live = UsbH264ChaseRuntime.streaming)
    }

    private fun setAction5Status(activity: CameraGateHighSpeedActivity, state: PreviewState, live: Boolean) {
        val prefix = if (live) "ACTION 5 · USB H.264 LIVE" else "ACTION 5 · USB H.264 연결 중"
        val value = "$prefix · ${state.videoWidth}×${state.videoHeight}\n전체 화각 미리보기 · 화각/해상도/녹화는 액션캠에서 설정"
        setState(activity, value)
    }

    private fun snapshotAndHideField(activity: CameraGateHighSpeedActivity, state: PreviewState, fieldName: String) {
        readViewField(activity, fieldName)?.let { view ->
            snapshot(state, view)
            view.visibility = View.GONE
        }
    }

    private fun snapshotAndHideParentField(activity: CameraGateHighSpeedActivity, state: PreviewState, fieldName: String) {
        val fieldView = readViewField(activity, fieldName) ?: return
        val target = (fieldView.parent as? View) ?: fieldView
        snapshot(state, target)
        target.visibility = View.GONE
    }

    private fun snapshot(state: PreviewState, view: View) {
        if (state.uiSnapshots.any { it.view === view }) return
        state.uiSnapshots += UiSnapshot(view)
    }

    private fun restoreUi(state: PreviewState) {
        state.uiSnapshots.asReversed().forEach { saved ->
            saved.view.visibility = saved.visibility
            if (saved.view is TextView && saved.text != null) saved.view.text = saved.text
        }
        state.uiSnapshots.clear()
    }

    private fun findTextView(root: View, predicate: (String) -> Boolean): TextView? {
        if (root is TextView && predicate(root.text?.toString().orEmpty())) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun closePhoneCamera(activity: CameraGateHighSpeedActivity) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredMethod("closeCamera").apply {
                isAccessible = true
            }.invoke(activity)
        }
    }

    private fun readTextureView(activity: CameraGateHighSpeedActivity): TextureView? =
        readViewField(activity, "textureView") as? TextureView

    private fun readViewField(activity: CameraGateHighSpeedActivity, name: String): View? = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(activity) as? View
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
            // Never restore/open the phone camera while screen-off/backgrounded. The foreground USB
            // service continues broadcasting the Action 5 stream independently.
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
