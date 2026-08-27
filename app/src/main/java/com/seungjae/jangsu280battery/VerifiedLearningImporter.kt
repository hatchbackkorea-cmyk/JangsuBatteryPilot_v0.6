package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 검증 학습의 기준 결합기.
 *
 * - 거리/GPS/고도/획득고도/속도/Rider Power/Motor Power/Cadence = Avinox FIT
 * - 실제 SOC/선택 Assist Mode = Jangsu Battery Pilot 주행 ZIP
 * - 시간축으로 두 파일을 매칭한 뒤 FIT의 routeKm에 BLE SOC 지점을 재배치한다.
 *
 * 앱 자체 GPS 거리/고도는 품질검사용 비교값으로만 사용하고 학습 정답에는 사용하지 않는다.
 */
data class VerifiedLearningPair(
    val analysis: HistoricalRideAnalysis,
    val fitUri: Uri,
    val zipUri: Uri,
    val fitName: String,
    val zipName: String,
    val pairHash: String,
    val batteryEntries: List<ActualBatteryEntry>,
    val modeWindows: List<AssistModeWindow>,
    val appDistanceKm: Double?,
    val distanceErrorPct: Double?,
    val startDeltaSec: Long?,
    val endDeltaSec: Long?,
    val fitTimeOffsetMs: Long,
    val qualityScore: Int,
    val accepted: Boolean,
    val warnings: List<String> = emptyList(),
    val sessionIdFromZip: String = ""
) {
    fun summaryText(): String = buildString {
        append("검증 학습 · Avinox FIT + 앱 ZIP\n")
        append("FIT 기준 거리 ${String.format(Locale.US, "%.3f", analysis.distanceKm)} km · 상승 ${analysis.ascentM.roundToInt()}m · 하강 ${analysis.descentM.roundToInt()}m\n")
        appDistanceKm?.let { appKm ->
            append("앱 ZIP 거리 ${String.format(Locale.US, "%.3f", appKm)} km")
            distanceErrorPct?.let { append(" · 거리차 ${String.format(Locale.US, "%.2f", it)}%") }
            append("\n")
        }
        append("BLE SOC ${batteryEntries.size}개 · 모드 구간 ${modeWindows.size}개")
        startDeltaSec?.let { append(" · 시작차 ${it}초") }
        append("\n데이터 품질 ${qualityScore}% · ")
        append(if (accepted) "✅ 정식 학습 가능" else "⛔ 정식 학습 보류")
        append("\n학습 기준: 거리·고도·파워=FIT / SOC·모드=ZIP")
        warnings.take(4).forEach { append("\n⚠ $it") }
    }
}

object VerifiedLearningImporter {
    private const val MAX_ZIP_BYTES = 64 * 1024 * 1024
    /** Garmin FIT epoch(1989-12-31) → Unix epoch 차이. */
    private const val FIT_TO_UNIX_MS = 631_065_600_000L

