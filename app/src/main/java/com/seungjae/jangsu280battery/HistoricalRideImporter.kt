package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.garmin.fit.LapMesg
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong


enum class HistoricalSourceType(val label: String) { FIT("FIT"), GPX("GPX") }

data class HistoricalRideAnalysis(
    val sourceType: HistoricalSourceType,
    val displayName: String,
    val fileHash: String,
    val course: CourseData,
    /** 이동시간. FIT은 total_timer_time, GPX는 GPS 이동 구간으로 계산한다. */
    val durationSec: Long?,
    val avgSpeedKph: Double?,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val distanceKm: Double get() = course.totalKm
    val ascentM: Double get() = course.totalAscentM
    val descentM: Double get() = course.totalDescentM

    fun summaryText(): String {
        val lines = mutableListOf<String>()
        lines += "$displayName · ${sourceType.label}"
        lines += "거리 ${String.format(Locale.US, "%.2f", distanceKm)} km"
        lines += "획득고도 ${ascentM.roundToLong()} m · 손실고도 ${descentM.roundToLong()} m"
        val timeText = durationSec?.takeIf { it > 0 }?.let { formatDuration(it) } ?: "—"
        val speedText = avgSpeedKph?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—"
        lines += "이동시간 $timeText · 평속 $speedText"
        return lines.joinToString("\n")
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else -> String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}

object HistoricalRideImporter {
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0
    /** GPX에서 이 속도 이상인 구간을 이동시간으로 본다. 약 2.9km/h. */
    private const val GPX_MOVING_SPEED_MPS = 0.8
    /** GPS 기록 공백이 너무 긴 구간은 이동시간 계산에서 제외한다. */
    private const val MAX_GPX_INTERVAL_SEC = 180.0

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "과거 라이딩"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "과거 라이딩"
    }

    fun analyze(context: Context, uri: Uri, requestedType: HistoricalSourceType): HistoricalRideAnalysis {
        val name = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                require(total <= MAX_FILE_BYTES) { "파일이 너무 큽니다. 64MB 이하의 FIT/GPX 파일을 사용해 주세요." }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error("선택한 파일을 열 수 없습니다.")
        require(bytes.isNotEmpty()) { "빈 파일입니다." }
        val hash = sha256(bytes)
        return when (requestedType) {
            HistoricalSourceType.FIT -> parseFit(bytes, name, hash)
            HistoricalSourceType.GPX -> parseGpx(bytes, name, hash)
        }
    }

    private data class FitRaw(
        val lat: Double?,
        val lon: Double?,
        val ele: Double?,
        val distanceM: Double?,
        val timestampSec: Long?
    )

    private data class FitSummary(
        val distanceM: Double?,
        val timerSec: Double?,
        val ascentM: Double?,
        val descentM: Double?
    )

    private fun parseFit(bytes: ByteArray, name: String, hash: String): HistoricalRideAnalysis {
        val raw = mutableListOf<FitRaw>()
        val sessions = mutableListOf<FitSummary>()
        val laps = mutableListOf<FitSummary>()
        val broadcaster = MesgBroadcaster()
        val listener = MesgListener { mesg ->
            when (mesg.num) {
                MesgNum.RECORD -> {
                    val r = RecordMesg(mesg)
                    val latSemi = r.getPositionLat()
                    val lonSemi = r.getPositionLong()
                    val lat = latSemi?.toDouble()?.times(SEMICIRCLE_TO_DEG)?.takeIf { it in -90.0..90.0 }
                    val lon = lonSemi?.toDouble()?.times(SEMICIRCLE_TO_DEG)?.takeIf { it in -180.0..180.0 }
                    // 최신 FIT은 enhanced_altitude를 우선 사용한다. Strava/신형 기기 FIT에서 중요하다.
                    val elevation = (r.getEnhancedAltitude() ?: r.getAltitude())
                        ?.toDouble()?.takeIf { it in -1000.0..10000.0 }
                    raw += FitRaw(
                        lat = lat,
                        lon = lon,
                        ele = elevation,
                        distanceM = r.getDistance()?.toDouble()?.takeIf { it >= 0.0 },
                        timestampSec = r.getTimestamp()?.getTimestamp()
                    )
                }
                MesgNum.SESSION -> {
                    val s = SessionMesg(mesg)
                    sessions += FitSummary(
                        distanceM = s.getTotalDistance()?.toDouble()?.takeIf { it > 0.0 },
                        timerSec = s.getTotalTimerTime()?.toDouble()?.takeIf { it > 0.0 },
                        ascentM = s.getTotalAscent()?.toDouble()?.takeIf { it >= 0.0 },
                        descentM = s.getTotalDescent()?.toDouble()?.takeIf { it >= 0.0 }
                    )
                }
                MesgNum.LAP -> {
                    val l = LapMesg(mesg)
                    laps += FitSummary(
                        distanceM = l.getTotalDistance()?.toDouble()?.takeIf { it > 0.0 },
                        timerSec = l.getTotalTimerTime()?.toDouble()?.takeIf { it > 0.0 },
                        ascentM = l.getTotalAscent()?.toDouble()?.takeIf { it >= 0.0 },
                        descentM = l.getTotalDescent()?.toDouble()?.takeIf { it >= 0.0 }
                    )
                }
            }
        }
        broadcaster.addListener(listener)
        ByteArrayInputStream(bytes).use { broadcaster.run(it) }
        require(raw.size >= 2) { "FIT 파일에서 주행 Record 데이터를 읽지 못했습니다." }

        // FIT에서는 Record를 다시 계산한 값보다 Session 요약값을 우선한다.
        // total_distance / total_timer_time / total_ascent / total_descent가 기기에 기록된 공식 통계다.
        val summary = aggregateSummary(if (sessions.isNotEmpty()) sessions else laps)

        val firstRecordedDistance = raw.firstNotNullOfOrNull { it.distanceM } ?: 0.0
        var lastRouteM = 0.0
        var lastGpsLat: Double? = null
        var lastGpsLon: Double? = null
        var lastEle = raw.firstNotNullOfOrNull { it.ele } ?: 0.0
        val track = ArrayList<TrackPoint>(raw.size)

        raw.forEach { p ->
            if (p.ele != null) lastEle = p.ele
            val lat = p.lat
            val lon = p.lon
            if (lat == null || lon == null) return@forEach

            val gpsStep = if (lastGpsLat != null && lastGpsLon != null) {
                Geo.distanceMeters(lastGpsLat!!, lastGpsLon!!, lat, lon).coerceIn(0.0, 1000.0)
            } else 0.0
            val distFromFile = p.distanceM?.let { (it - firstRecordedDistance).coerceAtLeast(0.0) }
            val routeM = when {
                distFromFile != null && distFromFile + 20.0 >= lastRouteM -> max(lastRouteM, distFromFile)
                else -> lastRouteM + gpsStep
            }
            lastRouteM = routeM
            lastGpsLat = lat
            lastGpsLon = lon
            track += TrackPoint(lat = lat, lon = lon, ele = lastEle, routeKm = routeM / 1000.0)
        }
        require(track.size >= 2 && track.last().routeKm > 0.1) { "FIT 파일에서 유효한 이동거리를 계산하지 못했습니다." }

        // Session의 total_distance가 있으면 트랙 진행거리도 같은 총거리로 맞춘다.
        val officialDistanceM = summary?.distanceM?.takeIf { it > 100.0 }
        val currentDistanceM = track.last().routeKm * 1000.0
        val distanceScale = if (officialDistanceM != null && currentDistanceM > 1.0) officialDistanceM / currentDistanceM else 1.0
        val scaledTrack = track.map { it.copy(routeKm = it.routeKm * distanceScale) }

        val elevationSamples = raw.count { it.ele != null }
        val hasElevation = elevationSamples >= maxOf(2, raw.size / 20)
        val fallbackElevation = elevationTotals(scaledTrack, hasElevation)
        val officialAscent = summary?.ascentM?.takeIf { it > 0.0 || fallbackElevation.ascentM < 5.0 }
        val officialDescent = summary?.descentM?.takeIf { it > 0.0 || fallbackElevation.descentM < 5.0 }
        val course = CourseData(
            name = name.substringBeforeLast('.').ifBlank { "과거 FIT 라이딩" },
            track = scaledTrack,
            batteryMarkers = emptyMap(),
            pois = emptyList(),
            hasElevation = hasElevation,
            totalAscentM = officialAscent ?: fallbackElevation.ascentM,
            totalDescentM = officialDescent ?: fallbackElevation.descentM
        )

        val recordTimes = raw.mapNotNull { it.timestampSec }
        val elapsedFromRecords = if (recordTimes.size >= 2) {
            (recordTimes.maxOrNull()!! - recordTimes.minOrNull()!!).coerceAtLeast(0L)
        } else null
        val movingSec = summary?.timerSec?.takeIf { it > 0.0 }?.roundToLong() ?: elapsedFromRecords
        val avgSpeed = if (movingSec != null && movingSec > 0) {
            course.totalKm / (movingSec / 3600.0)
        } else null

        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.FIT,
            displayName = name,
            fileHash = hash,
            course = course,
            durationSec = movingSec,
            avgSpeedKph = avgSpeed,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun aggregateSummary(items: List<FitSummary>): FitSummary? {
        if (items.isEmpty()) return null
        fun sumOrNull(values: List<Double?>): Double? {
            val present = values.mapNotNull { it }
            return if (present.isEmpty()) null else present.sum()
        }
        return FitSummary(
            distanceM = sumOrNull(items.map { it.distanceM }),
            timerSec = sumOrNull(items.map { it.timerSec }),
            ascentM = sumOrNull(items.map { it.ascentM }),
            descentM = sumOrNull(items.map { it.descentM })
        )
    }

    private data class GpxTimedPoint(
        val lat: Double,
        val lon: Double,
        val timeMs: Long?,
        val segment: Int
    )

    private fun parseGpx(bytes: ByteArray, name: String, hash: String): HistoricalRideAnalysis {
        val course = ByteArrayInputStream(bytes).use { CourseData.parse(it, name.substringBeforeLast('.')) }
        val timedPoints = parseGpxTimedPoints(bytes)
        val movingSec = calculateGpxMovingTime(timedPoints)
        val elapsedSec = timedPoints.mapNotNull { it.timeMs }.let { times ->
            if (times.size >= 2) ((times.maxOrNull()!! - times.minOrNull()!!) / 1000L).coerceAtLeast(0L) else null
        }
        val chosenDuration = movingSec?.takeIf { it > 0 } ?: elapsedSec
        val avgSpeed = if (chosenDuration != null && chosenDuration > 0) {
            course.totalKm / (chosenDuration / 3600.0)
        } else null

        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.GPX,
            displayName = name,
            fileHash = hash,
            course = course,
            durationSec = chosenDuration,
            avgSpeedKph = avgSpeed,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun parseGpxTimedPoints(bytes: ByteArray): List<GpxTimedPoint> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val result = mutableListOf<GpxTimedPoint>()
        var event = parser.eventType
        var segment = -1
        var inPoint = false
        var pointLat = 0.0
        var pointLon = 0.0
        var pointTime: Long? = null
        var currentTag = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(':').lowercase(Locale.US)
                    when (currentTag) {
                        "trkseg" -> segment++
                        "trkpt", "rtept" -> {
                            inPoint = true
                            pointLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            pointLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            pointTime = null
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inPoint && currentTag == "time") {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotEmpty()) pointTime = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                    if (tag == "trkpt" || tag == "rtept") {
                        if (pointLat in -90.0..90.0 && pointLon in -180.0..180.0) {
                            result += GpxTimedPoint(pointLat, pointLon, pointTime, segment.coerceAtLeast(0))
                        }
                        inPoint = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun calculateGpxMovingTime(points: List<GpxTimedPoint>): Long? {
        if (points.size < 2) return null
        var moving = 0.0
        var validIntervals = 0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment != b.segment) continue
            val ta = a.timeMs ?: continue
            val tb = b.timeMs ?: continue
            val dt = (tb - ta) / 1000.0
            if (dt <= 0.0 || dt > MAX_GPX_INTERVAL_SEC) continue
            val meters = Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
            val speed = meters / dt
            validIntervals++
            if (speed >= GPX_MOVING_SPEED_MPS) moving += dt
        }
        return if (validIntervals >= 2 && moving > 0.0) moving.roundToLong() else null
    }

    private fun elevationTotals(track: List<TrackPoint>, enabled: Boolean): ElevationStats {
        if (!enabled || track.size < 2) return ElevationStats(0.0, 0.0)
        var up = 0.0
        var down = 0.0
        for (i in 1 until track.size) {
            if (track[i].routeKm <= track[i - 1].routeKm + 0.00001) continue
            val d = track[i].ele - track[i - 1].ele
            if (d > 0.0) up += d else if (d < 0.0) down += -d
        }
        return ElevationStats(up, down)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
