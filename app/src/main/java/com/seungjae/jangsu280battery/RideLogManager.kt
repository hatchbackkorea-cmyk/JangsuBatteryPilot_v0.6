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
import kotlin.math.roundToInt


enum class RideMode { PLAN, FREE }

data class ActiveRide(
    val sessionId: String,
    val courseId: String,
    val courseName: String,
    val startMs: Long,
    val mode: RideMode
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
    val learnedSamples: Int,
    val rideMode: RideMode = RideMode.PLAN
)

class RideLogManager(context: Context) {
    companion object {
        private const val PREFS = "ride_log_manager"
        private const val ACTIVE_ID = "active_id"
        private const val ACTIVE_COURSE_ID = "active_course_id"
        private const val ACTIVE_COURSE_NAME = "active_course_name"
        private const val ACTIVE_START = "active_start"
        private const val ACTIVE_MODE = "active_mode"
        private const val ACTIVE_ASCENT_M = "active_ascent_m"
        private const val ACTIVE_MAX_KM = "active_max_km"
        private const val ACTIVE_SPEED_SUM = "active_speed_sum"
        private const val ACTIVE_SPEED_COUNT = "active_speed_count"
        private const val ACTIVE_ASSIST_MODE = "active_assist_mode"
        private const val ACTIVE_ASSIST_PROFILE_ID = "active_assist_profile_id"
        private const val ACTIVE_ASSIST_PROFILE_JSON = "active_assist_profile_json"
        private const val ACTIVE_ASSIST_SOURCE = "active_assist_source"
        private const val ACTIVE_ASSIST_CONFIDENCE = "active_assist_confidence"
        private const val ACTIVE_ASSIST_RAW_CODE = "active_assist_raw_code"
        private const val ASSIST_PROBE_UNTIL = "assist_probe_until"
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
        val mode = runCatching { RideMode.valueOf(prefs.getString(ACTIVE_MODE, RideMode.PLAN.name) ?: RideMode.PLAN.name) }.getOrDefault(RideMode.PLAN)
        val dir = File(sessionsRoot, id)
        if (!dir.exists()) return null
        return ActiveRide(id, courseId, name, start, mode)
    }

