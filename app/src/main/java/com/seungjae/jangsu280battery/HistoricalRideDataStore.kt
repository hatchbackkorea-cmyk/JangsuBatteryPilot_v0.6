package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 향후 지도 + 거리/고도/배터리 피드백 재분석을 위해 원본 파일과 해석된 시계열을 보존한다.
 * 심박 데이터는 의도적으로 저장하지 않는다.
 */
class HistoricalRideDataStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "historical_ride_data").apply { mkdirs() }

    data class StoredFiles(val original: File?, val telemetryCsv: File?, val batteryEventsCsv: File?)

    fun save(uri: Uri, analysis: HistoricalRideAnalysis, batteryEntries: List<ActualBatteryEntry> = emptyList()): StoredFiles {
        val dir = File(root, analysis.fileHash).apply { mkdirs() }
        val ext = when (analysis.sourceType) {
            HistoricalSourceType.FIT -> "fit"
            HistoricalSourceType.GPX -> "gpx"
        }
        val original = File(dir, "original.$ext")
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(original).use { out -> input.copyTo(out) }
        } ?: error("원본 파일을 다시 열 수 없습니다.")

        val telemetry = if (analysis.telemetry.isNotEmpty()) {
            File(dir, "telemetry.csv").also { writeTelemetry(it, analysis.telemetry) }
        } else null
        val batteryEvents = if (batteryEntries.isNotEmpty()) {
            File(dir, "battery_events.csv").also { writeBatteryEvents(it, batteryEntries) }
        } else null
        return StoredFiles(
            original.takeIf { it.exists() },
            telemetry?.takeIf { it.exists() },
            batteryEvents?.takeIf { it.exists() }
        )
    }

    fun remove(fileHash: String) {
        if (fileHash.isNotBlank()) File(root, fileHash).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
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

    private fun fmt(v: Double, digits: Int): String = String.format(Locale.US, "%." + digits + "f", v)
}