    fun analyze(context: Context, fitUri: Uri, zipUri: Uri): VerifiedLearningPair {
        val fitName = HistoricalRideImporter.displayName(context, fitUri)
        val zipName = HistoricalRideImporter.displayName(context, zipUri)
        val fit = HistoricalRideImporter.analyze(context, fitUri, HistoricalSourceType.FIT)
        require(fit.telemetry.isNotEmpty()) { "FIT에 시간/위치 텔레메트리가 없습니다." }

        val zipBytes = context.contentResolver.openInputStream(zipUri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_ZIP_BYTES) { "주행 ZIP이 너무 큽니다." }
            bytes
        } ?: error("앱 주행 ZIP을 열 수 없습니다.")
        val zipHash = sha256(zipBytes)
        val entries = readZipEntries(zipBytes)
        val rideJson = entries.entries
            .asSequence()
            .filter { it.key.endsWith(".json", ignoreCase = true) }
            .mapNotNull { (name, bytes) ->
                runCatching { name to JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            }
            .firstOrNull { (_, o) -> o.has("actualBattery") && o.has("sessionId") }
            ?: error("ZIP 안에서 Jangsu Battery Pilot 주행 JSON을 찾지 못했습니다.")
        val root = rideJson.second
        require(!root.optBoolean("testMode", false)) { "테스트 모드 주행은 정식 학습에 사용할 수 없습니다." }

        val zipStart = root.optLong("startMs", 0L).takeIf { it > 0L }
        val zipEnd = root.optLong("endMs", 0L).takeIf { it > 0L }
        val rawFitTimes = fit.telemetry.mapNotNull { it.timestampMs }
        val rawFitStart = rawFitTimes.minOrNull()
        val rawFitEnd = rawFitTimes.maxOrNull()
        val fitOffset = chooseFitTimeOffset(rawFitStart, zipStart)
        val fitTimeline = fit.telemetry.mapNotNull { p ->
            p.timestampMs?.let { (it + fitOffset) to p.routeKm }
        }.sortedBy { it.first }
        require(fitTimeline.size >= 2) { "FIT 시간축을 만들 수 없습니다." }

        val appDistance = root.optDouble("maxRouteKm", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
        val distanceError = appDistance?.let { abs(it - fit.distanceKm) / fit.distanceKm.coerceAtLeast(0.1) * 100.0 }
        val fitStartUnix = rawFitStart?.plus(fitOffset)
        val fitEndUnix = rawFitEnd?.plus(fitOffset)
        val startDelta = if (fitStartUnix != null && zipStart != null) abs(fitStartUnix - zipStart) / 1000L else null
        val endDelta = if (fitEndUnix != null && zipEnd != null) abs(fitEndUnix - zipEnd) / 1000L else null

        val battery = parseBatteryEntries(root, fitTimeline, fit.distanceKm, appDistance)
        val modes = parseModeWindows(root, entries)
        val warnings = mutableListOf<String>()
        if (distanceError == null) warnings += "앱 ZIP 총거리를 읽지 못했습니다."
        else if (distanceError > 3.0) warnings += "FIT과 앱 거리 차이가 3%를 넘습니다."
        if (startDelta == null) warnings += "FIT과 ZIP의 시작 시간을 비교하지 못했습니다."
        else if (startDelta > 300L) warnings += "FIT과 ZIP 시작 시간이 5분 넘게 차이납니다."
        if (endDelta != null && endDelta > 600L) warnings += "FIT과 ZIP 종료 시간이 10분 넘게 차이납니다."
        if (battery.size < 3) warnings += "BLE SOC 기록이 3개 미만입니다."
        if (modes.isEmpty()) warnings += "검증된 Assist Mode 구간이 없습니다."

        var quality = fit.dataQualityScore.toDouble()
        distanceError?.let { quality -= (it * 3.0).coerceAtMost(20.0) }
        startDelta?.let { quality -= (it / 60.0).coerceAtMost(12.0) }
        if (battery.size < 8) quality -= 10.0
        if (modes.isEmpty()) quality -= 35.0
        val accepted = fit.dataQualityScore >= 50 &&
            (distanceError ?: 99.0) <= 3.0 &&
            (startDelta ?: Long.MAX_VALUE) <= 300L &&
            (endDelta == null || endDelta <= 600L) &&
            battery.size >= 3 && modes.isNotEmpty()

        val pairHash = sha256((fit.fileHash + ":" + zipHash).toByteArray(Charsets.UTF_8))
        return VerifiedLearningPair(
            analysis = fit,
            fitUri = fitUri,
            zipUri = zipUri,
            fitName = fitName,
            zipName = zipName,
            pairHash = pairHash,
            batteryEntries = battery,
            modeWindows = modes,
            appDistanceKm = appDistance,
            distanceErrorPct = distanceError,
            startDeltaSec = startDelta,
            endDeltaSec = endDelta,
            fitTimeOffsetMs = fitOffset,
            qualityScore = quality.roundToInt().coerceIn(0, 100),
            accepted = accepted,
            warnings = warnings,
            sessionIdFromZip = root.optString("sessionId", "")
        )
    }

    private fun parseBatteryEntries(
        root: JSONObject,
        fitTimeline: List<Pair<Long, Double>>,
        fitDistanceKm: Double,
        appDistanceKm: Double?
    ): List<ActualBatteryEntry> {
        val arr = root.optJSONArray("actualBattery") ?: return emptyList()
        val out = mutableListOf<ActualBatteryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pct = o.optDouble("percent", Double.NaN)
            val ts = o.optLong("timestampMs", 0L)
            val appKm = o.optDouble("routeKm", Double.NaN)
            if (!pct.isFinite() || pct !in 0.0..100.0 || ts <= 0L) continue
            val kind = runCatching { ActualEntryKind.valueOf(o.optString("kind", "RIDING")) }.getOrDefault(ActualEntryKind.RIDING)
            val source = runCatching { ActualEntrySource.valueOf(o.optString("source", "MANUAL")) }.getOrDefault(ActualEntrySource.MANUAL)
            // SOC 학습 정답은 BLE/충전 이벤트만 허용. 음성/수동값은 보조 입력이므로 검증 학습에서 제외한다.
            if (source !in setOf(ActualEntrySource.BLE_AVINOX, ActualEntrySource.CHARGE)) continue
            val nearest = nearestFitKm(fitTimeline, ts)
            val fitKm = if (nearest != null && nearest.first <= 120_000L) {
                nearest.second
            } else if (appKm.isFinite() && appDistanceKm != null && appDistanceKm > 0.1) {
                appKm * fitDistanceKm / appDistanceKm
            } else if (appKm.isFinite()) appKm else continue
            out += ActualBatteryEntry(
                percent = pct,
                routeKm = fitKm.coerceIn(0.0, fitDistanceKm),
                timestampMs = ts,
                kind = kind,
                source = source
            )
        }
        // BLE가 같은 SOC를 중복 기록한 구형 로그가 있어 변화점만 남긴다. 충전 ARRIVAL/POST_CHARGE는 항상 보존.
        val sorted = out.sortedBy { it.timestampMs }
        val deduped = mutableListOf<ActualBatteryEntry>()
        sorted.forEach { e ->
            val prev = deduped.lastOrNull()
            if (e.kind != ActualEntryKind.RIDING || prev == null || e.percent != prev.percent || e.source != prev.source) {
                deduped += e
            }
        }
        return deduped
    }

