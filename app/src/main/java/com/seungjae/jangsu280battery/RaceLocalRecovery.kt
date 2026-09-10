package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time recovery helper for the legacy case where several cloned phones shared one
 * participant profile id. Local completed runs are the authority for run ownership.
 *
 * Recovery never edits/deletes the phone-local files. The server matches by run_id, moves
 * matching server records to this device-scoped participant, and restores local-only laps as
 * INVALID records so measured times are preserved without fabricating certification.
 */
object RaceLocalRecovery {
    data class Preview(
        val eventCode: String,
        val eventName: String,
        val profile: RaceProfileStore.Profile,
        val runs: List<RaceRunSummary>
    )

    fun preview(context: Context): Preview? {
        val store = RaceDataStore(context)
        val joined = store.lastJoined() ?: return null
        val code = joined.config.eventCode.trim().uppercase()
        val runs = store.completed()
            .filter { it.eventCode.trim().uppercase() == code && it.runId.isNotBlank() && it.elapsedMs > 0L }
            .sortedWith(compareBy<RaceRunSummary> { it.startedAtMs }.thenBy { it.runNumber })
        return Preview(code, joined.config.name, RaceProfileStore.profile(context), runs)
    }

    fun recover(context: Context, debugReport: String = ""): JSONObject {
        val app = context.applicationContext
        val store = RaceDataStore(app)
        val joined = store.lastJoined() ?: error("대회 참가 정보가 없습니다.")
        val preview = preview(app) ?: error("복구할 대회 정보가 없습니다.")
        require(joined.token.isNotBlank()) { "participant token이 없습니다." }
        require(preview.runs.isNotEmpty()) { "이 휴대폰에 복구할 완료 랩이 없습니다." }

        val payload = JSONObject().apply {
            put("profile_id", preview.profile.profileId)
            put("name", preview.profile.name)
            put("nickname", preview.profile.nickname)
            put("bib", preview.profile.bib)
            put("platform", "ANDROID")
            put("app_version", UpdateManager.currentVersion(app))
            put("debug_report", debugReport.take(12_000))
            put("runs", JSONArray().apply { preview.runs.forEach { put(it.toJson()) } })
        }

        val client = RaceServerClient(app)
        val response = client.recoverLocalRuns(preview.eventCode, joined.token, payload)
        val replacementToken = response.optString("participant_token").trim()
        if (replacementToken.isNotBlank()) {
            store.saveJoined(
                joined.config,
                replacementToken,
                joined.localCourseId,
                joined.serverUrl.ifBlank { client.baseUrl() }
            )
        }
        return response
    }
}
