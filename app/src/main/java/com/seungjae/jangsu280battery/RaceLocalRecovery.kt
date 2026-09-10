package com.seungjae.jangsu280battery

import android.content.Context
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One-time recovery helper for the legacy case where several cloned phones shared one
 * participant profile id. Local completed runs are the authority for run ownership.
 *
 * Recovery never edits/deletes the phone-local files. The server matches by run_id, moves
 * matching server records to this device-scoped participant, and restores local-only laps as
 * REVIEW records so measured times are preserved without fabricating certification.
 */
object RaceLocalRecovery {
    data class Preview(
        val eventCode: String,
        val eventName: String,
        val profile: RaceProfileStore.Profile,
        val runs: List<RaceRunSummary>
    )

    data class LocalForensics(
        val currentEventCompleted: Int,
        val allCompleted: Int,
        val rawFiles: Int,
        val rawNonEmptyFiles: Int,
        val otherEventCompleted: Int,
        val eventBreakdown: Map<String, Int>
    )

    fun scanAll(context: Context): LocalForensics {
        val store = RaceDataStore(context)
        val joinedCode = store.lastJoined()?.config?.eventCode?.trim()?.uppercase().orEmpty()
        val completed = store.completed()
            .filter { it.runId.isNotBlank() && it.elapsedMs > 0L }
        val current = if (joinedCode.isBlank()) 0 else completed.count {
            it.eventCode.trim().uppercase() == joinedCode
        }
        val breakdown = completed
            .groupingBy { it.eventCode.trim().uppercase().ifBlank { "PRACTICE" } }
            .eachCount()
            .toSortedMap()

        val rawDir = File(context.applicationContext.filesDir, "race/raw")
        val raw = rawDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jsonl", ignoreCase = true) }
            .orEmpty()

        return LocalForensics(
            currentEventCompleted = current,
            allCompleted = completed.size,
            rawFiles = raw.size,
            rawNonEmptyFiles = raw.count { it.length() > 0L },
            otherEventCompleted = (completed.size - current).coerceAtLeast(0),
            eventBreakdown = breakdown
        )
    }

    private fun showForensicsIfNeeded(context: Context, currentRuns: List<RaceRunSummary>) {
        if (currentRuns.isNotEmpty()) return
        val scan = scanAll(context)
        val events = scan.eventBreakdown.entries
            .joinToString(" · ") { "${it.key} ${it.value}랩" }
            .takeIf { it.isNotBlank() }
            ?: "완료랩 이벤트 없음"
        Toast.makeText(
            context.applicationContext,
            "전체 로컬 검사 · 완료랩 ${scan.allCompleted} · RAW ${scan.rawNonEmptyFiles}/${scan.rawFiles} · $events",
            Toast.LENGTH_LONG
        ).show()
    }

    fun preview(context: Context): Preview? {
        val store = RaceDataStore(context)
        val joined = store.lastJoined() ?: return null
        val code = joined.config.eventCode.trim().uppercase()
        val runs = store.completed()
            .filter { it.eventCode.trim().uppercase() == code && it.runId.isNotBlank() && it.elapsedMs > 0L }
            .sortedWith(compareBy<RaceRunSummary> { it.startedAtMs }.thenBy { it.runNumber })
        showForensicsIfNeeded(context, runs)
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
