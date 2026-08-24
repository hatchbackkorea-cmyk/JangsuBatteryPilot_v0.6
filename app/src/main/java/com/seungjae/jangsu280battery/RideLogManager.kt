package com.seungjae.jangsu280battery

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


data class ActiveRide(
    val sessionId: String,
    val courseId: String,
    val courseName: String,
    val startMs: Long
)

data class RideArchive(
    val sessionId: String,
    val courseName: String,
    val startMs: Long,
    val endMs: Long,
    val maxRouteKm: Double,
    val avgSpeedKmh: Double,
    val csvFile: File,
    val gpxFile: File,
    val jsonFile: File,
    val zipFile: File,
    val learnedSamples: Int
)

class RideLogManager(context: Context) {
    companion object {
        private const val PREFS = "ride_log_manager"
        private const val ACTIVE_ID = "active_id"
        private const val ACTIVE_COURSE_ID = "active_course_id"
        private const val ACTIVE_COURSE_NAME = "active_course_name"
        private const val ACTIVE_START = "active_start"
        private const val ACTIVE_MAX_KM = "active_max_km"
        private const val ACTIVE_SPEED_SUM = "active_speed_sum"
        private const val ACTIVE_SPEED_COUNT = "active_speed_count"
        private const val LAST_ZIP = "last_zip"
        private const val LAST_JSON = "last_json"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sessionsRoot = File(app.filesDir, "ride_sessions").apply { mkdirs() }
    private var lastWriteMs = 0L

    fun activeRide(): ActiveRide? {
        val id = prefs.getString(ACTIVE_ID, null) ?: return null
        val courseId = prefs.getString(ACTIVE_COURSE_ID, null) ?: return null
        val name = prefs.getString(ACTIVE_COURSE_NAME, "GPX 코스") ?: "GPX 코스"
        val start = prefs.getLong(ACTIVE_START, 0L)
        val dir = File(sessionsRoot, id)
        if (!dir.exists()) return null
        return ActiveRide(id, courseId, name, start)
    }

    fun isActive(): Boolean = activeRide() != null

    fun start(course: CourseMeta): ActiveRide {
        activeRide()?.let {
            if (it.courseId == course.id) return it
            error("다른 코스의 주행 기록이 아직 진행 중입니다.")
        }
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + "_" + now.toString().takeLast(5)
        val dir = File(sessionsRoot, id).apply { mkdirs() }
        File(dir, "track.csv").writeText("timestamp_ms,lat,lon,gps_ele_m,speed_kmh,route_km,off_course_m,course_ele_m,estimated_battery_pct,actual_battery_pct\n")
        File(dir, "events.jsonl").writeText("")
        prefs.edit()
            .putString(ACTIVE_ID, id)
            .putString(ACTIVE_COURSE_ID, course.id)
            .putString(ACTIVE_COURSE_NAME, course.name)
            .putLong(ACTIVE_START, now)
            .putFloat(ACTIVE_MAX_KM, 0f)
            .putFloat(ACTIVE_SPEED_SUM, 0f)
            .putInt(ACTIVE_SPEED_COUNT, 0)
            .apply()
        recordEvent("RIDE_START", "주행 시작", 0.0, null)
        return activeRide()!!
    }

    fun recordLocation(
        timestampMs: Long,
        lat: Double,
        lon: Double,
        gpsElevationM: Double?,
        speedKmh: Double,
        routeKm: Double,
        offCourseM: Double,
        courseElevationM: Double,
        estimatedBatteryPct: Double,
        actualBatteryPct: Double?
    ) {
        val ride = activeRide() ?: return
        val now = timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (now - lastWriteMs < 2500L) return
        lastWriteMs = now
        val dir = File(sessionsRoot, ride.sessionId)
        val gpsEle = gpsElevationM?.takeIf { it.isFinite() }?.let { fmt(it, 1) } ?: ""
        val actual = actualBatteryPct?.let { fmt(it, 1) } ?: ""
        val line = listOf(
            now.toString(), fmt(lat, 7), fmt(lon, 7), gpsEle, fmt(speedKmh, 2), fmt(routeKm, 3),
            fmt(offCourseM, 1), fmt(courseElevationM, 1), fmt(estimatedBatteryPct, 1), actual
        ).joinToString(",") + "\n"
        File(dir, "track.csv").appendText(line)

        val oldMax = prefs.getFloat(ACTIVE_MAX_KM, 0f).toDouble()
        val edit = prefs.edit()
        if (routeKm > oldMax) edit.putFloat(ACTIVE_MAX_KM, routeKm.toFloat())
        if (speedKmh in 2.0..70.0) {
            edit.putFloat(ACTIVE_SPEED_SUM, prefs.getFloat(ACTIVE_SPEED_SUM, 0f) + speedKmh.toFloat())
            edit.putInt(ACTIVE_SPEED_COUNT, prefs.getInt(ACTIVE_SPEED_COUNT, 0) + 1)
        }
        edit.apply()
    }

    fun recordEvent(type: String, detail: String, routeKm: Double, batteryPct: Double?) {
        val ride = activeRide() ?: return
        val dir = File(sessionsRoot, ride.sessionId)
        val o = JSONObject().apply {
            put("timestampMs", System.currentTimeMillis())
            put("type", type)
            put("detail", detail)
            put("routeKm", routeKm)
            if (batteryPct != null) put("batteryPct", batteryPct)
        }
        File(dir, "events.jsonl").appendText(o.toString() + "\n")
    }

    fun activeSummaryText(): String {
        val ride = activeRide() ?: return "현재 진행 중인 주행이 없습니다."
        val maxKm = prefs.getFloat(ACTIVE_MAX_KM, 0f).toDouble()
        val count = prefs.getInt(ACTIVE_SPEED_COUNT, 0)
        val avg = if (count > 0) prefs.getFloat(ACTIVE_SPEED_SUM, 0f) / count else 0f
        val elapsed = ((System.currentTimeMillis() - ride.startMs).coerceAtLeast(0) / 60000L)
        val h = elapsed / 60
        val m = elapsed % 60
        return "${ride.courseName}\n진행 ${RideFormatter.one(maxKm)} km · ${if (h > 0) "${h}시간 ${m}분" else "${m}분"}\n이동 평균 ${if (avg > 0f) RideFormatter.one(avg.toDouble()) + " km/h" else "-"}\n로그는 주행 중 계속 자동 저장 중입니다."
    }

    fun finalizeRide(
        course: CourseData,
        actualStore: BatteryActualStore,
        learning: BatteryLearningStore,
        chargingStations: List<ChargingStation> = emptyList()
    ): RideArchive {
        val ride = activeRide() ?: error("진행 중인 주행이 없습니다.")
        val end = System.currentTimeMillis()
        recordEvent("RIDE_END", "주행 종료", prefs.getFloat(ACTIVE_MAX_KM, 0f).toDouble(), actualStore.latest()?.percent)
        val sessionDir = File(sessionsRoot, ride.sessionId)
        val sourceCsv = File(sessionDir, "track.csv")
        val events = readEvents(File(sessionDir, "events.jsonl"))
        val maxKm = prefs.getFloat(ACTIVE_MAX_KM, 0f).toDouble()
        val speedCount = prefs.getInt(ACTIVE_SPEED_COUNT, 0)
        val avgSpeed = if (speedCount > 0) prefs.getFloat(ACTIVE_SPEED_SUM, 0f).toDouble() / speedCount else 0.0

        val exportRoot = (app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(app.filesDir, "exports"))
        val outDir = File(exportRoot, "GPXBatteryCopilot/RideLogs").apply { mkdirs() }
        val safeName = sanitize(ride.courseName).take(38).ifBlank { "ride" }
        val baseName = "${ride.sessionId}_$safeName"
        val csv = File(outDir, "$baseName.csv")
        sourceCsv.copyTo(csv, overwrite = true)
        val gpx = File(outDir, "$baseName.gpx")
        writeGpxFromCsv(sourceCsv, gpx, ride.courseName)

        val learned = learning.trainFromRide(ride.sessionId, course, actualStore.entries())
        val json = File(outDir, "$baseName.json")
        val summary = JSONObject().apply {
            put("sessionId", ride.sessionId)
            put("courseId", ride.courseId)
            put("courseName", ride.courseName)
            put("startMs", ride.startMs)
            put("endMs", end)
            put("durationSec", ((end - ride.startMs).coerceAtLeast(0L) / 1000L))
            put("maxRouteKm", maxKm)
            put("avgSpeedKmh", avgSpeed)
            put("courseTotalKm", course.totalKm)
            put("courseAscentM", course.totalAscentM)
            put("courseDescentM", course.totalDescentM)
            put("courseHasElevation", course.hasElevation)
            put("learnedSamplesAdded", learned)
            put("chargingPlan", JSONArray().apply {
                chargingStations.sortedBy { it.routeKm }.forEach { s -> put(JSONObject().apply {
                    put("name", s.name); put("routeKm", s.routeKm); put("chargeToPct", s.chargeToPct)
                    put("source", s.source); put("distanceFromRouteM", s.distanceFromRouteM); put("detourKm", s.detourKm)
                    if (s.address.isNotBlank()) put("address", s.address)
                }) }
            })
            put("events", JSONArray().apply { events.forEach { put(it) } })
            put("actualBattery", JSONArray().apply {
                actualStore.entries().forEach { e -> put(JSONObject().apply {
                    put("percent", e.percent); put("routeKm", e.routeKm); put("timestampMs", e.timestampMs); put("kind", e.kind.name)
                }) }
            })
        }
        json.writeText(summary.toString(2))

        val zip = File(outDir, "$baseName.zip")
        zipFiles(zip, listOf(csv, gpx, json))
        prefs.edit().putString(LAST_ZIP, zip.absolutePath).putString(LAST_JSON, json.absolutePath)
            .remove(ACTIVE_ID).remove(ACTIVE_COURSE_ID).remove(ACTIVE_COURSE_NAME).remove(ACTIVE_START)
            .remove(ACTIVE_MAX_KM).remove(ACTIVE_SPEED_SUM).remove(ACTIVE_SPEED_COUNT).apply()
        sessionDir.deleteRecursively()
        return RideArchive(ride.sessionId, ride.courseName, ride.startMs, end, maxKm, avgSpeed, csv, gpx, json, zip, learned)
    }

    fun discardActive() {
        val ride = activeRide()
        if (ride != null) File(sessionsRoot, ride.sessionId).deleteRecursively()
        prefs.edit().remove(ACTIVE_ID).remove(ACTIVE_COURSE_ID).remove(ACTIVE_COURSE_NAME).remove(ACTIVE_START)
            .remove(ACTIVE_MAX_KM).remove(ACTIVE_SPEED_SUM).remove(ACTIVE_SPEED_COUNT).apply()
    }

    fun lastZipFile(): File? = prefs.getString(LAST_ZIP, null)?.let(::File)?.takeIf { it.exists() }

    fun lastReportText(): String {
        val file = prefs.getString(LAST_JSON, null)?.let(::File)?.takeIf { it.exists() } ?: return "저장된 주행 리포트가 없습니다."
        return try {
            val o = JSONObject(file.readText())
            val start = o.optLong("startMs")
            val end = o.optLong("endMs")
            val min = ((end - start).coerceAtLeast(0) / 60000L)
            val h = min / 60
            val m = min % 60
            buildString {
                append(o.optString("courseName", "라이딩")); append('\n')
                append("진행 거리: "); append(RideFormatter.one(o.optDouble("maxRouteKm"))); append(" km\n")
                append("기록 시간: "); append(if (h > 0) "${h}시간 ${m}분" else "${m}분"); append('\n')
                append("GPS 이동 평균: "); append(RideFormatter.one(o.optDouble("avgSpeedKmh"))); append(" km/h\n")
                append("학습 반영 구간: "); append(o.optInt("learnedSamplesAdded")); append("개\n")
                append("GPX / CSV / JSON / ZIP 저장 완료")
            }
        } catch (_: Exception) { "리포트를 읽지 못했습니다." }
    }

    private fun readEvents(file: File): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }

