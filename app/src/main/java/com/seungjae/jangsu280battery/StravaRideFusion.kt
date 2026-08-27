package com.seungjae.jangsu280battery

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Matches an Avinox FIT to the phone ride log created by this app.
 * Avinox FITs currently contain Rider Power/Motor Power/HR/Cadence but may omit battery/mode.
 * Phone BLE logs provide the missing Avinox SOC and rider-selected assist mode.
 */
data class StravaPhonePoint(
    val timestampMs: Long,
    val routeKm: Double,
    val batteryPct: Double?,
    val assistMode: AvinoxAssistMode?,
    val assistRawCode: Int?
)

data class StravaModePoint(
    val timestampMs: Long,
    val mode: AvinoxAssistMode,
    val rawCode: Int
)

data class StravaRideOverlay(
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val points: List<StravaPhonePoint>,
    val modePoints: List<StravaModePoint>,
    val matchStartDeltaMin: Double,
    val matchDistanceDeltaPct: Double
) {
    data class Sample(
        val batteryPct: Double?,
        val assistMode: AvinoxAssistMode?,
        val assistRawCode: Int?
    )

    fun sample(fitEpochTimestampMs: Long?, routeKm: Double, fitTotalKm: Double): Sample {
        val unixMs = fitEpochTimestampMs?.plus(FIT_TO_UNIX_EPOCH_MS)
        val nearestTrack = if (unixMs != null && points.isNotEmpty()) {
            points.minByOrNull { abs(it.timestampMs - unixMs) }
                ?.takeIf { abs(it.timestampMs - unixMs) <= 20_000L }
        } else null

        val routeFallback = if (nearestTrack == null && points.isNotEmpty()) {
            val scaledRoute = if (fitTotalKm > 0.1 && distanceKm > 0.1) routeKm * (distanceKm / fitTotalKm) else routeKm
            points.minByOrNull { abs(it.routeKm - scaledRoute) }
        } else null
        val track = nearestTrack ?: routeFallback

        val preciseMode = if (unixMs != null && modePoints.isNotEmpty()) {
            modePoints.minByOrNull { abs(it.timestampMs - unixMs) }
                ?.takeIf { abs(it.timestampMs - unixMs) <= 8_000L }
        } else null

        return Sample(
            batteryPct = track?.batteryPct,
            assistMode = preciseMode?.mode ?: track?.assistMode,
            assistRawCode = preciseMode?.rawCode ?: track?.assistRawCode
        )
    }

    fun batteryStart(): Double? = points.firstNotNullOfOrNull { it.batteryPct }
    fun batteryEnd(): Double? = points.asReversed().firstNotNullOfOrNull { it.batteryPct }

    fun modeShareText(): String? {
        val src = if (modePoints.size >= 2) modePoints.map { it.timestampMs to it.mode }
        else points.mapNotNull { p -> p.assistMode?.let { p.timestampMs to it } }
        if (src.size < 2) return null
        val seconds = linkedMapOf<AvinoxAssistMode, Double>()
        for (i in 1 until src.size) {
            val dt = ((src[i].first - src[i - 1].first) / 1000.0).takeIf { it in 0.1..10.0 } ?: continue
            val mode = src[i - 1].second
            seconds[mode] = (seconds[mode] ?: 0.0) + dt
        }
        val total = seconds.values.sum().takeIf { it > 0.0 } ?: return null
        val order = listOf(AvinoxAssistMode.ECO, AvinoxAssistMode.AUTO, AvinoxAssistMode.TRAIL, AvinoxAssistMode.TURBO)
        return order.mapNotNull { mode ->
            seconds[mode]?.takeIf { it > 0.0 }?.let {
                "${mode.name} ${String.format(Locale.US, "%.0f", it / total * 100.0)}%"
            }
        }.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    companion object {
        const val FIT_TO_UNIX_EPOCH_MS = 631_065_600_000L
    }
}

object StravaRideFusion {
    private data class Candidate(
        val json: File,
        val csv: File,
        val autoDetect: File?,
        val startMs: Long,
        val endMs: Long,
        val distanceKm: Double,
        val score: Double,
        val startDeltaMin: Double,
        val distanceDeltaPct: Double
    )

    fun findBestMatch(context: Context, analysis: HistoricalRideAnalysis): StravaRideOverlay? {
        val fitStartUnix = analysis.telemetry.mapNotNull { it.timestampMs }.minOrNull()?.plus(StravaRideOverlay.FIT_TO_UNIX_EPOCH_MS)
            ?: return null
        val roots = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "GPXBatteryCopilot/RideLogs") },
            File(context.filesDir, "exports/GPXBatteryCopilot/RideLogs")
        ).distinctBy { it.absolutePath }

        val candidates = roots.flatMap { root -> root.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.toList().orEmpty() }
            .mapNotNull { json ->
                runCatching {
                    val o = JSONObject(json.readText())
                    val start = o.optLong("startMs", 0L)
                    val end = o.optLong("endMs", 0L)
                    val distance = o.optDouble("maxRouteKm", Double.NaN)
                    if (start <= 0L || !distance.isFinite()) return@runCatching null
                    val base = json.nameWithoutExtension
                    val csv = File(json.parentFile, "$base.csv")
                    if (!csv.exists()) return@runCatching null
                    val auto = File(json.parentFile, "${base}_assist_auto_detect.csv").takeIf { it.exists() }
                    val startDeltaMin = abs(start - fitStartUnix) / 60_000.0
                    val distanceDeltaPct = if (analysis.distanceKm > 0.2) abs(distance - analysis.distanceKm) / analysis.distanceKm * 100.0 else 0.0
                    val score = startDeltaMin + distanceDeltaPct * 1.5
                    Candidate(json, csv, auto, start, end, distance, score, startDeltaMin, distanceDeltaPct)
                }.getOrNull()
            }.filterNotNull()

        val best = candidates.minByOrNull { it.score } ?: return null
        // A ride started a little before/after Avinox is fine. Reject obviously unrelated files.
        if (best.startDeltaMin > 180.0 || (analysis.distanceKm > 1.0 && best.distanceDeltaPct > 35.0)) return null
        val points = readTrack(best.csv)
        if (points.isEmpty()) return null
        val modes = best.autoDetect?.let(::readModeTrace).orEmpty()
        return StravaRideOverlay(
            label = best.json.nameWithoutExtension,
            startMs = best.startMs,
            endMs = best.endMs,
            distanceKm = best.distanceKm,
            points = points,
            modePoints = modes,
            matchStartDeltaMin = best.startDeltaMin,
            matchDistanceDeltaPct = best.distanceDeltaPct
        )
    }

    private fun readTrack(file: File): List<StravaPhonePoint> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size < 2) return emptyList()
        val header = lines.first().split(',')
        fun ix(name: String) = header.indexOf(name)
        val ti = ix("timestamp_ms")
        val ri = ix("route_km")
        val bi = ix("actual_battery_pct")
        val mi = ix("assist_mode")
        val ci = ix("assist_raw_code")
        if (ti < 0 || ri < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            val ts = c.getOrNull(ti)?.toLongOrNull() ?: return@mapNotNull null
            val km = c.getOrNull(ri)?.toDoubleOrNull() ?: return@mapNotNull null
            val battery = c.getOrNull(bi)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
            val mode = c.getOrNull(mi)?.takeIf { it.isNotBlank() }?.let { runCatching { AvinoxAssistMode.valueOf(it) }.getOrNull() }
            val raw = c.getOrNull(ci)?.toIntOrNull()
            StravaPhonePoint(ts, km, battery, mode, raw)
        }.sortedBy { it.timestampMs }
    }

    private fun readModeTrace(file: File): List<StravaModePoint> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size < 2) return emptyList()
        val header = lines.first().split(',')
        fun ix(name: String) = header.indexOf(name)
        val ti = ix("timestamp_ms")
        val pi = ix("primary")
        val ci = ix("confidence")
        val ri = ix("raw_code")
        if (ti < 0 || pi < 0 || ri < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            val confidence = c.getOrNull(ci).orEmpty()
            if (ci >= 0 && confidence !in setOf("HIGH", "CONFIRMED")) return@mapNotNull null
            val ts = c.getOrNull(ti)?.toLongOrNull() ?: return@mapNotNull null
            val mode = c.getOrNull(pi)?.let { runCatching { AvinoxAssistMode.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            val raw = c.getOrNull(ri)?.toIntOrNull() ?: modeCode(mode)
            StravaModePoint(ts, mode, raw)
        }.sortedBy { it.timestampMs }
    }

    fun modeCode(mode: AvinoxAssistMode): Int = when (mode) {
        AvinoxAssistMode.ECO -> 1
        AvinoxAssistMode.TRAIL -> 2
        AvinoxAssistMode.TURBO -> 3
        AvinoxAssistMode.AUTO -> 4
    }
}
