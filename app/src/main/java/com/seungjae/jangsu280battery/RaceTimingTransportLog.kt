package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Small durable field log for START/CP/FINISH transport diagnostics. */
object RaceTimingTransportLog {
    private const val MAX_BYTES = 512 * 1024L

    @Synchronized
    fun write(
        context: Context,
        stage: String,
        eventCode: String = "",
        runId: String = "",
        payload: JSONObject? = null,
        detail: String = ""
    ) {
        runCatching {
            val dir = File(context.filesDir, "race/debug").apply { mkdirs() }
            val file = File(dir, "timing_transport.jsonl")
            if (file.exists() && file.length() > MAX_BYTES) {
                val old = File(dir, "timing_transport.previous.jsonl")
                if (old.exists()) old.delete()
                file.renameTo(old)
            }
            val row = JSONObject().apply {
                put("at_ms", System.currentTimeMillis())
                put("stage", stage)
                if (eventCode.isNotBlank()) put("event_code", eventCode)
                if (runId.isNotBlank()) put("run_id", runId)
                if (detail.isNotBlank()) put("detail", detail.take(400))
                if (payload != null) {
                    put("type_state", payload.optString("state"))
                    put("elapsed_ms", payload.optLong("elapsed_ms", -1L))
                    put("started_at_ms", payload.optLong("started_at_ms", 0L))
                    put("finished_at_ms", payload.optLong("finished_at_ms", 0L))
                    put("timestamp_ms", payload.optLong("timestamp_ms", 0L))
                    put("sector_index", payload.optInt("sector_index", -1))
                    put("route_m", payload.optDouble("route_m", -1.0))
                    put("status", payload.optString("status"))
                }
            }
            file.appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }
}
