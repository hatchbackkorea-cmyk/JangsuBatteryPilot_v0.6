package com.seungjae.jangsu280battery

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Keeps the already-joined server participant row aligned with the profile saved on the phone.
 * This is intentionally lightweight: a successful profile is sent once per Activity resume and
 * again only when name/nickname/bib changes. Network failures are retried later without blocking UI.
 */
object RaceProfileServerSync {
    private val handler = Handler(Looper.getMainLooper())
    private var activityRef = WeakReference<RaceActivity>(null)
    @Volatile private var inFlight = false
    @Volatile private var lastSynced = ""
    @Volatile private var lastFailed = ""
    @Volatile private var lastFailedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val activity = activityRef.get() ?: return
            val profile = RaceProfileStore.profile(activity)
            val client = RaceServerClient(activity)
            val joined = if (client.available()) RaceDataStore(activity).lastJoined(client.baseUrl()) else null
            if (profile.isReady && joined != null && joined.token.isNotBlank()) {
                val signature = listOf(client.baseUrl(), joined.config.eventCode, profile.name, profile.nickname, profile.bib).joinToString("|")
                val now = System.currentTimeMillis()
                val canRetry = signature != lastFailed || now - lastFailedAt >= 4_000L
                if (!inFlight && signature != lastSynced && canRetry) {
                    inFlight = true
                    Thread {
                        val ok = runCatching {
                            client.updateParticipantProfile(joined.config.eventCode, joined.token, profile)
                        }.isSuccess
                        if (ok) {
                            lastSynced = signature
                            lastFailed = ""
                        } else {
                            lastFailed = signature
                            lastFailedAt = System.currentTimeMillis()
                        }
                        inFlight = false
                    }.start()
                }
            }
            handler.postDelayed(this, 800L)
        }
    }

    fun resume(activity: RaceActivity) {
        activityRef = WeakReference(activity)
        lastSynced = "" // Re-confirm server state whenever the RACE screen comes back.
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 250L)
    }

    fun pause(activity: RaceActivity) {
        if (activityRef.get() === activity) {
            handler.removeCallbacks(tick)
            activityRef.clear()
        }
    }
}
