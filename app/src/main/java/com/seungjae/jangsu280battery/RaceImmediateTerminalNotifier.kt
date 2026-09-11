package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject

/**
 * Mirrors the phone's local terminal state to the live server immediately.
 *
 * This is presentation-only: the durable FINISH record is still uploaded after the phone finishes
 * its own timing refinement. The server must never calculate or advance the official lap time.
 */
object RaceImmediateTerminalNotifier {
    fun onSnapshot(
        context: Context,
        store: RaceDataStore,
        previous: RaceDataStore.Snapshot,
        current: RaceDataStore.Snapshot
    ) {
        if (previous.state == current.state) return
        if (current.state != "FINISHED") return
        if (current.eventCode.isBlank() || current.eventCode.equals("PRACTICE", ignoreCase = true)) return
        if (current.runId.isBlank() || current.startedAtMs <= 0L || current.elapsedMs <= 0L) return

        val app = context.applicationContext
        Thread {
            val client = RaceServerClient(app)
            val joined = store.joined(current.eventCode, client.baseUrl()) ?: return@Thread
            val finishedAt = current.startedAtMs + current.elapsedMs
            val payload = JSONObject().apply {
                put("event_code", current.eventCode)
                put("run_id", current.runId)
                put("run_number", current.runNumber)
                put("state", "FINISHED")
                put("status", "FINISHED")
                put("started_at_ms", current.startedAtMs)
                put("finished_at_ms", finishedAt)
                put("timestamp_ms", finishedAt)
                put("elapsed_ms", current.elapsedMs)
                put("route_m", current.routeM)
                put("sector_index", current.nextGateIndex)
                put("timing_authority", "PHONE")
                put("phone_time_frozen", true)
                put("provisional_terminal", true)
            }
            RaceTimingTransportLog.write(app, "LOCAL_FINISH", current.eventCode, current.runId, payload)
            runCatching { client.sendLive(current.eventCode, joined.token, payload) }
                .onSuccess { RaceTimingTransportLog.write(app, "LOCAL_FINISH_ACK", current.eventCode, current.runId, payload) }
                .onFailure { RaceTimingTransportLog.write(app, "LOCAL_FINISH_FAIL", current.eventCode, current.runId, payload, it.message.orEmpty()) }
        }.start()
    }
}