    private fun parseModeWindows(root: JSONObject, zipEntries: Map<String, ByteArray>): List<AssistModeWindow> {
        val requested = root.optString("assistProfilesFile", "")
        val bytes = zipEntries[requested]
            ?: zipEntries.entries.firstOrNull { it.key.contains("assist_profiles") && it.key.endsWith(".jsonl") }?.value
            ?: return emptyList()
        data class Mark(val at: Long, val mode: AvinoxAssistMode, val profileId: String, val confidence: String)
        val marks = bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val at = o.optLong("selectedAtMs", 0L)
            val mode = runCatching { AvinoxAssistMode.valueOf(o.optString("mode", "")) }.getOrNull()
            val confidence = o.optString("modeConfidence", "CONFIRMED")
            val profileId = o.optString("profileId", "").takeIf { it.isNotBlank() } ?: mode?.let { "${it.name}_verified" }
            if (at <= 0L || mode == null || profileId == null || confidence !in setOf("HIGH", "CONFIRMED")) null
            else Mark(at, mode, profileId, confidence)
        }.sortedBy { it.at }.toList()
        if (marks.isEmpty()) return emptyList()
        val rideEnd = root.optLong("endMs", marks.last().at + 1L).coerceAtLeast(marks.last().at + 1L)
        return marks.mapIndexed { index, m ->
            val end = if (index + 1 < marks.size) marks[index + 1].at - 1L else rideEnd
            AssistModeWindow(m.at, end.coerceAtLeast(m.at), m.mode, m.profileId)
        }
    }

    private fun nearestFitKm(timeline: List<Pair<Long, Double>>, timestampMs: Long): Pair<Long, Double>? {
        if (timeline.isEmpty()) return null
        var lo = 0
        var hi = timeline.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (timeline[mid].first < timestampMs) lo = mid + 1 else hi = mid - 1
        }
        val candidates = listOfNotNull(timeline.getOrNull(lo), timeline.getOrNull(lo - 1))
        val best = candidates.minByOrNull { abs(it.first - timestampMs) } ?: return null
        return abs(best.first - timestampMs) to best.second
    }

    private fun chooseFitTimeOffset(rawFitStartMs: Long?, zipStartMs: Long?): Long {
        if (rawFitStartMs == null || zipStartMs == null) return if ((rawFitStartMs ?: 0L) < 1_500_000_000_000L) FIT_TO_UNIX_MS else 0L
        val noOffset = abs(rawFitStartMs - zipStartMs)
        val withOffset = abs(rawFitStartMs + FIT_TO_UNIX_MS - zipStartMs)
        return if (withOffset < noOffset) FIT_TO_UNIX_MS else 0L
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    if (name.endsWith(".json", true) || name.endsWith(".jsonl", true) || name.endsWith(".csv", true)) {
                        out[name] = zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