    fun isActive(): Boolean = activeRide() != null
    fun isFreeRide(): Boolean = activeRide()?.mode == RideMode.FREE
    fun activeDistanceKm(): Double = prefs.getFloat(ACTIVE_MAX_KM, 0f).toDouble()
    fun activeAscentM(): Double = prefs.getFloat(ACTIVE_ASCENT_M, 0f).toDouble()
    fun activeAssistMode(): AvinoxAssistMode? = prefs.getString(ACTIVE_ASSIST_MODE, null)?.let { runCatching { AvinoxAssistMode.valueOf(it) }.getOrNull() }
    fun activeAssistProfileId(): String? = prefs.getString(ACTIVE_ASSIST_PROFILE_ID, null)
    fun activeAssistSource(): String = prefs.getString(ACTIVE_ASSIST_SOURCE, "") ?: ""
    fun activeAssistConfidence(): String = prefs.getString(ACTIVE_ASSIST_CONFIDENCE, "") ?: ""
    fun activeAssistRawCode(): Int? = prefs.getInt(ACTIVE_ASSIST_RAW_CODE, -1).takeIf { it >= 0 }
    fun activeAssistProfile(): AvinoxAssistProfile? = prefs.getString(ACTIVE_ASSIST_PROFILE_JSON, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            val mode = AvinoxAssistMode.valueOf(o.getString("mode"))
            AvinoxAssistProfile(
                mode = mode,
                assistMin = if (o.has("assistMin")) o.getInt("assistMin") else null,
                assistMax = if (o.has("assistMax")) o.getInt("assistMax") else null,
                maxTorqueNm = if (o.has("maxTorqueNm")) o.getInt("maxTorqueNm") else null,
                maxPowerW = if (o.has("maxPowerW")) o.getInt("maxPowerW") else null,
                motorOverrunStep = if (o.has("motorOverrunStep")) o.getInt("motorOverrunStep") else null,
                startAssistStep = if (o.has("startAssistStep")) o.getInt("startAssistStep") else null,
                continuousAssistStep = if (o.has("continuousAssistStep")) o.getInt("continuousAssistStep") else null,
                sourceNote = o.optString("sourceNote", "주행 기록"),
                savedAtMs = o.optLong("savedAtMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun start(course: CourseMeta): ActiveRide = startPlan(course)

    fun startPlan(course: CourseMeta): ActiveRide {
        activeRide()?.let {
            if (it.courseId == course.id) return it
            error("다른 코스의 주행 기록이 아직 진행 중입니다.")
        }
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + "_" + now.toString().takeLast(5)
        val dir = File(sessionsRoot, id).apply { mkdirs() }
        File(dir, "track.csv").writeText("timestamp_ms,lat,lon,gps_ele_m,speed_kmh,route_km,off_course_m,course_ele_m,estimated_battery_pct,actual_battery_pct,assist_mode,assist_profile_id,assist_mode_source,assist_mode_confidence,assist_raw_code\n")
        File(dir, "events.jsonl").writeText("")
        File(dir, "assist_profiles.jsonl").writeText("")
        File(dir, "raw_ble_probe.csv").writeText("timestamp_ms,assist_mode,assist_profile_id,fff4_hex\n")
        prefs.edit()
            .putString(ACTIVE_ID, id)
            .putString(ACTIVE_COURSE_ID, course.id)
            .putString(ACTIVE_COURSE_NAME, course.name)
            .putLong(ACTIVE_START, now)
            .putString(ACTIVE_MODE, RideMode.PLAN.name)
            .putFloat(ACTIVE_ASCENT_M, 0f)
            .putFloat(ACTIVE_MAX_KM, 0f)
            .putFloat(ACTIVE_SPEED_SUM, 0f)
            .putInt(ACTIVE_SPEED_COUNT, 0)
            .remove(ACTIVE_ASSIST_MODE)
            .remove(ACTIVE_ASSIST_PROFILE_ID)
            .remove(ACTIVE_ASSIST_PROFILE_JSON)
            .remove(ACTIVE_ASSIST_SOURCE)
            .remove(ACTIVE_ASSIST_CONFIDENCE)
            .remove(ACTIVE_ASSIST_RAW_CODE)
            .remove(ASSIST_PROBE_UNTIL)
            .apply()
        recordEvent("RIDE_START", "계획주행 시작", 0.0, null)
        return activeRide()!!
    }

    fun startFree(): ActiveRide {
        activeRide()?.let { return it }
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + "_" + now.toString().takeLast(5)
        val dir = File(sessionsRoot, id).apply { mkdirs() }
        File(dir, "track.csv").writeText("timestamp_ms,lat,lon,gps_ele_m,speed_kmh,route_km,off_course_m,course_ele_m,estimated_battery_pct,actual_battery_pct,assist_mode,assist_profile_id,assist_mode_source,assist_mode_confidence,assist_raw_code\n")
        File(dir, "events.jsonl").writeText("")
        File(dir, "assist_profiles.jsonl").writeText("")
        File(dir, "raw_ble_probe.csv").writeText("timestamp_ms,assist_mode,assist_profile_id,fff4_hex\n")
        prefs.edit()
            .putString(ACTIVE_ID, id)
            .putString(ACTIVE_COURSE_ID, "__FREE_RIDE__")
            .putString(ACTIVE_COURSE_NAME, "임의주행")
            .putLong(ACTIVE_START, now)
            .putString(ACTIVE_MODE, RideMode.FREE.name)
            .putFloat(ACTIVE_MAX_KM, 0f)
            .putFloat(ACTIVE_ASCENT_M, 0f)
            .putFloat(ACTIVE_SPEED_SUM, 0f)
            .putInt(ACTIVE_SPEED_COUNT, 0)
            .remove(ACTIVE_ASSIST_MODE)
            .remove(ACTIVE_ASSIST_PROFILE_ID)
            .remove(ACTIVE_ASSIST_PROFILE_JSON)
            .remove(ACTIVE_ASSIST_SOURCE)
            .remove(ACTIVE_ASSIST_CONFIDENCE)
            .remove(ACTIVE_ASSIST_RAW_CODE)
            .remove(ASSIST_PROBE_UNTIL)
            .apply()
        recordEvent("RIDE_START", "임의주행 시작 · GPX 독립", 0.0, null)
        return activeRide()!!
    }

    fun updateFreeRideStats(distanceKm: Double, ascentM: Double) {
        if (!isFreeRide()) return
        prefs.edit().putFloat(ACTIVE_MAX_KM, distanceKm.coerceAtLeast(0.0).toFloat())
            .putFloat(ACTIVE_ASCENT_M, ascentM.coerceAtLeast(0.0).toFloat()).apply()
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
        val assistMode = activeAssistMode()?.name.orEmpty()
        val assistProfileId = activeAssistProfileId().orEmpty()
        val assistSource = activeAssistSource()
        val assistConfidence = activeAssistConfidence()
        val assistRawCode = activeAssistRawCode()?.toString().orEmpty()
        val estimated = estimatedBatteryPct.takeIf { it.isFinite() }?.let { fmt(it, 1) }.orEmpty()
        val line = listOf(
            now.toString(), fmt(lat, 7), fmt(lon, 7), gpsEle, fmt(speedKmh, 2), fmt(routeKm, 3),
            fmt(offCourseM, 1), fmt(courseElevationM, 1), estimated, actual, assistMode, assistProfileId,
            assistSource, assistConfidence, assistRawCode
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
            activeAssistMode()?.let { put("assistMode", it.name) }
            activeAssistProfileId()?.let { put("assistProfileId", it) }
            activeAssistSource().takeIf { it.isNotBlank() }?.let { put("assistModeSource", it) }
            activeAssistConfidence().takeIf { it.isNotBlank() }?.let { put("assistModeConfidence", it) }
            activeAssistRawCode()?.let { put("assistRawCode", it) }
            if (batteryPct != null) put("batteryPct", batteryPct)
        }
        File(dir, "events.jsonl").appendText(o.toString() + "\n")
    }

    fun setAssistMode(profile: AvinoxAssistProfile, routeKm: Double, batteryPct: Double?) {
        val ride = activeRide() ?: return
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(ACTIVE_ASSIST_MODE, profile.mode.name)
            .putString(ACTIVE_ASSIST_PROFILE_ID, profile.profileId)
            .putString(ACTIVE_ASSIST_PROFILE_JSON, profile.toJson().toString())
            .putString(ACTIVE_ASSIST_SOURCE, "MANUAL")
            .putString(ACTIVE_ASSIST_CONFIDENCE, "CONFIRMED")
            .remove(ACTIVE_ASSIST_RAW_CODE)
            .putLong(ASSIST_PROBE_UNTIL, now + 12_000L)
            .apply()
        val dir = File(sessionsRoot, ride.sessionId)
        val snapshot = profile.toJson().apply {
            put("selectedAtMs", now)
            put("routeKm", routeKm)
            if (batteryPct != null) put("batteryPct", batteryPct)
        }
        File(dir, "assist_profiles.jsonl").appendText(snapshot.toString() + "\n")
        recordEvent("ASSIST_MODE", "${profile.mode.label} · ${profile.compactText()} · BLE raw 12초 probe", routeKm, batteryPct)
    }

    /** Automatic BLE mode candidate. AMBIGUOUS segments are recorded but excluded from validated mode learning. */
    fun setDetectedAssistMode(profile: AvinoxAssistProfile, routeKm: Double, batteryPct: Double?, confidence: String, rawCode: Int) {
        if (activeRide() == null) return
        val oldMode = activeAssistMode()
        val oldConfidence = activeAssistConfidence()
        val oldCode = activeAssistRawCode()
        val normalized = if (confidence == "HIGH") "HIGH" else "AMBIGUOUS"
        prefs.edit()
            .putString(ACTIVE_ASSIST_MODE, profile.mode.name)
            .putString(ACTIVE_ASSIST_PROFILE_ID, profile.profileId)
            .putString(ACTIVE_ASSIST_PROFILE_JSON, profile.toJson().toString())
            .putString(ACTIVE_ASSIST_SOURCE, "BLE_AUTO")
            .putString(ACTIVE_ASSIST_CONFIDENCE, normalized)
            .putInt(ACTIVE_ASSIST_RAW_CODE, rawCode)
            .apply()
        if (oldMode != profile.mode || oldConfidence != normalized || oldCode != rawCode) {
            val ride = activeRide() ?: return
            val now = System.currentTimeMillis()
            val snapshot = profile.toJson().apply {
                put("selectedAtMs", now)
                put("routeKm", routeKm)
                put("modeSource", "BLE_AUTO")
                put("modeConfidence", normalized)
                put("rawCode", rawCode)
                if (batteryPct != null) put("batteryPct", batteryPct)
            }
            File(File(sessionsRoot, ride.sessionId), "assist_profiles.jsonl").appendText(snapshot.toString() + "\n")
            recordEvent("ASSIST_AUTO_DETECT", "${profile.mode.label} · $normalized · raw=$rawCode", routeKm, batteryPct)
        }
    }

    /** User confirms the bike display during detector validation. */
    fun confirmDetectedAssistMode(profile: AvinoxAssistProfile, routeKm: Double, batteryPct: Double?, rawCode: Int?) {
        if (activeRide() == null) return
        prefs.edit()
            .putString(ACTIVE_ASSIST_MODE, profile.mode.name)
            .putString(ACTIVE_ASSIST_PROFILE_ID, profile.profileId)
            .putString(ACTIVE_ASSIST_PROFILE_JSON, profile.toJson().toString())
            .putString(ACTIVE_ASSIST_SOURCE, "USER_CONFIRMED")
            .putString(ACTIVE_ASSIST_CONFIDENCE, "CONFIRMED")
            .apply { if (rawCode != null) putInt(ACTIVE_ASSIST_RAW_CODE, rawCode) else remove(ACTIVE_ASSIST_RAW_CODE) }
            .apply()
        val ride = activeRide() ?: return
        val now = System.currentTimeMillis()
        val snapshot = profile.toJson().apply {
            put("selectedAtMs", now)
            put("routeKm", routeKm)
            put("modeSource", "USER_CONFIRMED")
            put("modeConfidence", "CONFIRMED")
            rawCode?.let { put("rawCode", it) }
            if (batteryPct != null) put("batteryPct", batteryPct)
        }
        File(File(sessionsRoot, ride.sessionId), "assist_profiles.jsonl").appendText(snapshot.toString() + "\n")
        recordEvent("ASSIST_USER_CONFIRM", "${profile.mode.label} · detector verification · raw=${rawCode ?: -1}", routeKm, batteryPct)
    }

    /** Keep a compact continuous detector trace; one long FFF4 packet per second is small enough for field validation. */
    fun recordAutoModeDetection(timestampMs: Long, detection: AvinoxAssistDetection, bytes: ByteArray) {
        val ride = activeRide() ?: return
        val file = File(File(sessionsRoot, ride.sessionId), "assist_auto_detect.csv")
        if (!file.exists()) file.writeText("timestamp_ms,primary,alternate,confidence,raw_code,active_mode,active_confidence,fff4_hex\n")
        val hex = bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        file.appendText(listOf(
            timestampMs.toString(), detection.primary.name, detection.alternate?.name.orEmpty(), detection.confidence,
            detection.rawCode.toString(), activeAssistMode()?.name.orEmpty(), activeAssistConfidence(), hex
        ).joinToString(",") + "\n")
    }

    fun restartAssistProbeWindow(durationMs: Long = 12_000L) {
        if (activeRide() == null || activeAssistMode() == null) return
        prefs.edit().putLong(ASSIST_PROBE_UNTIL, System.currentTimeMillis() + durationMs.coerceIn(3_000L, 60_000L)).apply()
    }

    fun recordRawBleNotification(timestampMs: Long, bytes: ByteArray) {
        val ride = activeRide() ?: return
        val probeUntil = prefs.getLong(ASSIST_PROBE_UNTIL, 0L)
        if (timestampMs > probeUntil || probeUntil <= 0L) return
        val mode = activeAssistMode()?.name ?: return
        val profileId = activeAssistProfileId().orEmpty()
        val hex = bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        File(File(sessionsRoot, ride.sessionId), "raw_ble_probe.csv")
            .appendText("$timestampMs,$mode,$profileId,$hex\n")
    }

    fun activeAverageSpeedKmh(): Double {
        val count = prefs.getInt(ACTIVE_SPEED_COUNT, 0)
        return if (count > 0) prefs.getFloat(ACTIVE_SPEED_SUM, 0f).toDouble() / count else 0.0
    }

    fun activeAssistSummaryText(): String {
        val mode = activeAssistMode() ?: return "모드 자동감지 대기 · Avinox BLE 연결 확인"
        val profile = activeAssistProfile()
        val conf = activeAssistConfidence().takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "${mode.label}$conf · ${profile?.compactText() ?: activeAssistProfileId().orEmpty()}"
    }

    fun activeSummaryText(): String {
        val ride = activeRide() ?: return "현재 진행 중인 주행이 없습니다."
        val maxKm = prefs.getFloat(ACTIVE_MAX_KM, 0f).toDouble()
        val count = prefs.getInt(ACTIVE_SPEED_COUNT, 0)
        val avg = if (count > 0) prefs.getFloat(ACTIVE_SPEED_SUM, 0f) / count else 0f
        val elapsed = ((System.currentTimeMillis() - ride.startMs).coerceAtLeast(0) / 60000L)
        val h = elapsed / 60
        val m = elapsed % 60
        val modeText = if (ride.mode == RideMode.FREE) "임의주행 · GPX 독립" else "계획주행 · ${ride.courseName}"
        val ascentText = if (ride.mode == RideMode.FREE) " · 상승 ${activeAscentM().toInt()}m" else ""
        return "$modeText\n진행 ${RideFormatter.one(maxKm)} km$ascentText · ${if (h > 0) "${h}시간 ${m}분" else "${m}분"}\n이동 평균 ${if (avg > 0f) RideFormatter.one(avg.toDouble()) + " km/h" else "-"}\nAvinox 어시스트: ${activeAssistSummaryText()}\n로그는 주행 중 계속 자동 저장 중입니다."
    }

    fun finalizeRide(
        course: CourseData,
        actualStore: BatteryActualStore,
        chargingStations: List<ChargingStation> = emptyList(),
        testMode: Boolean = false
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

        val learned = 0
        val json = File(outDir, "$baseName.json")
        val assistProfilesOut = copyOptionalSessionFile(sessionDir, outDir, "assist_profiles.jsonl", baseName)
        val rawBleProbeOut = copyOptionalSessionFile(sessionDir, outDir, "raw_ble_probe.csv", baseName)
        val autoModeDetectOut = copyOptionalSessionFile(sessionDir, outDir, "assist_auto_detect.csv", baseName)
        val assistStats = buildAssistModeStats(sourceCsv, events)
        val summary = JSONObject().apply {
            put("sessionId", ride.sessionId)
            put("courseId", ride.courseId)
            put("courseName", ride.courseName)
            put("rideMode", ride.mode.name)
            put("startMs", ride.startMs)
            put("endMs", end)
            put("durationSec", ((end - ride.startMs).coerceAtLeast(0L) / 1000L))
            put("maxRouteKm", maxKm)
            put("avgSpeedKmh", avgSpeed)
            put("courseTotalKm", course.totalKm)
            put("courseAscentM", course.totalAscentM)
            put("courseDescentM", course.totalDescentM)
            put("courseHasElevation", course.hasElevation)
            put("testMode", testMode)
            put("learningStatus", if (testMode) "TEST_MODE_SKIPPED" else "PENDING")
            put("learnedSamplesAdded", learned)
            put("chargingPlan", JSONArray().apply {
                chargingStations.sortedBy { it.routeKm }.forEach { s -> put(JSONObject().apply {
                    put("name", s.name); put("routeKm", s.routeKm); put("chargeToPct", s.chargeToPct)
                    put("source", s.source); put("distanceFromRouteM", s.distanceFromRouteM); put("detourKm", s.detourKm)
                    if (s.address.isNotBlank()) put("address", s.address)
                }) }
            })
            put("assistModeStats", assistStats)
            put("assistProfilesFile", assistProfilesOut?.name ?: "")
            put("rawBleProbeFile", rawBleProbeOut?.name ?: "")
            put("autoModeDetectFile", autoModeDetectOut?.name ?: "")
            put("events", JSONArray().apply { events.forEach { put(it) } })
            put("actualBattery", JSONArray().apply {
                actualStore.entries().forEach { e -> put(JSONObject().apply {
                    put("percent", e.percent); put("routeKm", e.routeKm); put("timestampMs", e.timestampMs); put("kind", e.kind.name); put("source", e.source.name)
                }) }
            })
        }
        json.writeText(summary.toString(2))

        val zip = File(outDir, "$baseName.zip")
        zipFiles(zip, listOfNotNull(csv, gpx, json, assistProfilesOut, rawBleProbeOut, autoModeDetectOut))
        prefs.edit().putString(LAST_ZIP, zip.absolutePath).putString(LAST_JSON, json.absolutePath)
            .remove(ACTIVE_ID).remove(ACTIVE_COURSE_ID).remove(ACTIVE_COURSE_NAME).remove(ACTIVE_START)
            .remove(ACTIVE_MAX_KM).remove(ACTIVE_ASCENT_M).remove(ACTIVE_MODE).remove(ACTIVE_SPEED_SUM).remove(ACTIVE_SPEED_COUNT)
            .remove(ACTIVE_ASSIST_MODE).remove(ACTIVE_ASSIST_PROFILE_ID).remove(ACTIVE_ASSIST_PROFILE_JSON).remove(ACTIVE_ASSIST_SOURCE).remove(ACTIVE_ASSIST_CONFIDENCE).remove(ACTIVE_ASSIST_RAW_CODE).remove(ASSIST_PROBE_UNTIL).apply()
        sessionDir.deleteRecursively()
        val archive = RideArchive(ride.sessionId, ride.courseName, ride.startMs, end, maxKm, avgSpeed, csv, gpx, json, zip, learned, ride.mode)
        RiderServerSync(app).enqueueRide(archive)
        return archive
    }

    fun finalizeFreeRide(
        actualStore: BatteryActualStore,
        testMode: Boolean = false
    ): RideArchive {
        val ride = activeRide() ?: error("진행 중인 주행이 없습니다.")
        require(ride.mode == RideMode.FREE) { "임의주행 세션이 아닙니다." }
        val end = System.currentTimeMillis()
        val maxKm = activeDistanceKm()
        val ascentM = activeAscentM()
        recordEvent("RIDE_END", "임의주행 종료", maxKm, actualStore.latest()?.percent)
        val sessionDir = File(sessionsRoot, ride.sessionId)
        val sourceCsv = File(sessionDir, "track.csv")
        val events = readEvents(File(sessionDir, "events.jsonl"))
        val speedCount = prefs.getInt(ACTIVE_SPEED_COUNT, 0)
        val avgSpeed = if (speedCount > 0) prefs.getFloat(ACTIVE_SPEED_SUM, 0f).toDouble() / speedCount else 0.0
        val exportRoot = (app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(app.filesDir, "exports"))
        val outDir = File(exportRoot, "GPXBatteryCopilot/RideLogs").apply { mkdirs() }
        val baseName = "${ride.sessionId}_free_ride"
        val csv = File(outDir, "$baseName.csv")
        sourceCsv.copyTo(csv, overwrite = true)
        val gpx = File(outDir, "$baseName.gpx")
        writeGpxFromCsv(sourceCsv, gpx, "임의주행")
        val json = File(outDir, "$baseName.json")
        val assistProfilesOut = copyOptionalSessionFile(sessionDir, outDir, "assist_profiles.jsonl", baseName)
        val rawBleProbeOut = copyOptionalSessionFile(sessionDir, outDir, "raw_ble_probe.csv", baseName)
        val autoModeDetectOut = copyOptionalSessionFile(sessionDir, outDir, "assist_auto_detect.csv", baseName)
        val assistStats = buildAssistModeStats(sourceCsv, events)
        val actualEntries = actualStore.entries()
        val actualConsumed = cumulativeConsumed(actualEntries)
        val summary = JSONObject().apply {
            put("sessionId", ride.sessionId)
            put("courseId", "__FREE_RIDE__")
            put("courseName", "임의주행")
            put("rideMode", RideMode.FREE.name)
            put("startMs", ride.startMs)
            put("endMs", end)
            put("durationSec", ((end - ride.startMs).coerceAtLeast(0L) / 1000L))
            put("maxRouteKm", maxKm)
            put("gpsAscentM", ascentM)
            put("avgSpeedKmh", avgSpeed)
            put("testMode", testMode)
            put("learningStatus", if (testMode) "TEST_MODE_SKIPPED" else "WAITING_FOR_FIT")
            if (actualConsumed != null) put("actualConsumedPct", actualConsumed)
            actualEntries.firstOrNull()?.let { put("actualCoverageStartKm", it.routeKm); put("actualStartBatteryPct", it.percent) }
            actualEntries.lastOrNull()?.let { put("actualEndBatteryPct", it.percent) }
            put("postRideFitAttached", false)
            put("postRideAvinox", JSONObject())
            put("assistModeStats", assistStats)
            put("assistProfilesFile", assistProfilesOut?.name ?: "")
            put("rawBleProbeFile", rawBleProbeOut?.name ?: "")
            put("autoModeDetectFile", autoModeDetectOut?.name ?: "")
            put("events", JSONArray().apply { events.forEach { put(it) } })
            put("actualBattery", JSONArray().apply {
                actualEntries.forEach { e -> put(JSONObject().apply {
                    put("percent", e.percent); put("routeKm", e.routeKm); put("timestampMs", e.timestampMs); put("kind", e.kind.name); put("source", e.source.name)
                }) }
            })
        }
        json.writeText(summary.toString(2))
        val zip = File(outDir, "$baseName.zip")
        zipFiles(zip, listOfNotNull(csv, gpx, json, assistProfilesOut, rawBleProbeOut, autoModeDetectOut))
        prefs.edit().putString(LAST_ZIP, zip.absolutePath).putString(LAST_JSON, json.absolutePath)
            .remove(ACTIVE_ID).remove(ACTIVE_COURSE_ID).remove(ACTIVE_COURSE_NAME).remove(ACTIVE_START)
            .remove(ACTIVE_MAX_KM).remove(ACTIVE_ASCENT_M).remove(ACTIVE_MODE).remove(ACTIVE_SPEED_SUM).remove(ACTIVE_SPEED_COUNT)
            .remove(ACTIVE_ASSIST_MODE).remove(ACTIVE_ASSIST_PROFILE_ID).remove(ACTIVE_ASSIST_PROFILE_JSON).remove(ACTIVE_ASSIST_SOURCE).remove(ACTIVE_ASSIST_CONFIDENCE).remove(ACTIVE_ASSIST_RAW_CODE).remove(ASSIST_PROBE_UNTIL).apply()
        sessionDir.deleteRecursively()
        val archive = RideArchive(ride.sessionId, "임의주행", ride.startMs, end, maxKm, avgSpeed, csv, gpx, json, zip, 0, RideMode.FREE)
        RiderServerSync(app).enqueueRide(archive)
        return archive
    }

    fun attachFitToLastRide(uri: android.net.Uri, learning: BatteryLearningStore): String {
        val json = lastJsonFile() ?: error("최근 주행 기록이 없습니다.")
        val root = JSONObject(json.readText())
        val analysis = HistoricalRideImporter.analyze(app, uri, HistoricalSourceType.FIT)
        val preLearnPlan = BatteryPlan(analysis.course, learning, emptyList())
        val modelBefore = preLearnPlan.predictedTotalUsePct()
        val outDir = json.parentFile ?: error("저장 폴더를 찾지 못했습니다.")
        val fitName = json.nameWithoutExtension + "_avinox.fit"
        val fitFile = File(outDir, fitName)
        app.contentResolver.openInputStream(uri)?.use { input -> fitFile.outputStream().use { input.copyTo(it) } }
            ?: error("FIT 파일을 읽지 못했습니다.")
        root.put("postRideFitAttached", true)
        root.put("postRideFit", JSONObject().apply {
            put("fileName", fitFile.name)
            put("distanceKm", analysis.distanceKm)
            put("ascentM", analysis.ascentM)
            put("descentM", analysis.descentM)
            if (analysis.durationSec != null) put("durationSec", analysis.durationSec)
            if (analysis.avgSpeedKph != null) put("avgSpeedKmh", analysis.avgSpeedKph)
            put("modelPredictedTotalBeforeLearningPct", modelBefore)
            put("dataQualityScore", analysis.dataQualityScore)
        })
        root.put("learningStatus", "FIT_ATTACHED_NOT_LEARNED")
        json.writeText(root.toString(2))
        rebuildLastZip(extraFiles = listOf(fitFile))
        return "FIT 연결 완료 · ${RideFormatter.one(analysis.distanceKm)} km · 상승 ${analysis.ascentM.roundToInt()}m\n우리 모델 사후 예상 ${format1(modelBefore)}% · 아직 학습에는 미반영"
    }

    fun learnLastFreeRideFromFit(learning: BatteryLearningStore): Int {
        val json = lastJsonFile() ?: return 0
        val root = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return 0
        if (root.optString("rideMode") != RideMode.FREE.name) return 0
        if (root.optString("learningStatus") == "USED") return root.optInt("learnedSamplesAdded", 0)
        val fit = root.optJSONObject("postRideFit") ?: return 0
        val fitFile = File(json.parentFile, fit.optString("fileName"))
        if (!fitFile.exists()) return 0
        val analysis = HistoricalRideImporter.analyzeFile(fitFile, HistoricalSourceType.FIT)
        val arr = root.optJSONArray("actualBattery") ?: JSONArray()
        val firstZipTs = (0 until arr.length()).asSequence().mapNotNull { idx ->
            arr.optJSONObject(idx)?.optLong("timestampMs", 0L)?.takeIf { it > 0L }
        }.firstOrNull()
        val rawFitStart = analysis.telemetry.mapNotNull { it.timestampMs }.minOrNull()
        val fitEpochOffset = if (rawFitStart != null && firstZipTs != null) {
            val fitToUnix = 631_065_600_000L
            if (kotlin.math.abs(rawFitStart + fitToUnix - firstZipTs) < kotlin.math.abs(rawFitStart - firstZipTs)) fitToUnix else 0L
        } else 0L
        val entries = mutableListOf<ActualBatteryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = runCatching { ActualEntryKind.valueOf(o.optString("kind", "RIDING")) }.getOrDefault(ActualEntryKind.RIDING)
            val pct = o.optDouble("percent", Double.NaN)
            val phoneKm = o.optDouble("routeKm", Double.NaN)
            val ts = o.optLong("timestampMs", 0L)
            if (pct.isFinite() && phoneKm.isFinite()) {
                val nearest = if (ts > 0L) analysis.telemetry.asSequence()
                    .mapNotNull { p -> p.timestampMs?.let { t -> kotlin.math.abs((t + fitEpochOffset) - ts) to p.routeKm } }
                    .minByOrNull { it.first } else null
                val fitKm = if (nearest != null && nearest.first <= 300_000L) {
                    nearest.second
                } else {
                    val phoneTotal = root.optDouble("maxRouteKm", 0.0)
                    if (phoneTotal > 0.1) phoneKm * (analysis.course.totalKm / phoneTotal) else phoneKm
                }
                val source = runCatching { ActualEntrySource.valueOf(o.optString("source", "MANUAL")) }.getOrDefault(ActualEntrySource.MANUAL)
                entries += ActualBatteryEntry(pct, fitKm.coerceIn(0.0, analysis.course.totalKm), ts, kind, source)
            }
        }
        val modeWindows = readAssistModeWindows(root, json.parentFile)
        if (modeWindows.isEmpty()) {
            root.put("learningStatus", "BLOCKED_NO_MODE_LOG")
            root.put("learnedSamplesAdded", 0)
            root.put("learningDecisionMs", System.currentTimeMillis())
            root.put("learningNote", "v0.18.1부터 임의주행 학습은 Eco/Auto/Trail/Turbo 모드 로그가 있는 검증 세션만 허용")
            json.writeText(root.toString(2))
            rebuildLastZip(extraFiles = listOf(fitFile))
            return 0
        }
        val learned = learning.trainModeSeparatedRide(
            root.optString("sessionId", "free_ride"),
            analysis.course,
            entries,
            analysis.telemetry,
            modeWindows,
            analysis.dataQualityScore
        )
        root.put("learningStatus", if (learned > 0) "USED" else "BLOCKED_NO_CLEAN_INTERVALS")
        root.put("learnedSamplesAdded", learned)
        root.put("learningDecisionMs", System.currentTimeMillis())
        root.put("learningModePolicy", "모드 전환 없는 SOC 구간만 모드별 분리 학습 · 100→98% 제외")
        json.writeText(root.toString(2))
        rebuildLastZip(extraFiles = listOf(fitFile))
        return learned
    }

    private fun readAssistModeWindows(root: JSONObject, dir: File?): List<AssistModeWindow> {
        val parent = dir ?: return emptyList()
        val fileName = root.optString("assistProfilesFile", "")
        if (fileName.isBlank()) return emptyList()
        val file = File(parent, fileName)
        if (!file.exists()) return emptyList()
        data class Mark(val at: Long, val mode: AvinoxAssistMode, val profileId: String, val confidence: String)
        val marks = file.readLines().mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val at = o.optLong("selectedAtMs", 0L)
            val mode = runCatching { AvinoxAssistMode.valueOf(o.optString("mode", "")) }.getOrNull()
            val profileId = o.optString("profileId", "")
            val confidence = o.optString("modeConfidence", "CONFIRMED")
            if (at <= 0L || mode == null || profileId.isBlank()) null else Mark(at, mode, profileId, confidence)
        }.sortedBy { it.at }
        if (marks.isEmpty()) return emptyList()
        val rideEnd = root.optLong("endMs", marks.last().at + 1L).coerceAtLeast(marks.last().at + 1L)
        return marks.mapIndexedNotNull { index, m ->
            val end = if (index + 1 < marks.size) marks[index + 1].at - 1L else rideEnd
            if (m.confidence == "AMBIGUOUS") null else AssistModeWindow(m.at, end.coerceAtLeast(m.at), m.mode, m.profileId)
        }
    }

    fun setLastRideAvinox(eco: Double?, auto: Double?, trail: Double?, turbo: Double?, selected: AvinoxRideMode?): String {
        val json = lastJsonFile() ?: error("최근 주행 기록이 없습니다.")
        val root = JSONObject(json.readText())
        val values = JSONObject().apply {
            eco?.let { put("ECO", it.coerceAtLeast(0.0)) }
            auto?.let { put("AUTO", it.coerceAtLeast(0.0)) }
            trail?.let { put("TRAIL", it.coerceAtLeast(0.0)) }
            turbo?.let { put("TURBO", it.coerceAtLeast(0.0)) }
            selected?.let { put("selectedMode", it.name) }
        }
        root.put("postRideAvinox", values)
        json.writeText(root.toString(2))
        rebuildLastZip()
        return lastComparisonText()
    }

    fun lastComparisonText(): String {
        val json = lastJsonFile() ?: return "저장된 주행 기록이 없습니다."
        val o = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return "주행 기록을 읽지 못했습니다."
        val actual = o.optDouble("actualConsumedPct", Double.NaN).takeIf { it.isFinite() }
        val fit = o.optJSONObject("postRideFit")
        val model = fit?.optDouble("modelPredictedTotalBeforeLearningPct", Double.NaN)?.takeIf { it.isFinite() }
        val av = o.optJSONObject("postRideAvinox")
        val selectedName = av?.optString("selectedMode", "").orEmpty()
        val avinox = if (selectedName.isNotBlank()) av?.optDouble(selectedName, Double.NaN)?.takeIf { it.isFinite() } else null
        return buildString {
            append(if (o.optString("rideMode") == RideMode.FREE.name) "임의주행 사후 비교" else "계획주행 사후 비교")
            append("\n실제 누적 소비: "); append(actual?.let { format1(it) + "%" } ?: "—")
            val coverage = o.optDouble("actualCoverageStartKm", Double.NaN)
            if (coverage.isFinite() && coverage > 0.5) append(" · ${format1(coverage)}km 첫 입력부터")
            append("\n우리 모델(FIT 기준·학습 전): "); append(model?.let { format1(it) + "%" } ?: "FIT 미연결")
            if (actual != null && model != null) append(" · 오차 ${signed1(model - actual)}%")
            append("\nAvinox"); if (selectedName.isNotBlank()) append(" $selectedName")
            append(": "); append(avinox?.let { format1(it) + "%" } ?: "미입력")
            if (actual != null && avinox != null) append(" · 오차 ${signed1(avinox - actual)}%")
            fit?.let { append("\nFIT: ${format1(it.optDouble("distanceKm", 0.0))} km · 상승 ${it.optDouble("ascentM", 0.0).roundToInt()}m") }
        }
    }

    fun lastJsonFile(): File? = prefs.getString(LAST_JSON, null)?.let(::File)?.takeIf { it.exists() }

    private fun cumulativeConsumed(entries: List<ActualBatteryEntry>): Double? {
        if (entries.isEmpty()) return null
        var chargeAdded = 0.0
        var arrival: ActualBatteryEntry? = null
        entries.sortedBy { it.timestampMs }.forEach { e ->
            when (e.kind) {
                ActualEntryKind.ARRIVAL -> arrival = e
                ActualEntryKind.POST_CHARGE -> {
                    val a = arrival
                    if (a != null) chargeAdded += (e.percent - a.percent).coerceAtLeast(0.0)
                    arrival = null
                }
                ActualEntryKind.RIDING -> Unit
            }
        }
        return (entries.first().percent + chargeAdded - entries.last().percent).coerceAtLeast(0.0)
    }

    private fun rebuildLastZip(extraFiles: List<File> = emptyList()) {
        val json = lastJsonFile() ?: return
        val zip = lastZipFile() ?: return
        val base = json.nameWithoutExtension
        val dir = json.parentFile ?: return
        val root = runCatching { JSONObject(json.readText()) }.getOrNull()
        val attachedFit = root?.optJSONObject("postRideFit")?.optString("fileName")?.takeIf { it.isNotBlank() }?.let { File(dir, it) }
        val assistProfiles = root?.optString("assistProfilesFile")?.takeIf { it.isNotBlank() }?.let { File(dir, it) }
        val rawBleProbe = root?.optString("rawBleProbeFile")?.takeIf { it.isNotBlank() }?.let { File(dir, it) }
        val autoModeDetect = root?.optString("autoModeDetectFile")?.takeIf { it.isNotBlank() }?.let { File(dir, it) }
        val files = listOfNotNull(File(dir, "$base.csv"), File(dir, "$base.gpx"), json, attachedFit, assistProfiles, rawBleProbe, autoModeDetect).filter { it.exists() } + extraFiles.filter { it.exists() }
        zipFiles(zip, files.distinctBy { it.absolutePath })
    }

    private fun format1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun signed1(v: Double): String = (if (v >= 0) "+" else "") + format1(v)

    fun learnFromArchive(archive: RideArchive, course: CourseData, learning: BatteryLearningStore): Int {
        val json = archive.jsonFile
        if (!json.exists()) return 0
        val root = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return 0
        if (root.optBoolean("testMode", false)) {
            updateLearningDecision(archive, "TEST_MODE_SKIPPED", 0)
            return 0
        }
        if (root.optString("learningStatus") == "USED") return root.optInt("learnedSamplesAdded", 0)
        val arr = root.optJSONArray("actualBattery") ?: JSONArray()
        val entries = mutableListOf<ActualBatteryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = runCatching { ActualEntryKind.valueOf(o.optString("kind", "RIDING")) }
                .getOrDefault(ActualEntryKind.RIDING)
            val source = runCatching { ActualEntrySource.valueOf(o.optString("source", "MANUAL")) }
                .getOrDefault(ActualEntrySource.MANUAL)
            entries += ActualBatteryEntry(
                percent = o.optDouble("percent", Double.NaN),
                routeKm = o.optDouble("routeKm", Double.NaN),
                timestampMs = o.optLong("timestampMs", 0L),
                kind = kind,
                source = source
            )
        }
        val valid = entries.filter { it.percent.isFinite() && it.routeKm.isFinite() }
        val modeWindows = readAssistModeWindows(root, json.parentFile)
        if (modeWindows.isEmpty()) {
            updateLearningDecision(archive, "BLOCKED_NO_MODE_LOG", 0)
            return 0
        }
        // v0.18.2부터 계획주행도 임의주행과 동일한 클린 모드 분리 정책을 사용한다.
        // 선택 모드가 확정되지 않은 구간(AMBIGUOUS)은 readAssistModeWindows에서 경계만 남기고 학습에서 제외된다.
        // 계획주행에는 FIT 텔레메트리가 아직 붙지 않으므로 파워 보조값은 비워 두고,
        // GPX 거리/상승량 + 실제 BLE SOC + 검증된 모드만 학습한다.
        val learned = learning.trainModeSeparatedRide(
            archive.sessionId,
            course,
            valid,
            emptyList(),
            modeWindows,
            100
        )
        updateLearningDecision(archive, if (learned > 0) "USED" else "BLOCKED_NO_CLEAN_INTERVALS", learned)
        return learned
    }

