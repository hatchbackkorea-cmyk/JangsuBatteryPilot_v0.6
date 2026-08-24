package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.MesgListener
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.max


enum class HistoricalSourceType(val label: String) { FIT("FIT"), GPX("GPX") }

data class HistoricalRideAnalysis(
    val sourceType: HistoricalSourceType,
    val displayName: String,
    val fileHash: String,
    val course: CourseData,
    val durationSec: Long?,
    val avgSpeedKph: Double?,
    val avgHeartRate: Double?,
    val avgCadence: Double?,
    val avgPower: Double?,
    val heartRateSamples: Int,
    val cadenceSamples: Int,
    val powerSamples: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val distanceKm: Double get() = course.totalKm
    val ascentM: Double get() = course.totalAscentM

    fun sensorSummary(): String = listOf(
        "심박 ${if (heartRateSamples > 0) "✅" else "—"}",
        "케이던스 ${if (cadenceSamples > 0) "✅" else "—"}",
        "파워 ${if (powerSamples > 0) "✅" else "—"}"
    ).joinToString(" · ")

    fun summaryText(): String {
        val lines = mutableListOf<String>()
        lines += "$displayName · ${sourceType.label}"
        lines += "${RideFormatter.one(distanceKm)} km · 상승 ${ascentM.toInt()} m" +
            (durationSec?.takeIf { it > 0 }?.let { " · ${formatDuration(it)}" } ?: "")
        val stats = mutableListOf<String>()
        avgSpeedKph?.let { stats += "평속 ${String.format(Locale.US, "%.1f", it)} km/h" }
        avgHeartRate?.let { stats += "심박 ${it.toInt()} bpm" }
        avgCadence?.let { stats += "케이던스 ${it.toInt()} rpm" }
        avgPower?.let { stats += "파워 ${it.toInt()} W" }
        if (stats.isNotEmpty()) lines += stats.joinToString(" · ")
        lines += sensorSummary()
        return lines.joinToString("\n")
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }
}

