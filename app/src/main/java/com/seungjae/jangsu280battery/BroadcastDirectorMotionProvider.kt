package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Field
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Local-only approach/group detector for Broadcast Director V2.
 *
 * It observes CameraGateHighSpeedActivity's already-computed frame-difference score on the same
 * phone. No rider GPS or video pixels are sent to the server. A lower, sustained motion threshold
 * requests the broadcast token before the exact timing line trigger; continued movement holds the
 * feed for a pack, and a quiet tail releases it back to CHASE.
 *
 * This is deliberately additive: the timing activity, timing trigger, clock sync, and recording
 * paths are not modified.
 */
class BroadcastDirectorMotionProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newFixedThreadPool(2)
    private val monitors = WeakHashMap<Activity, Monitor>()

    private data class Monitor(
        val activity: CameraGateHighSpeedActivity,
        val assignment: TimingOperatorStore.Assignment,
        val scoreField: Field,
        val thresholdField: Field,
        val armedField: Field,
        var active: Boolean = false,
        var consecutiveMotion: Int = 0,
        var lastMotionMs: Long = 0L,
        var lastRequestMs: Long = 0L,
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
        if (role == "CHASE") return
        if (role !in TimingOperatorStore.ROLES) return

        val monitor = runCatching {
            Monitor(
                activity = activity,
                assignment = assignment,
                scoreField = field(activity, "lastScore"),
                thresholdField = field(activity, "threshold"),
                armedField = field(activity, "armed"),
            )
        }.getOrNull() ?: return
        monitors[activity] = monitor
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
                main.postDelayed(this, SAMPLE_MS)
            }
        }
        m.job = job
        main.post(job)
    }

    private fun tick(m: Monitor) {
        val now = SystemClock.elapsedRealtime()
        val role = m.assignment.role.trim().uppercase(Locale.US)
        val isBroadcastOnly = TimingOperatorStore.isBroadcastRole(role)
        val armed = if (isBroadcastOnly) true else readBoolean(m.armedField, m.activity)
        if (!armed) {
            m.consecutiveMotion = 0
            if (m.active) release(m, "camera-disarmed")
            return
        }

        val score = readDouble(m.scoreField, m.activity)
        val timingThreshold = readDouble(m.thresholdField, m.activity).coerceAtLeast(6.0)
        val approachThreshold = max(MIN_APPROACH_SCORE, timingThreshold * APPROACH_RATIO)
        val moving = score >= approachThreshold

        if (moving) {
            m.consecutiveMotion = (m.consecutiveMotion + 1).coerceAtMost(8)
            m.lastMotionMs = now
            if (!m.active && m.consecutiveMotion >= REQUIRED_HITS) {
                m.active = true
                request(m, "local-motion-approach")
            } else if (m.active && now - m.lastRequestMs >= REFRESH_MS) {
                // Refresh the short server lease while a rider or group remains in this camera.
                request(m, "local-motion-hold")
            }
            return
        }

        m.consecutiveMotion = (m.consecutiveMotion - 1).coerceAtLeast(0)
        if (m.active) {
            if (now - m.lastMotionMs >= QUIET_RELEASE_MS) {
                release(m, "local-motion-tail-clear")
            } else if (now - m.lastRequestMs >= REFRESH_MS) {
                // Keep the token alive through tiny gaps between riders in a close pack.
                request(m, "group-gap-hold")
            }
        }
    }

    private fun request(m: Monitor, reason: String) {
        val now = SystemClock.elapsedRealtime()
        m.lastRequestMs = now
        if (!m.networkBusy.compareAndSet(false, true)) return
        network.execute {
            try {
                BroadcastDirectorClient.requestLive(m.activity.applicationContext, m.assignment, reason)
            } catch (_: Throwable) {
                // Broadcast control failure must never affect camera timing.
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
    }

    private fun field(activity: Activity, name: String): Field =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun readDouble(field: Field, target: Any): Double =
        runCatching { field.getDouble(target) }.getOrElse {
            (runCatching { field.get(target) as? Number }.getOrNull()?.toDouble() ?: 0.0)
        }

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
        private const val SAMPLE_MS = 40L
        private const val REQUIRED_HITS = 2
        private const val APPROACH_RATIO = 0.45
        private const val MIN_APPROACH_SCORE = 5.0
        private const val REFRESH_MS = 900L
        private const val QUIET_RELEASE_MS = 1_650L
    }
}