    fun skipArchiveLearning(archive: RideArchive, reason: String = "SKIPPED") {
        updateLearningDecision(archive, reason, 0)
    }

    private fun updateLearningDecision(archive: RideArchive, status: String, learnedSamples: Int) {
        val json = archive.jsonFile
        if (!json.exists()) return
        val root = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return
        root.put("learningStatus", status)
        root.put("learnedSamplesAdded", learnedSamples)
        root.put("learningDecisionMs", System.currentTimeMillis())
        json.writeText(root.toString(2))
        val dir = archive.jsonFile.parentFile
        val assistProfiles = root.optString("assistProfilesFile").takeIf { it.isNotBlank() }?.let { name -> dir?.let { File(it, name) } }
        val rawBleProbe = root.optString("rawBleProbeFile").takeIf { it.isNotBlank() }?.let { name -> dir?.let { File(it, name) } }
        val autoModeDetect = root.optString("autoModeDetectFile").takeIf { it.isNotBlank() }?.let { name -> dir?.let { File(it, name) } }
        zipFiles(archive.zipFile, listOfNotNull(archive.csvFile, archive.gpxFile, archive.jsonFile, assistProfiles, rawBleProbe, autoModeDetect).filter { it.exists() })
    }

    fun discardActive() {
        val ride = activeRide()
        if (ride != null) File(sessionsRoot, ride.sessionId).deleteRecursively()
        prefs.edit().remove(ACTIVE_ID).remove(ACTIVE_COURSE_ID).remove(ACTIVE_COURSE_NAME).remove(ACTIVE_START)
            .remove(ACTIVE_MAX_KM).remove(ACTIVE_ASCENT_M).remove(ACTIVE_MODE).remove(ACTIVE_SPEED_SUM).remove(ACTIVE_SPEED_COUNT)
            .remove(ACTIVE_ASSIST_MODE).remove(ACTIVE_ASSIST_PROFILE_ID).remove(ACTIVE_ASSIST_PROFILE_JSON).remove(ACTIVE_ASSIST_SOURCE).remove(ACTIVE_ASSIST_CONFIDENCE).remove(ACTIVE_ASSIST_RAW_CODE).remove(ASSIST_PROBE_UNTIL).apply()
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
                when (o.optString("learningStatus", "PENDING")) {
                    "USED" -> { append("학습 반영: "); append(o.optInt("learnedSamplesAdded")); append("개 구간\n") }
                    "SKIPPED" -> append("학습: 사용 안 함\n")
                    "TEST_MODE_SKIPPED" -> append("학습: 테스트 모드 제외\n")
                    "BLOCKED_NO_MODE_LOG" -> append("학습: 모드 로그 없음 · 검증용 데이터로만 보관\n")
                    "BLOCKED_NO_CLEAN_INTERVALS" -> append("학습: 모드가 섞이지 않은 유효 SOC 구간 없음\n")
                    else -> append("학습: 선택 대기\n")
                }
                if (o.optString("rideMode") == RideMode.FREE.name) {
                    append("임의주행 · GPX 독립 기록\n")
                    val actual = o.optDouble("actualConsumedPct", Double.NaN)
                    if (actual.isFinite()) append("실제 누적 소비: ${format1(actual)}%\n")
                    append(if (o.optBoolean("postRideFitAttached", false)) "FIT: 연결됨\n" else "FIT: 사후 연결 대기\n")
                }
                val assist = o.optJSONArray("assistModeStats")
                if (assist != null && assist.length() > 0) {
                    append("Avinox 모드 검증: ${assist.length()}개 프로필\n")
                    for (i in 0 until assist.length().coerceAtMost(6)) {
                        val a = assist.optJSONObject(i) ?: continue
                        append("· ${a.optString("mode")}: ${format1(a.optDouble("distanceKm", 0.0))}km · SOC ${format1(a.optDouble("verifiedSocDropPct", 0.0))}%\n")
                    }
                }
                append("GPX / CSV / JSON / ZIP 저장 완료")
            }
        } catch (_: Exception) { "리포트를 읽지 못했습니다." }
    }

    private fun copyOptionalSessionFile(sessionDir: File, outDir: File, sourceName: String, baseName: String): File? {
        val source = File(sessionDir, sourceName)
        if (!source.exists() || source.length() == 0L) return null
        val out = File(outDir, "${baseName}_${sourceName}")
        source.copyTo(out, overwrite = true)
        return out
    }

    private data class AssistAccum(
        var durationSec: Double = 0.0,
        var distanceKm: Double = 0.0,
        var ascentM: Double = 0.0,
        var verifiedSocDropPct: Double = 0.0
    )

    private fun buildAssistModeStats(csv: File, events: List<JSONObject>): JSONArray {
        val stats = linkedMapOf<String, AssistAccum>()
        if (csv.exists()) {
            var prev: List<String>? = null
            var segmentKey: String? = null
            var segmentElevation = ElevationGainFilter()
            var segmentLastAscent = 0.0
            csv.bufferedReader().use { r ->
                r.readLine()
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split(',')
                    if (p.size < 12) continue
                    val prior = prev
                    if (prior != null && prior.size >= 12) {
                        val mode = prior[10]
                        val profileId = prior[11]
                        val priorConfidence = prior.getOrNull(13).orEmpty()
                        val currentConfidence = p.getOrNull(13).orEmpty()
                        val confidenceOk = priorConfidence != "AMBIGUOUS" && currentConfidence != "AMBIGUOUS"
                        if (confidenceOk && mode.isNotBlank() && profileId.isNotBlank() && p[10] == mode && p[11] == profileId) {
                            val key = "$mode|$profileId"
                            val a = stats.getOrPut(key) { AssistAccum() }
                            val t0 = prior[0].toLongOrNull()
                            val t1 = p[0].toLongOrNull()
                            if (t0 != null && t1 != null && t1 > t0 && t1 - t0 <= 15000L) a.durationSec += (t1 - t0) / 1000.0
                            val d0 = prior[5].toDoubleOrNull()
                            val d1 = p[5].toDoubleOrNull()
                            if (d0 != null && d1 != null && d1 >= d0 && d1 - d0 <= 0.5) a.distanceKm += d1 - d0

                            if (segmentKey != key) {
                                segmentKey = key
                                segmentElevation = ElevationGainFilter()
                                segmentLastAscent = 0.0
                                prior[3].toDoubleOrNull()?.let { segmentElevation.update(it) }
                            }
                            p[3].toDoubleOrNull()?.let { ele ->
                                val nowAscent = segmentElevation.update(ele)
                                val delta = (nowAscent - segmentLastAscent).coerceAtLeast(0.0)
                                if (delta > 0.0) a.ascentM += delta
                                segmentLastAscent = nowAscent
                            }
                        } else {
                            segmentKey = null
                            segmentElevation = ElevationGainFilter()
                            segmentLastAscent = 0.0
                        }
                    }
                    prev = p
                }
            }
        }

        var lastBatteryPct: Double? = null
        var lastBatteryMode: String? = null
        var lastBatteryProfile: String? = null
        var lastBatteryConfidence: String? = null
        var modeChangedSinceBattery = false
        events.sortedBy { it.optLong("timestampMs") }.forEach { e ->
            if (e.optString("type") in setOf("ASSIST_MODE", "ASSIST_AUTO_DETECT", "ASSIST_USER_CONFIRM")) modeChangedSinceBattery = true
            if (e.optString("type") == "BATTERY_BLE" && e.has("batteryPct")) {
                val pct = e.optDouble("batteryPct", Double.NaN)
                val mode = e.optString("assistMode")
                val profile = e.optString("assistProfileId")
                val confidence = e.optString("assistModeConfidence")
                val confidenceOk = confidence != "AMBIGUOUS" && lastBatteryConfidence != "AMBIGUOUS"
                val prevPct = lastBatteryPct
                if (pct.isFinite() && prevPct != null && confidenceOk && !modeChangedSinceBattery && mode.isNotBlank() && profile.isNotBlank() && mode == lastBatteryMode && profile == lastBatteryProfile) {
                    val drop = prevPct - pct
                    if (drop in 0.0..5.0) stats.getOrPut("$mode|$profile") { AssistAccum() }.verifiedSocDropPct += drop
                }
                if (pct.isFinite()) {
                    lastBatteryPct = pct
                    lastBatteryMode = mode
                    lastBatteryProfile = profile
                    lastBatteryConfidence = confidence
                    modeChangedSinceBattery = false
                }
            }
        }

        return JSONArray().apply {
            stats.forEach { (key, a) ->
                val parts = key.split('|', limit = 2)
                put(JSONObject().apply {
                    put("mode", parts.getOrElse(0) { "" })
                    put("profileId", parts.getOrElse(1) { "" })
                    put("durationSec", a.durationSec.roundToInt())
                    put("distanceKm", a.distanceKm)
                    put("gpsAscentM", a.ascentM)
                    put("gpsAscentFilter", "median21+hysteresis4m")
                    put("verifiedSocDropPct", a.verifiedSocDropPct)
                    put("verifiedEnergyWh800", a.verifiedSocDropPct * 8.0)
                    put("socAttributionRule", "모드 변경 없는 연속 BLE SOC 하락만 해당 모드에 귀속")
                })
            }
        }
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
