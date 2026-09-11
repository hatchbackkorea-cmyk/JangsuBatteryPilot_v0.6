package com.seungjae.jangsu280battery

import org.json.JSONArray
import org.json.JSONObject

object RaceTimingEvidence {
    fun audit(
        start: JSONObject?,
        finish: JSONObject?,
        originalStart: Long,
        originalFinish: Long,
        measurementNotes: List<String>
    ): JSONObject = JSONObject().apply {
        put("algorithm", GateTimingMath.ALGORITHM)
        put("evidence_version", "TIMING-EVIDENCE-1")
        put("start", start ?: JSONObject.NULL)
        put("finish", finish ?: JSONObject.NULL)
        put("measurement_notes", JSONArray(measurementNotes.distinct()))
        put("original_started_at_ms", originalStart)
        put("original_finished_at_ms", originalFinish)
        put("original_elapsed_ms", (originalFinish - originalStart).coerceAtLeast(0L))
        put("app_version", BuildConfig.VERSION_NAME)
    }
}
