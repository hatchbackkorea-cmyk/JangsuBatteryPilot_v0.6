package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 향후 지도 + 거리/고도/배터리 피드백 재분석을 위해 원본 파일과 해석된 시계열을 보존한다.
 * 심박 데이터는 의도적으로 저장하지 않는다.
 *
 * 한 실제 라이딩이 Avinox 절전/전원 OFF로 여러 FIT으로 나뉘어도 같은 세션 폴더에 보존한다.
 */
class HistoricalRideDataStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "historical_ride_data").apply { mkdirs() }

    data class StoredFiles(
        val original: File?,
        val originals: List<File> = emptyList(),
        val telemetryCsv: File?,
        val batteryEventsCsv: File?,
        val sessionManifestCsv: File? = null,
        val gapsCsv: File? = null
    )

    fun save(uri: Uri, analysis: HistoricalRideAnalysis, batteryEntries: List<ActualBatteryEntry> = emptyList()): StoredFiles =
        save(listOf(uri), analysis, batteryEntries)

    fun save(uris: List<Uri>, analysis: HistoricalRideAnalysis, batteryEntries: List<ActualBatteryEntry> = emptyList()): StoredFiles {
        val dir = File(root, analysis.fileHash).apply { mkdirs() }
        val ext = when (analysis.sourceType) {
            HistoricalSourceType.FIT -> "fit"
            HistoricalSourceType.GPX -> "gpx"
        }

        val orderedUris = if (analysis.sourceParts.isNotEmpty()) {
            analysis.sourceParts.mapNotNull { it.uri }.ifEmpty { uris }
        } else uris

        val originals = mutableListOf<File>()
        orderedUris.distinct().forEachIndexed { index, uri ->
            val fileName = if (orderedUris.size == 1) "original.$ext" else "original_${String.format(Locale.US, "%02d", index + 1)}.$ext"
            val original = File(dir, fileName)
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(original).use { out -> input.copyTo(out) }
            } ?: error("원본 파일을 다시 열 수 없습니다.")
            if (original.exists()) originals += original
        }

        val telemetry = if (analysis.telemetry.isNotEmpty()) {
            File(dir, "telemetry.csv").also { writeTelemetry(it, analysis.telemetry) }
        } else null
        val batteryEvents = if (batteryEntries.isNotEmpty()) {
            File(dir, "battery_events.csv").also { writeBatteryEvents(it, batteryEntries) }
        } else null
        val manifest = File(dir, "session_manifest.csv").also { writeManifest(it, analysis) }
        val gaps = if (analysis.gaps.isNotEmpty()) {
            File(dir, "session_gaps.csv").also { writeGaps(it, analysis.gaps) }
        } else null

        return StoredFiles(
            original = originals.firstOrNull(),
            originals = originals,
            telemetryCsv = telemetry?.takeIf { it.exists() },
            batteryEventsCsv = batteryEvents?.takeIf { it.exists() },
            sessionManifestCsv = manifest.takeIf { it.exists() },
            gapsCsv = gaps?.takeIf { it.exists() }
        )
    }

    fun saveCompanion(uri: Uri, fileHash: String, fileName: String = "app_ride.zip"): File {
        require(fileHash.isNotBlank()) { "학습 세션 해시가 없습니다." }
        val dir = File(root, fileHash).apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^0-9A-Za-z가-힣._-]"), "_").take(120).ifBlank { "app_ride.zip" }
        val out = File(dir, safeName)
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: error("동반 주행 ZIP을 다시 열 수 없습니다.")
        return out
    }

    fun remove(fileHash: String) {
        if (fileHash.isNotBlank()) File(root, fileHash).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    private fun writeManifest(file: File, analysis: HistoricalRideAnalysis) {
        file.bufferedWriter().use { w ->
            w.appendLine("part_index,file_name,file_hash,distance_km,ascent_m,descent_m,moving_sec,start_timestamp_ms,end_timestamp_ms,start_lat,start_lon,end_lat,end_lon")
            val parts = analysis.sourceParts.ifEmpty {
                listOf(HistoricalRideSourcePart(
                    displayName = analysis.displayName,
                    fileHash = analysis.fileHash,
                    uri = null,
                    distanceKm = analysis.distanceKm,
                    ascentM = analysis.ascentM,
                    descentM = analysis.descentM,
                    durationSec = analysis.durationSec,
                    startTimestampMs = analysis.telemetry.mapNotNull { it.timestampMs }.minOrNull(),
                    endTimestampMs = analysis.telemetry.mapNotNull { it.timestampMs }.maxOrNull(),
                    startLat = analysis.telemetry.firstOrNull()?.lat,
                    startLon = analysis.telemetry.firstOrNull()?.lon,
                    endLat = analysis.telemetry.lastOrNull()?.lat,
                    endLon = analysis.telemetry.lastOrNull()?.lon
                ))
            }
            parts.forEachIndexed { index, p ->
                w.append((index + 1).toString()).append(',')
                    .append(csv(p.displayName)).append(',')
                    .append(p.fileHash).append(',')
                    .append(fmt(p.distanceKm, 4)).append(',')
                    .append(fmt(p.ascentM, 1)).append(',')
                    .append(fmt(p.descentM, 1)).append(',')
                    .append(p.durationSec?.toString() ?: "").append(',')
                    .append(p.startTimestampMs?.toString() ?: "").append(',')
                    .append(p.endTimestampMs?.toString() ?: "").append(',')
                    .append(p.startLat?.let { fmt(it, 7) } ?: "").append(',')
                    .append(p.startLon?.let { fmt(it, 7) } ?: "").append(',')
                    .append(p.endLat?.let { fmt(it, 7) } ?: "").append(',')
                    .appendLine(p.endLon?.let { fmt(it, 7) } ?: "")
            }
        }
    }

    private fun writeGaps(file: File, gaps: List<HistoricalRideGap>) {
        file.bufferedWriter().use { w ->
            w.appendLine("gap_index,before_file,after_file,pause_sec,location_gap_m,time_overlap_sec")
            gaps.forEachIndexed { index, g ->
                w.append((index + 1).toString()).append(',')
                    .append(csv(g.beforeFile)).append(',')
                    .append(csv(g.afterFile)).append(',')
                    .append(g.durationSec?.toString() ?: "").append(',')
                    .append(g.locationGapM?.let { fmt(it, 1) } ?: "").append(',')
                    .appendLine(g.timeOverlapSec.toString())
            }
        }
    }

    private fun writeBatteryEvents(file: File, entries: List<ActualBatteryEntry>) {
        file.bufferedWriter().use { w ->
            w.appendLine("timestamp_ms,route_km,battery_pct,kind")
            entries.forEach { e ->
                w.append(e.timestampMs.toString()).append(',')
                    .append(fmt(e.routeKm, 4)).append(',')
                    .append(fmt(e.percent, 1)).append(',')
                    .appendLine(e.kind.name)
            }
        }
    }

    private fun writeTelemetry(file: File, points: List<HistoricalTelemetryPoint>) {
        file.bufferedWriter().use { w ->
            w.appendLine("timestamp_ms,route_km,lat,lon,elevation_m,speed_kph,cadence_rpm,rider_power_w,motor_power_w,state")
            points.forEach { p ->
                w.append(p.timestampMs?.toString() ?: "").append(',')
                    .append(fmt(p.routeKm, 4)).append(',')
                    .append(fmt(p.lat, 7)).append(',')
                    .append(fmt(p.lon, 7)).append(',')
                    .append(p.elevationM?.let { fmt(it, 1) } ?: "").append(',')
                    .append(p.speedKph?.let { fmt(it, 2) } ?: "").append(',')
                    .append(p.cadenceRpm?.let { fmt(it, 1) } ?: "").append(',')
                    .append(p.riderPowerW?.let { fmt(it, 1) } ?: "").append(',')
                    .append(p.motorPowerW?.let { fmt(it, 1) } ?: "").append(',')
                    .appendLine(p.state.name)
            }
        }
    }

    private fun csv(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
    private fun fmt(v: Double, digits: Int): String = String.format(Locale.US, "%." + digits + "f", v)
}
