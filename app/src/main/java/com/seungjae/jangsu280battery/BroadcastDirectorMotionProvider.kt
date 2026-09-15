package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import java.lang.reflect.Field
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Local-only approach/group detector for Broadcast Director V2.
 *
 * IMPORTANT: exact timing remains untouched. START/CP/FINISH still use the existing 60/120 FPS
 * narrow timing strip. This provider samples only a tiny 48x27 copy of the already-visible camera
 * preview at ~12 Hz, on the phone, to notice broad scene motion before a rider reaches the timing
 * line. No preview image, pixel data, rider GPS, route position, speed or rank is sent to server.
 * The server receives only ACTIVATE / REQUEST / RELEASE events.
 *
 * Continued local motion refreshes the short broadcast-token lease, so several riders arriving a
 * metre apart stay on the same point camera. A quiet tail releases back to CHASE. CHASE itself does
 * not run motion detection; it only keeps V2 activation alive and remains the default feed.
 */
class BroadcastDirectorMotionProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newFixedThreadPool(2)
    private val monitors = WeakHashMap<Activity, Monitor>()

    private data class Monitor(
        val activity: CameraGateHighSpeedActivity,
        val assignment: TimingOperatorStore.Assignment,
        val textureField: Field,
        val armedField: Field,
        val chase: Boolean,
        var previousLuma: IntArray? = null,
        var bitmap: Bitmap? = null,
        var active: Boolean = false,
        var consecutiveMotion: Int = 0,
        var lastMotionMs: Long = 0L,
        var lastRequestMs: Long = 0L,
        var lastActivateMs: Long = 0L,
        val networkBusy: AtomicBoolean = AtomicBoolean(false),
        var job: Runnable? = null,
    )

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        val assignment = TimingOperatorStore.current(activity) ?: return
        val role = assignment.role.trim().uppercase(Locale.US)
        if (role !in TimingOperatorStore.ROLES) return

        val monitor = runCatching {
            Monitor(
                activity = activity,
                assignment = assignment,
                textureField = field(activity, "textureView"),
                armedField = field(activity, "armed"),
                chase = role == "CHASE",
            )
        }.getOrNull() ?: return
        monitors[activity] = monitor
        activate(monitor)
        schedule(monitor)
    }

    override fun onActivityPaused(activity: Activity) = stop(activity, release = true)
    override fun onActivityDestroyed(activity: Activity) = stop(activity, release = true)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun schedule(m: Monitor) {
        m.job?.let(main::removeCallbacks)
        val job = object : Runnable {
            override fun run() {
                if (m.activity.isFinishing || m.activity.isDestroyed || monitors[m.activity] !== m) {
                    stop(m.activity, release = true)
                    return
                }
                tick(m)
                main.postDelayed(this, if (m.chase) CHASE_TICK_MS else SAMPLE_MS)
            }
        }
        m.job = job
        main.postDelayed(job, if (m.chase) CHASE_TICK_MS else SAMPLE_MS)
    }

    private fun tick(m: Monitor) {
        val now = SystemClock.elapsedRealtime()
        if (now - m.lastActivateMs >= ACTIVATE_REFRESH_MS) activate(m)
        if (m.chase) return

        val role = m.assignment.role.trim().uppercase(Locale.US)
        val isBroadcastOnly = TimingOperatorStore.isBroadcastRole(role)
        val armed = if (isBroadcastOnly) true else readBoolean(m.armedField, m.activity)
        if (!armed) {
            m.previousLuma = null
            m.consecutiveMotion = 0
            if (m.active) release(m, "camera-disarmed")
            return
        }

        val texture = runCatching { m.textureField.get(m.activity) as? TextureView }.getOrNull()
        val motionScore = texture?.takeIf { it.isAvailable }?.let { broadMotionScore(m, it) }
        if (motionScore == null) {
            // Preview may briefly disappear while Camera2 switches session. Do not steal a feed on
            // missing pixels, and do not instantly release an already-live group during that blip.
            m.consecutiveMotion = 0
            if (m.active && now - m.lastMotionMs >= PREVIEW_LOSS_RELEASE_MS) {
                release(m, "preview-unavailable")
            }
            return
        }

        val moving = motionScore >= APPROACH_CHANGED_PERCENT
        if (moving) {
            m.consecutiveMotion = (m.consecutiveMotion + 1).coerceAtMost(8)
            m.lastMotionMs = now
            if (!m.active && m.consecutiveMotion >= REQUIRED_HITS) {
                m.active = true
                request(m, "local-preview-approach")
            } else if (m.active && now - m.lastRequestMs >= REFRESH_MS) {
                request(m, "local-preview-group-hold")
            }
            return
        }

        m.consecutiveMotion = (m.consecutiveMotion - 1).coerceAtLeast(0)
        if (m.active) {
            if (now - m.lastMotionMs >= QUIET_RELEASE_MS) {
                release(m, "local-preview-tail-clear")
            } else if (now - m.lastRequestMs >= REFRESH_MS) {
                // One-metre pack gaps can contain a few quiet preview samples. Keep the feed token
                // alive until the whole tail has been quiet for QUIET_RELEASE_MS.
                request(m, "group-gap-hold")
            }
        }
    }

    /** Percentage of sampled preview pixels that changed meaningfully since the previous sample. */
    private fun broadMotionScore(m: Monitor, texture: TextureView): Double? {
        val bitmap = try {
            val reusable = m.bitmap?.takeIf { !it.isRecycled && it.width == SAMPLE_W && it.height == SAMPLE_H }
                ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888).also { m.bitmap = it }
            texture.getBitmap(reusable) ?: return null
        } catch (_: Throwable) {
            return null
        }

        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
        bitmap.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        val current = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            current[i] = (77 * r + 150 * g + 29 * b) shr 8
        }
        val previous = m.previousLuma
        m.previousLuma = current
        if (previous == null || previous.size != current.size) return 0.0

        var changed = 0
        var usable = 0
        // Ignore a thin edge band where TextureView scaling/cropping can shimmer after rotation.
        for (y in EDGE_Y until SAMPLE_H - EDGE_Y) {
            val row = y * SAMPLE_W
            for (x in EDGE_X until SAMPLE_W - EDGE_X) {
                val i = row + x
                usable++
                if (abs(current[i] - previous[i]) >= PIXEL_DELTA_THRESHOLD) changed++
            }
        }
        return if (usable == 0) 0.0 else changed * 100.0 / usable
    }

    private fun activate(m: Monitor) {
        m.lastActivateMs = SystemClock.elapsedRealtime()
        network.execute {
            runCatching { BroadcastDirectorClient.activate(m.activity.applicationContext, m.assignment) }
        }
    }

    private fun request(m: Monitor, reason: String) {
        m.lastRequestMs = SystemClock.elapsedRealtime()
        if (!m.networkBusy.compareAndSet(false, true)) return
        network.execute {
            try {
                BroadcastDirectorClient.requestLive(m.activity.applicationContext, m.assignment, reason)
            } catch (_: Throwable) {
                // Broadcast-control failure must never affect timing.
            } finally {
                m.networkBusy.set(false)
            }
        }
    }

    private fun release(m: Monitor, reason: String) {
        if (!m.active) return
        m.active = false
        m.consecutiveMotion = 0
        m.lastMotionMs = 0L
        m.lastRequestMs = 0L
        network.execute {
            runCatching { BroadcastDirectorClient.releaseLive(m.activity.applicationContext, m.assignment, reason) }
        }
    }

    private fun stop(activity: Activity, release: Boolean) {
        val m = monitors.remove(activity) ?: return
        m.job?.let(main::removeCallbacks)
        m.job = null
        if (release) release(m, "camera-screen-closed")
        runCatching { m.bitmap?.recycle() }
        m.bitmap = null
        m.previousLuma = null
    }

    private fun field(activity: Activity, name: String): Field =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun readBoolean(field: Field, target: Any): Boolean =
        runCatching { field.getBoolean(target) }.getOrElse {
            runCatching { field.get(target) as? Boolean }.getOrNull() == true
        }

    override fun shutdown() {
        main.removeCallbacksAndMessages(null)
        network.shutdownNow()
        super.shutdown()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        // 48x27 = 1296 pixels. This is intentionally tiny and sampled only ~12.5 times/s so the
        // exact 60/120 FPS timing path never waits on this detector.
        private const val SAMPLE_W = 48
        private const val SAMPLE_H = 27
        private const val SAMPLE_MS = 80L
        private const val CHASE_TICK_MS = 1_000L
        private const val EDGE_X = 2
        private const val EDGE_Y = 1
        private const val PIXEL_DELTA_THRESHOLD = 20
        private const val APPROACH_CHANGED_PERCENT = 1.8
        private const val REQUIRED_HITS = 2
        private const val REFRESH_MS = 800L
        private const val QUIET_RELEASE_MS = 1_250L
        private const val PREVIEW_LOSS_RELEASE_MS = 2_000L
        private const val ACTIVATE_REFRESH_MS = 30_000L
    }
}
