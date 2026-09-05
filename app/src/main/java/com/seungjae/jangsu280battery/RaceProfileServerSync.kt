package com.seungjae.jangsu280battery

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Keeps the already-joined server participant row aligned with the profile saved on the phone.
 *
 * Normal path: authenticated participant-profile update.
 * Recovery path: if that token became stale (for example after a re-join), re-join the same event
 * with the persistent profileId, refresh name/nickname/bib on the server, receive a fresh token,
 * and save that token locally. This prevents the phone from showing the new profile while the
 * RACE admin page remains stuck on the old participant information.
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
            val store = RaceDataStore(activity)
            val joined = if (client.available()) store.lastJoined(client.baseUrl()) else null

            if (profile.isReady && joined != null && joined.token.isNotBlank()) {
                val signature = listOf(
                    client.baseUrl(), joined.config.eventCode,
                    profile.profileId, profile.name, profile.nickname, profile.bib
                ).joinToString("|")
                val now = System.currentTimeMillis()
                val canRetry = signature != lastFailed || now - lastFailedAt >= 4_000L

                if (!inFlight && signature != lastSynced && canRetry) {
                    inFlight = true
                    Thread {
                        val direct = runCatching {
                            client.updateParticipantProfile(joined.config.eventCode, joined.token, profile)
                        }

                        val ok = if (direct.isSuccess) {
                            true
                        } else {
                            // The most common reason here is an old participant token. Re-joining the
                            // same event updates the server row by persistent profileId and returns a
                            // fresh token. If the event is not currently joinable this simply fails and
                            // the normal retry loop will try the authenticated path again later.
                            runCatching {
                                val refreshed = client.join(joined.config.eventCode, profile)
                                store.saveJoined(
                                    refreshed.config,
                                    refreshed.participantToken,
                                    joined.localCourseId,
                                    client.baseUrl()
                                )
                            }.isSuccess
                        }

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
        // Re-confirm server identity on every RACE screen resume. This also repairs profiles that
        // were edited before this version was installed.
        lastSynced = ""
        lastFailed = ""
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 150L)
    }

    fun pause(activity: RaceActivity) {
        if (activityRef.get() === activity) {
            handler.removeCallbacks(tick)
            activityRef.clear()
        }
    }
}