object HistoricalRideImporter {
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0

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
        val timestampSec: Long?,
        val speedMps: Double?,
        val hr: Double?,
        val cadence: Double?,
        val power: Double?
    )

    private fun parseFit(bytes: ByteArray, name: String, hash: String): HistoricalRideAnalysis {
        val raw = mutableListOf<FitRaw>()
        val broadcaster = MesgBroadcaster()
        val listener = MesgListener { mesg ->
            if (mesg.num != MesgNum.RECORD) return@MesgListener
            val r = RecordMesg(mesg)
            val latSemi = r.getPositionLat()
            val lonSemi = r.getPositionLong()
            val lat = latSemi?.toDouble()?.times(SEMICIRCLE_TO_DEG)?.takeIf { it in -90.0..90.0 }
            val lon = lonSemi?.toDouble()?.times(SEMICIRCLE_TO_DEG)?.takeIf { it in -180.0..180.0 }
            raw += FitRaw(
                lat = lat,
                lon = lon,
                ele = r.getAltitude()?.toDouble()?.takeIf { it in -1000.0..10000.0 },
                distanceM = r.getDistance()?.toDouble()?.takeIf { it >= 0.0 },
                timestampSec = r.getTimestamp()?.getTimestamp(),
                speedMps = r.getSpeed()?.toDouble()?.takeIf { it >= 0.0 },
                hr = r.getHeartRate()?.toDouble()?.takeIf { it in 20.0..260.0 },
                cadence = r.getCadence()?.toDouble()?.takeIf { it in 0.0..300.0 },
                power = r.getPower()?.toDouble()?.takeIf { it in 0.0..3000.0 }
            )
        }
        broadcaster.addListener(listener)
        ByteArrayInputStream(bytes).use { broadcaster.run(it) }
        require(raw.size >= 2) { "FIT 파일에서 주행 Record 데이터를 읽지 못했습니다." }

        val distanceValues = raw.mapNotNull { it.distanceM }
        val firstRecordedDistance = distanceValues.firstOrNull() ?: 0.0
        var lastRouteM = 0.0
        var lastGpsLat: Double? = null
        var lastGpsLon: Double? = null
        var lastEle = raw.firstNotNullOfOrNull { it.ele } ?: 0.0
        val track = ArrayList<TrackPoint>(raw.size)

        raw.forEach { p ->
            val distFromFile = p.distanceM?.let { (it - firstRecordedDistance).coerceAtLeast(0.0) }
            val gpsStep = if (p.lat != null && p.lon != null && lastGpsLat != null && lastGpsLon != null) {
                Geo.distanceMeters(lastGpsLat!!, lastGpsLon!!, p.lat, p.lon).coerceIn(0.0, 500.0)
            } else 0.0
            val routeM = when {
                distFromFile != null && distFromFile + 15.0 >= lastRouteM -> max(lastRouteM, distFromFile)
                else -> lastRouteM + gpsStep
            }
            lastRouteM = routeM
            if (p.lat != null && p.lon != null) {
                lastGpsLat = p.lat
                lastGpsLon = p.lon
            }
            if (p.ele != null) lastEle = p.ele
            track += TrackPoint(
                lat = p.lat ?: lastGpsLat ?: 0.0,
                lon = p.lon ?: lastGpsLon ?: 0.0,
                ele = lastEle,
                routeKm = routeM / 1000.0
            )
        }
        val compactTrack = track.filterIndexed { index, point ->
            index == 0 || index == track.lastIndex || point.routeKm > track[index - 1].routeKm + 0.0005
        }
        require(compactTrack.size >= 2 && compactTrack.last().routeKm > 0.1) { "FIT 파일에서 유효한 이동거리를 계산하지 못했습니다." }

        val elevationSamples = raw.count { it.ele != null }
        val hasElevation = elevationSamples >= maxOf(2, raw.size / 10)
        val elevation = elevationTotals(compactTrack, hasElevation)
        val course = CourseData(
            name = name.substringBeforeLast('.').ifBlank { "과거 FIT 라이딩" },
            track = compactTrack,
            batteryMarkers = emptyMap(),
            pois = emptyList(),
            hasElevation = hasElevation,
            totalAscentM = elevation.ascentM,
            totalDescentM = elevation.descentM
        )

        val times = raw.mapNotNull { it.timestampSec }
        val duration = if (times.size >= 2) (times.maxOrNull()!! - times.minOrNull()!!).coerceAtLeast(0L) else null
        val speeds = raw.mapNotNull { it.speedMps }
        val hrs = raw.mapNotNull { it.hr }
        val cads = raw.mapNotNull { it.cadence }
        val powers = raw.mapNotNull { it.power }
        val avgSpeed = when {
            speeds.isNotEmpty() -> speeds.average() * 3.6
            duration != null && duration > 0 -> course.totalKm / (duration / 3600.0)
            else -> null
        }
        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.FIT,
            displayName = name,
            fileHash = hash,
            course = course,
            durationSec = duration,
            avgSpeedKph = avgSpeed,
            avgHeartRate = hrs.takeIf { it.isNotEmpty() }?.average(),
            avgCadence = cads.takeIf { it.isNotEmpty() }?.average(),
            avgPower = powers.takeIf { it.isNotEmpty() }?.average(),
            heartRateSamples = hrs.size,
            cadenceSamples = cads.size,
            powerSamples = powers.size,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun parseGpx(bytes: ByteArray, name: String, hash: String): HistoricalRideAnalysis {
        val course = ByteArrayInputStream(bytes).use { CourseData.parse(it, name.substringBeforeLast('.')) }
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        var currentTag = ""
        var inTrackPoint = false
        val times = mutableListOf<Long>()
        val hrs = mutableListOf<Double>()
        val cads = mutableListOf<Double>()
        val powers = mutableListOf<Double>()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(':').lowercase(Locale.US)
                    if (currentTag == "trkpt" || currentTag == "rtept") inTrackPoint = true
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text?.trim().orEmpty()
                    if (inTrackPoint && value.isNotEmpty()) {
                        when (currentTag) {
                            "time" -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { times += it }
                            "hr", "heartrate", "heart_rate" -> value.toDoubleOrNull()?.takeIf { it in 20.0..260.0 }?.let { hrs += it }
                            "cad", "cadence" -> value.toDoubleOrNull()?.takeIf { it in 0.0..300.0 }?.let { cads += it }
                            "power", "watts" -> value.toDoubleOrNull()?.takeIf { it in 0.0..3000.0 }?.let { powers += it }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                    if (tag == "trkpt" || tag == "rtept") inTrackPoint = false
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        val duration = if (times.size >= 2) ((times.maxOrNull()!! - times.minOrNull()!!) / 1000L).coerceAtLeast(0L) else null
        val avgSpeed = if (duration != null && duration > 0) course.totalKm / (duration / 3600.0) else null
        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.GPX,
            displayName = name,
            fileHash = hash,
            course = course,
            durationSec = duration,
            avgSpeedKph = avgSpeed,
            avgHeartRate = hrs.takeIf { it.isNotEmpty() }?.average(),
            avgCadence = cads.takeIf { it.isNotEmpty() }?.average(),
            avgPower = powers.takeIf { it.isNotEmpty() }?.average(),
            heartRateSamples = hrs.size,
            cadenceSamples = cads.size,
            powerSamples = powers.size,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun elevationTotals(track: List<TrackPoint>, enabled: Boolean): ElevationStats {
        if (!enabled || track.size < 2) return ElevationStats(0.0, 0.0)
        var up = 0.0
        var down = 0.0
        for (i in 1 until track.size) {
            if (track[i].routeKm <= track[i - 1].routeKm + 0.00001) continue
            val d = track[i].ele - track[i - 1].ele
            if (d > 1.0) up += d else if (d < -1.0) down += -d
        }
        return ElevationStats(up, down)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
