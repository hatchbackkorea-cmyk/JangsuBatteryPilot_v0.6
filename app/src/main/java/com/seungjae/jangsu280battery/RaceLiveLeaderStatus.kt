package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Lightweight event course-leader refresh used while the phone live timing HUD is visible. */
object RaceLiveLeaderStatus {
    data class Status(
        val eventCode: String,
        val leaderBib: String,
        val leaderName: String,
        val leaderNickname: String,
        val leaderElapsedMs: Long?,
        val leaderDeltaMs: Long?,
        val estimatedRank: Int?,
        val rankedCount: Int,
        val participantCount: Int,
        val updatedAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, Status>()
    private val lastFetch = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

    fun cached(eventCode: String): Status? = cache[eventCode.trim().uppercase()]

    fun clear(eventCode: String) {
        cache.remove(eventCode.trim().uppercase())
    }

    fun refreshIfDue(context: Context, snapshot: RaceDataStore.Snapshot) {
        val event = snapshot.eventCode.trim().uppercase()
        if (event.isBlank() || event == "PRACTICE") return
        if (snapshot.state !in setOf("ARMED", "RUNNING", "FINISHED")) return

        val now = System.currentTimeMillis()
        if (now - (lastFetch[event] ?: 0L) < 1_000L) return
        val gate = inFlight.getOrPut(event) { AtomicBoolean(false) }
        if (!gate.compareAndSet(false, true)) return
        lastFetch[event] = now

        val app = context.applicationContext
        Thread {
            try {
                val client = RaceServerClient(app)
                val base = client.baseUrl().trim().trimEnd('/')
                if (!base.startsWith("http://") && !base.startsWith("https://")) return@Thread
                val joined = RaceDataStore(app).joined(event, base) ?: return@Thread
                if (joined.token.isBlank()) return@Thread
                val elapsed = when (snapshot.state) {
                    "RUNNING" -> if (snapshot.startedAtMs > 0L) (System.currentTimeMillis() - snapshot.startedAtMs).coerceAtLeast(0L) else 0L
                    "FINISHED" -> snapshot.elapsedMs.coerceAtLeast(0L)
                    else -> 0L
                }
                val code = URLEncoder.encode(event, "UTF-8")
                val url = "$base/api/race/events/$code/my-live-status" +
                    "?route_m=${snapshot.routeM}&elapsed_ms=$elapsed&started_at_ms=${snapshot.startedAtMs.coerceAtLeast(0L)}"
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 4_000
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer ${joined.token}")
                    if (conn.responseCode !in 200..299) return@Thread
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val o = JSONObject(text)
                    if (!o.optBoolean("accepted", true)) return@Thread
                    val leader = o.optJSONObject("leader")

                    fun nullableLong(obj: JSONObject?, vararg keys: String): Long? {
                        if (obj == null) return null
                        for (key in keys) if (obj.has(key) && !obj.isNull(key)) return obj.optLong(key)
                        return null
                    }
                    fun nullableInt(obj: JSONObject?, vararg keys: String): Int? {
                        if (obj == null) return null
                        for (key in keys) if (obj.has(key) && !obj.isNull(key)) return obj.optInt(key)
                        return null
                    }
                    fun textValue(vararg keys: String): String {
                        for (key in keys) {
                            val direct = o.optString(key, "").trim()
                            if (direct.isNotBlank()) return direct
                        }
                        if (leader != null) {
                            for (key in keys) {
                                val nestedKey = key.removePrefix("leader_")
                                val nested = leader.optString(nestedKey, "").trim()
                                if (nested.isNotBlank()) return nested
                            }
                        }
                        return ""
                    }

                    val status = Status(
                        eventCode = event,
                        leaderBib = textValue("leader_bib", "leader_bib_number", "leader_number"),
                        leaderName = textValue("leader_name"),
                        leaderNickname = textValue("leader_nickname", "leader_nick"),
                        leaderElapsedMs = nullableLong(o, "leader_elapsed_ms", "leader_best_ms", "best_elapsed_ms")
                            ?: nullableLong(leader, "elapsed_ms", "best_ms", "best_elapsed_ms"),
                        leaderDeltaMs = nullableLong(o, "leader_delta_ms"),
                        estimatedRank = nullableInt(o, "estimated_rank"),
                        rankedCount = o.optInt("ranked_count", 0),
                        participantCount = o.optInt("participant_count", 0),
                        updatedAtMs = System.currentTimeMillis()
                    )
                    cache[event] = status
                    // Keep the durable snapshot informed as well. Identity details stay in the
                    // in-memory status because the durable schema predates bib/nickname support.
                    RaceDataStore(app).updateLiveLeaderboard(
                        status.leaderName,
                        status.leaderElapsedMs,
                        status.leaderDeltaMs,
                        status.estimatedRank,
                        status.rankedCount,
                        status.participantCount
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Course-best display is additive. Timing must never depend on this request.
            } finally {
                gate.set(false)
            }
        }.start()
    }
}