    private fun writeGpxFromCsv(csv: File, out: File, courseName: String) {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        out.bufferedWriter().use { w ->
            w.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            w.appendLine("<gpx version=\"1.1\" creator=\"GPX Battery Copilot\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            w.appendLine("  <trk><name>${xmlEscape(courseName)} - 실제 주행</name><trkseg>")
            BufferedReader(InputStreamReader(FileInputStream(csv))).use { r ->
                r.readLine() // header
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split(',')
                    if (p.size < 10) continue
                    val ts = p[0].toLongOrNull() ?: continue
                    val lat = p[1].toDoubleOrNull() ?: continue
                    val lon = p[2].toDoubleOrNull() ?: continue
                    val ele = p[3].toDoubleOrNull()
                    w.append("    <trkpt lat=\"").append(fmt(lat, 7)).append("\" lon=\"").append(fmt(lon, 7)).appendLine("\">")
                    if (ele != null) w.appendLine("      <ele>${fmt(ele, 1)}</ele>")
                    w.appendLine("      <time>${utc.format(Date(ts))}</time>")
                    w.appendLine("    </trkpt>")
                }
            }
            w.appendLine("  </trkseg></trk>")
            w.appendLine("</gpx>")
        }
    }

    private fun zipFiles(zip: File, files: List<File>) {
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            files.forEach { f ->
                z.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^0-9A-Za-z가-힣._-]+"), "_")
    private fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun fmt(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)
}
