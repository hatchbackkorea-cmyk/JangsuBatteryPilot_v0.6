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

enum class HistoricalSourceType(val label: String) { FIT("FIT"), GPX("GPX"), PROTO("Avinox 원본") }

data class HistoricalRideSourcePart(
    val displayName: String,
    val fileHash: String,
    val uri: Uri?,
    val distanceKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationSec: Long?,
    val startTimestampMs: Long?,
    val endTimestampMs: Long?,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?
)

data class HistoricalRideGap(
    val beforeFile: String,
    val afterFile: String,
    val durationSec: Long?,
    val locationGapM: Double?,
    val timeOverlapSec: Long = 0L
)

data class HistoricalRideAnalysis(
    val sourceType: HistoricalSourceType,
    val displayName: String,
    val fileHash: String,
    val course: CourseData,
    /** 이동시간. FIT은 total_timer_time, GPX는 GPS 이동 구간으로 계산한다. */
    val durationSec: Long?,
    val avgSpeedKph: Double?,
    /** 학습은 기존 파워/지형 중심. 심박·e-bike 필드는 export 검증에도 함께 보존한다. */
    val telemetry: List<HistoricalTelemetryPoint> = emptyList(),
    val dataQualityScore: Int = 0,
    val sourceParts: List<HistoricalRideSourcePart> = emptyList(),
    val gaps: List<HistoricalRideGap> = emptyList(),
    val warnings: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val distanceKm: Double get() = course.totalKm
    val ascentM: Double get() = course.totalAscentM
    val descentM: Double get() = course.totalDescentM

    /** 화면에는 사용자가 요청한 핵심 주행 통계만 표시한다. */
    fun summaryText(): String {
        val lines = mutableListOf<String>()
        lines += "$displayName · ${sourceType.label}"
        if (sourceParts.size > 1) {
            lines += "FIT ${sourceParts.size}개 결합 · 파일 사이 공백 ${gaps.size}회"
        }
        lines += "거리 ${String.format(Locale.US, "%.2f", distanceKm)} km"
        lines += "획득고도 ${ascentM.roundToLong()} m · 손실고도 ${descentM.roundToLong()} m"
        val timeText = durationSec?.takeIf { it > 0 }?.let(::formatDuration) ?: "—"
        val speedText = avgSpeedKph?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—"
        lines += "이동시간 $timeText · 평속 $speedText"
        gaps.forEachIndexed { index, gap ->
            val pause = gap.durationSec?.takeIf { it > 0 }?.let(::formatDuration) ?: "—"
            val gapM = gap.locationGapM?.let { String.format(Locale.US, "%.0f m", it) } ?: "—"
            lines += "파일 공백 ${index + 1}: $pause · 위치차 $gapM"
        }
        warnings.take(3).forEach { lines += "⚠ $it" }
        return lines.joinToString("\n")
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}

object HistoricalRideImporter {
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0
    private const val GPX_MOVING_SPEED_MPS = 0.8
    private const val MAX_GPX_INTERVAL_SEC = 180.0
    private const val ZERO_MOTOR_W = 5.0
    private const val DOWNHILL_MIN_SEC = 10.0
    private const val DOWNHILL_MIN_KM = 0.10

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
        val bytes = readBytes(context, uri)
        val hash = sha256(bytes)
        val single = when (requestedType) {
            HistoricalSourceType.FIT -> parseFit(bytes, name, hash)
            HistoricalSourceType.GPX -> parseGpx(bytes, name, hash)
            HistoricalSourceType.PROTO -> error("Avinox 원본은 Shizuku 원본 동기화 경로에서 불러옵니다.")
        }
        val first = single.telemetry.firstOrNull()
        val last = single.telemetry.lastOrNull()
        return single.copy(sourceParts = listOf(HistoricalRideSourcePart(
            displayName = name,
            fileHash = hash,
            uri = uri,
            distanceKm = single.distanceKm,
            ascentM = single.ascentM,
            descentM = single.descentM,
            durationSec = single.durationSec,
            startTimestampMs = single.telemetry.mapNotNull { it.timestampMs }.minOrNull(),
            endTimestampMs = single.telemetry.mapNotNull { it.timestampMs }.maxOrNull(),
            startLat = first?.lat, startLon = first?.lon, endLat = last?.lat, endLon = last?.lon
        )))
    }

    fun analyzeFile(file: java.io.File, requestedType: HistoricalSourceType): HistoricalRideAnalysis {
        require(file.exists()) { "파일을 찾지 못했습니다." }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_FILE_BYTES) { "파일이 너무 큽니다." }
        val hash = sha256(bytes)
        return when (requestedType) {
            HistoricalSourceType.FIT -> parseFit(bytes, file.name, hash)
            HistoricalSourceType.GPX -> parseGpx(bytes, file.name, hash)
            HistoricalSourceType.PROTO -> error("Avinox 원본은 AvinoxProtoParser를 사용합니다.")
        }
    }

    /**
     * Avinox가 절전/전원 OFF로 한 라이딩을 여러 FIT으로 나눈 경우를 하나의 실제 세션으로 결합한다.
     * 파일 자체의 이동거리/상승/이동시간은 합산하고, 파일 사이 공백은 주행 데이터로 꾸며내지 않는다.
     */
    fun analyzeMultipleFit(context: Context, uris: List<Uri>): HistoricalRideAnalysis {
        require(uris.isNotEmpty()) { "FIT 파일을 하나 이상 선택해 주세요." }
        val singles = uris.map { uri -> analyze(context, uri, HistoricalSourceType.FIT) }
            .distinctBy { it.fileHash }
        require(singles.isNotEmpty()) { "유효한 FIT 파일이 없습니다." }
        if (singles.size == 1) return singles.first()

        val ordered = singles.sortedWith(compareBy<HistoricalRideAnalysis> {
            it.telemetry.mapNotNull { p -> p.timestampMs }.minOrNull() ?: Long.MAX_VALUE
        }.thenBy { it.displayName })

        val warnings = mutableListOf<String>()
        val gaps = mutableListOf<HistoricalRideGap>()
        val mergedTrack = mutableListOf<TrackPoint>()
        val mergedTelemetry = mutableListOf<HistoricalTelemetryPoint>()
        val parts = mutableListOf<HistoricalRideSourcePart>()
        var offsetKm = 0.0

        ordered.forEachIndexed { index, a ->
            val source = a.sourceParts.firstOrNull()
            val times = a.telemetry.mapNotNull { it.timestampMs }
            val firstTel = a.telemetry.firstOrNull()
            val lastTel = a.telemetry.lastOrNull()
            if (index > 0) {
                val prev = ordered[index - 1]
                val prevLast = prev.telemetry.lastOrNull()
                val thisFirst = firstTel
                val prevEnd = prev.telemetry.mapNotNull { it.timestampMs }.maxOrNull()
                val thisStart = times.minOrNull()
                val signedGap = if (prevEnd != null && thisStart != null) (thisStart - prevEnd) / 1000L else null
                val overlap = signedGap?.takeIf { it < 0 }?.let { -it } ?: 0L
                val pause = signedGap?.coerceAtLeast(0L)
                val locationGap = if (prevLast != null && thisFirst != null) {
                    Geo.distanceMeters(prevLast.lat, prevLast.lon, thisFirst.lat, thisFirst.lon)
                } else null
                gaps += HistoricalRideGap(prev.displayName, a.displayName, pause, locationGap, overlap)
                if (overlap > 30L) warnings += "${prev.displayName}와 ${a.displayName} 기록이 ${overlap}초 겹칩니다."
                if (pause != null && pause > 6 * 3600L) warnings += "파일 사이 휴식이 6시간을 넘습니다. 같은 세션인지 확인하세요."
                if (locationGap != null && locationGap > 1000.0) warnings += "파일 연결 위치가 ${locationGap.roundToLong()}m 떨어져 있습니다."
                require(locationGap == null || locationGap <= 5000.0) {
                    "선택한 FIT 사이 위치가 5km 이상 떨어져 있습니다. 서로 다른 라이딩 파일이 섞였는지 확인해 주세요."
                }
            }

            a.course.track.forEach { p -> mergedTrack += p.copy(routeKm = p.routeKm + offsetKm) }
            a.telemetry.forEachIndexed { pIndex, p ->
                val boundaryGap = index > 0 && pIndex == 0
                mergedTelemetry += p.copy(
                    routeKm = p.routeKm + offsetKm,
                    state = if (boundaryGap) TelemetryState.SENSOR_GAP else p.state
                )
            }
            parts += HistoricalRideSourcePart(
                displayName = a.displayName,
                fileHash = a.fileHash,
                uri = source?.uri,
                distanceKm = a.distanceKm,
                ascentM = a.ascentM,
                descentM = a.descentM,
                durationSec = a.durationSec,
                startTimestampMs = times.minOrNull(),
                endTimestampMs = times.maxOrNull(),
                startLat = firstTel?.lat, startLon = firstTel?.lon, endLat = lastTel?.lat, endLon = lastTel?.lon
            )
            offsetKm += a.distanceKm
        }

        require(mergedTrack.size >= 2 && offsetKm > 0.1) { "결합된 FIT에서 유효한 코스를 만들지 못했습니다." }
        val combinedHash = sha256(ordered.joinToString("|") { it.fileHash }.toByteArray(Charsets.UTF_8))
        val durations = ordered.map { it.durationSec }
        val totalDuration = if (durations.all { it != null }) durations.filterNotNull().sum() else null
        if (totalDuration == null) warnings += "일부 FIT에 이동시간이 없어 결합 평속을 확정하지 않았습니다."
        val totalAscent = ordered.sumOf { it.ascentM }
        val totalDescent = ordered.sumOf { it.descentM }
        val hasElevation = ordered.all { it.course.hasElevation }
        if (!hasElevation) warnings += "일부 FIT에 고도 데이터가 없어 결합 세션의 지형 학습 가중치를 낮춥니다."
        if (ordered.any { it.telemetry.none { p -> p.timestampMs != null } }) {
            warnings += "일부 FIT에 시간정보가 없어 파일명 순서가 보조 기준으로 사용되었습니다."
        }
        val combinedCourse = CourseData(
            name = "결합 FIT 세션 (${ordered.size}개)",
            track = mergedTrack,
            batteryMarkers = emptyMap(),
            pois = emptyList(),
            hasElevation = hasElevation,
            totalAscentM = totalAscent,
            totalDescentM = totalDescent
        )
        val baseQuality = if (ordered.isNotEmpty()) {
            ordered.sumOf { it.dataQualityScore * it.telemetry.size }.toDouble() / ordered.sumOf { it.telemetry.size }.coerceAtLeast(1)
        } else 0.0
        var quality = baseQuality
        if (!hasElevation) quality -= 12.0
        if (totalDuration == null) quality -= 10.0
        gaps.forEach { gap ->
            if ((gap.locationGapM ?: 0.0) > 1000.0) quality -= 12.0
            else if ((gap.locationGapM ?: 0.0) > 250.0) quality -= 4.0
            if (gap.timeOverlapSec > 30) quality -= 10.0
            if ((gap.durationSec ?: 0L) > 6 * 3600L) quality -= 8.0
        }
        val avgSpeed = totalDuration?.takeIf { it > 0 }?.let { combinedCourse.totalKm / (it / 3600.0) }
        val firstName = ordered.first().displayName.substringBeforeLast('.')
        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.FIT,
            displayName = "$firstName 외 ${ordered.size - 1}개 · 결합 세션",
            fileHash = combinedHash,
            course = combinedCourse,
            durationSec = totalDuration,
            avgSpeedKph = avgSpeed,
            telemetry = mergedTelemetry,
            dataQualityScore = quality.roundToLong().toInt().coerceIn(0, 100),
            sourceParts = parts,
            gaps = gaps,
            warnings = warnings.distinct(),
            timestampMs = parts.mapNotNull { it.startTimestampMs }.minOrNull() ?: System.currentTimeMillis()
        )
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
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
        return bytes
    }

    private data class FitRaw(
        val lat: Double?,
        val lon: Double?,
        val ele: Double?,
        val distanceM: Double?,
        val timestampSec: Long?,
        val speedKph: Double?,
        val cadenceRpm: Double?,
        val riderPowerW: Double?,
        val motorPowerW: Double?,
        val heartRateBpm: Double?,
        val batterySocPercent: Double?,
        val ebikeBatteryLevelPercent: Double?,
        val ebikeAssistMode: Int?,
        val ebikeAssistLevelPercent: Double?,
        val temperatureC: Double?
    )

    private data class FitSummary(
        val distanceM: Double?,
        val timerSec: Double?,
        val ascentM: Double?,
        val descentM: Double?
    )

    private data class TempTelemetry(
        val raw: FitRaw,
        val routeM: Double,
        val ele: Double
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
                    val elevation = (r.getEnhancedAltitude() ?: r.getAltitude())
                        ?.toDouble()?.takeIf { it in -1000.0..10000.0 }
                    val speedMps = reflectiveNumber(r, "getEnhancedSpeed") ?: reflectiveNumber(r, "getSpeed")
                    raw += FitRaw(
                        lat = lat,
                        lon = lon,
                        ele = elevation,
                        distanceM = r.getDistance()?.toDouble()?.takeIf { it >= 0.0 },
                        timestampSec = r.getTimestamp()?.getTimestamp(),
                        speedKph = speedMps?.takeIf { it in 0.0..60.0 }?.times(3.6),
                        cadenceRpm = reflectiveNumber(r, "getCadence")?.takeIf { it in 0.0..250.0 },
                        riderPowerW = reflectiveNumber(r, "getPower")?.takeIf { it in 0.0..2500.0 },
                        motorPowerW = reflectiveNumber(r, "getMotorPower")?.takeIf { it in 0.0..3000.0 },
                        heartRateBpm = reflectiveNumber(r, "getHeartRate")?.takeIf { it in 20.0..250.0 },
                        batterySocPercent = reflectiveNumber(r, "getBatterySoc")?.takeIf { it in 0.0..100.0 },
                        ebikeBatteryLevelPercent = reflectiveNumber(r, "getEbikeBatteryLevel")?.takeIf { it in 0.0..100.0 },
                        ebikeAssistMode = reflectiveNumber(r, "getEbikeAssistMode")?.toInt()?.takeIf { it in 0..255 },
                        ebikeAssistLevelPercent = reflectiveNumber(r, "getEbikeAssistLevelPercent")?.takeIf { it in 0.0..100.0 },
                        temperatureC = reflectiveNumber(r, "getTemperature")?.takeIf { it in -50.0..100.0 }
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

        val summary = aggregateSummary(if (sessions.isNotEmpty()) sessions else laps)
        val firstRecordedDistance = raw.firstNotNullOfOrNull { it.distanceM } ?: 0.0
        var lastRouteM = 0.0
        var lastGpsLat: Double? = null
        var lastGpsLon: Double? = null
        var lastEle = raw.firstNotNullOfOrNull { it.ele } ?: 0.0
        val temp = ArrayList<TempTelemetry>(raw.size)

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
            temp += TempTelemetry(p, routeM, lastEle)
        }
        require(temp.size >= 2 && temp.last().routeM > 100.0) { "FIT 파일에서 유효한 이동거리를 계산하지 못했습니다." }

        val officialDistanceM = summary?.distanceM?.takeIf { it > 100.0 }
        val distanceScale = if (officialDistanceM != null && temp.last().routeM > 1.0) officialDistanceM / temp.last().routeM else 1.0
        val rawTrack = temp.map { t -> TrackPoint(t.raw.lat!!, t.raw.lon!!, t.ele, t.routeM * distanceScale / 1000.0) }
        val elevationSamples = raw.count { it.ele != null }
        val hasElevation = elevationSamples >= maxOf(2, raw.size / 20)
        // 학습용 프로필은 1초 고도 노이즈를 평활화한다. 원본 고도는 telemetry.csv에 그대로 보존한다.
        val track = smoothTrackElevations(rawTrack, hasElevation)
        val correctedElevation = elevationTotals(track, hasElevation)
        val officialAscent = summary?.ascentM?.takeIf { it >= 0.0 }
        val officialDescent = summary?.descentM?.takeIf { it >= 0.0 }
        // 외부 계열 FIT은 Session 누적고도와 트랙 고도가 다를 수 있다.
        // v0.11.0은 70m 공간창으로 평활화한 실제 트랙 고도를 우선하고 Session 값은 fallback으로만 사용한다.
        val chosenAscent = correctedElevation.ascentM.takeIf { hasElevation && it >= 5.0 } ?: officialAscent ?: 0.0
        val chosenDescent = correctedElevation.descentM.takeIf { hasElevation && it >= 5.0 } ?: officialDescent ?: 0.0
        val course = CourseData(
            name = name.substringBeforeLast('.').ifBlank { "과거 FIT 라이딩" },
            track = track,
            batteryMarkers = emptyMap(),
            pois = emptyList(),
            hasElevation = hasElevation,
            totalAscentM = chosenAscent,
            totalDescentM = chosenDescent
        )

        val rawTelemetry = temp.map { t ->
            HistoricalTelemetryPoint(
                timestampMs = t.raw.timestampSec?.times(1000L),
                routeKm = t.routeM * distanceScale / 1000.0,
                lat = t.raw.lat!!,
                lon = t.raw.lon!!,
                elevationM = if (hasElevation) t.ele else null,
                speedKph = t.raw.speedKph,
                cadenceRpm = t.raw.cadenceRpm,
                riderPowerW = t.raw.riderPowerW,
                motorPowerW = t.raw.motorPowerW,
                heartRateBpm = t.raw.heartRateBpm,
                batterySocPercent = t.raw.batterySocPercent,
                ebikeBatteryLevelPercent = t.raw.ebikeBatteryLevelPercent,
                ebikeAssistMode = t.raw.ebikeAssistMode,
                ebikeAssistLevelPercent = t.raw.ebikeAssistLevelPercent,
                temperatureC = t.raw.temperatureC
            )
        }
        val motorAware = rawTelemetry.any { it.motorPowerW != null }
        val telemetry = classifyTelemetry(rawTelemetry, motorAware = motorAware)

        val recordTimes = raw.mapNotNull { it.timestampSec }
        val elapsedFromRecords = if (recordTimes.size >= 2) {
            (recordTimes.maxOrNull()!! - recordTimes.minOrNull()!!).coerceAtLeast(0L)
        } else null
        val movingSec = summary?.timerSec?.takeIf { it > 0.0 }?.roundToLong() ?: elapsedFromRecords
        // Avinox 일부 FIT의 avg_speed 단위 이상을 피하고 거리/이동시간으로 직접 계산한다.
        val avgSpeed = if (movingSec != null && movingSec > 0) course.totalKm / (movingSec / 3600.0) else null

        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.FIT,
            displayName = name,
            fileHash = hash,
            course = course,
            durationSec = movingSec,
            avgSpeedKph = avgSpeed,
            telemetry = telemetry,
            dataQualityScore = qualityScore(telemetry, hasElevation, motorExpected = motorAware),
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun reflectiveNumber(target: Any, methodName: String): Double? = runCatching {
        val value = target.javaClass.getMethod(methodName).invoke(target)
        (value as? Number)?.toDouble()
    }.getOrNull()?.takeIf { it.isFinite() }

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
        val ele: Double?,
        val timeMs: Long?,
        val segment: Int
    )

    private fun parseGpx(bytes: ByteArray, name: String, hash: String): HistoricalRideAnalysis {
        val parsed = ByteArrayInputStream(bytes).use { CourseData.parse(it, name.substringBeforeLast('.')) }
        val smoothedTrack = smoothTrackElevations(parsed.track, parsed.hasElevation)
        val totals = elevationTotals(smoothedTrack, parsed.hasElevation)
        val course = CourseData(
            name = parsed.name,
            track = smoothedTrack,
            batteryMarkers = emptyMap(),
            pois = parsed.pois,
            hasElevation = parsed.hasElevation,
            totalAscentM = totals.ascentM,
            totalDescentM = totals.descentM
        )
        val timedPoints = parseGpxTimedPoints(bytes)
        val movingSec = calculateGpxMovingTime(timedPoints)
        val elapsedSec = timedPoints.mapNotNull { it.timeMs }.let { times ->
            if (times.size >= 2) ((times.maxOrNull()!! - times.minOrNull()!!) / 1000L).coerceAtLeast(0L) else null
        }
        val chosenDuration = movingSec?.takeIf { it > 0 } ?: elapsedSec
        val avgSpeed = if (chosenDuration != null && chosenDuration > 0) course.totalKm / (chosenDuration / 3600.0) else null
        val telemetry = classifyTelemetry(gpxTelemetry(timedPoints, course.totalKm), motorAware = false)

        return HistoricalRideAnalysis(
            sourceType = HistoricalSourceType.GPX,
            displayName = name,
            fileHash = hash,
            course = course,
            durationSec = chosenDuration,
            avgSpeedKph = avgSpeed,
            telemetry = telemetry,
            dataQualityScore = qualityScore(telemetry, course.hasElevation, motorExpected = false),
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
        var pointEle: Double? = null
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
                            pointEle = null
                            pointTime = null
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inPoint) {
                    val value = parser.text?.trim().orEmpty()
                    when (currentTag) {
                        "time" -> if (value.isNotEmpty()) pointTime = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                        "ele" -> pointEle = value.toDoubleOrNull()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.US)
                    if (tag == "trkpt" || tag == "rtept") {
                        if (pointLat in -90.0..90.0 && pointLon in -180.0..180.0) {
                            result += GpxTimedPoint(pointLat, pointLon, pointEle, pointTime, segment.coerceAtLeast(0))
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

    private fun gpxTelemetry(points: List<GpxTimedPoint>, officialTotalKm: Double): List<HistoricalTelemetryPoint> {
        if (points.size < 2) return emptyList()
        var cumM = 0.0
        val rawRoute = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment == b.segment) cumM += Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon).coerceAtMost(1000.0)
            rawRoute[i] = cumM
        }
        val scale = if (cumM > 1.0 && officialTotalKm > 0.0) officialTotalKm * 1000.0 / cumM else 1.0
        return points.mapIndexed { i, p ->
            val speed = if (i > 0) {
                val a = points[i - 1]
                val dt = if (a.timeMs != null && p.timeMs != null) (p.timeMs - a.timeMs) / 1000.0 else -1.0
                if (a.segment == p.segment && dt in 0.2..MAX_GPX_INTERVAL_SEC) {
                    Geo.distanceMeters(a.lat, a.lon, p.lat, p.lon) / dt * 3.6
                } else null
            } else null
            HistoricalTelemetryPoint(
                timestampMs = p.timeMs,
                routeKm = rawRoute[i] * scale / 1000.0,
                lat = p.lat,
                lon = p.lon,
                elevationM = p.ele,
                speedKph = speed?.takeIf { it in 0.0..120.0 },
                cadenceRpm = null,
                riderPowerW = null,
                motorPowerW = null
            )
        }
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

    private fun smoothTrackElevations(track: List<TrackPoint>, enabled: Boolean): List<TrackPoint> {
        if (!enabled || track.size < 5) return track
        // 시간 샘플 수가 아니라 코스 거리 기준 ±35m(총 70m) 창을 사용한다.
        // 속도가 빨라지거나 느려져도 동일한 공간 해상도로 고도 노이즈를 억제하기 위함이다.
        val halfWindowKm = 0.035
        val out = ArrayList<TrackPoint>(track.size)
        var left = 0
        var right = 0
        var sum = 0.0
        for (i in track.indices) {
            val centerKm = track[i].routeKm
            val lo = centerKm - halfWindowKm
            val hi = centerKm + halfWindowKm
            while (right < track.size && track[right].routeKm <= hi) {
                sum += track[right].ele
                right++
            }
            while (left < right && track[left].routeKm < lo) {
                sum -= track[left].ele
                left++
            }
            val count = (right - left).coerceAtLeast(1)
            out += track[i].copy(ele = sum / count)
        }
        return out
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

    private fun classifyTelemetry(input: List<HistoricalTelemetryPoint>, motorAware: Boolean): List<HistoricalTelemetryPoint> {
        if (input.isEmpty()) return input
        val states = MutableList(input.size) { TelemetryState.NORMAL }

        // 1) 결측 / 정차 / 명백한 GPS 이상치를 먼저 분리한다.
        for (i in input.indices) {
            val p = input[i]
            val speed = p.speedKph
            if (i > 0) {
                val prev = input[i - 1]
                val dt = if (p.timestampMs != null && prev.timestampMs != null) (p.timestampMs - prev.timestampMs) / 1000.0 else null
                val gpsM = Geo.distanceMeters(prev.lat, prev.lon, p.lat, p.lon)
                if ((speed != null && speed > 120.0) || (dt != null && dt in 0.1..5.0 && gpsM > 350.0)) {
                    states[i] = TelemetryState.OUTLIER
                    continue
                }
                if (dt != null && dt > 180.0) {
                    states[i] = TelemetryState.SENSOR_GAP
                    continue
                }
            }
            if (speed != null && speed < 1.5) states[i] = TelemetryState.STOPPED
        }

        // 2) 약 50m 창의 고도 변화로 업힐을 분류해 1초 노이즈 영향을 줄인다.
        for (i in input.indices) {
            if (states[i] != TelemetryState.NORMAL) continue
            var j = i - 1
            while (j >= 0 && input[i].routeKm - input[j].routeKm < 0.05) j--
            if (j < 0) continue
            val e0 = input[j].elevationM ?: continue
            val e1 = input[i].elevationM ?: continue
            val dM = (input[i].routeKm - input[j].routeKm) * 1000.0
            if (dM <= 20.0) continue
            val grade = (e1 - e0) / dM * 100.0
            states[i] = when {
                grade >= 8.0 -> TelemetryState.STEEP_CLIMB
                grade >= 3.0 -> TelemetryState.CLIMB
                else -> TelemetryState.NORMAL
            }
        }

        // 3) motor_power=0을 곧바로 다운힐로 단정하지 않는다. 지속시간/거리 + 고도하강을 함께 확인한다.
        if (motorAware) {
            var start = -1
            fun flush(endExclusive: Int) {
                if (start < 0 || endExclusive - start < 2) {
                    start = -1
                    return
                }
                val first = input[start]
                val last = input[endExclusive - 1]
                val duration = if (first.timestampMs != null && last.timestampMs != null) (last.timestampMs - first.timestampMs) / 1000.0 else 0.0
                val dist = (last.routeKm - first.routeKm).coerceAtLeast(0.0)
                if (duration >= DOWNHILL_MIN_SEC || dist >= DOWNHILL_MIN_KM) {
                    val e0 = first.elevationM
                    val e1 = last.elevationM
                    val drop = if (e0 != null && e1 != null) e0 - e1 else 0.0
                    val downhill = drop >= max(5.0, dist * 1000.0 * 0.015)
                    val state = if (downhill) TelemetryState.DOWNHILL_COAST else TelemetryState.COASTING
                    for (k in start until endExclusive) {
                        if (states[k] != TelemetryState.OUTLIER && states[k] != TelemetryState.SENSOR_GAP && states[k] != TelemetryState.STOPPED) {
                            states[k] = state
                        }
                    }
                }
                start = -1
            }
            for (i in input.indices) {
                val p = input[i]
                val zeroMotor = p.motorPowerW != null && p.motorPowerW <= ZERO_MOTOR_W
                val moving = (p.speedKph ?: 0.0) >= 5.0
                val eligible = zeroMotor && moving && states[i] != TelemetryState.OUTLIER && states[i] != TelemetryState.SENSOR_GAP
                if (eligible && start < 0) start = i
                if (!eligible && start >= 0) flush(i)
            }
            if (start >= 0) flush(input.size)
        }

        return input.mapIndexed { i, p -> p.copy(state = states[i]) }
    }

    private fun qualityScore(points: List<HistoricalTelemetryPoint>, hasElevation: Boolean, motorExpected: Boolean): Int {
        if (points.size < 2) return 0
        var score = 100.0
        val n = points.size.toDouble()
        val timestampCoverage = points.count { it.timestampMs != null } / n
        val elevationCoverage = points.count { it.elevationM != null } / n
        val outlierRatio = points.count { it.state == TelemetryState.OUTLIER || it.state == TelemetryState.SENSOR_GAP } / n
        if (timestampCoverage < 0.8) score -= 15.0
        if (hasElevation && elevationCoverage < 0.8) score -= 15.0
        if (outlierRatio > 0.01) score -= 20.0 else if (outlierRatio > 0.003) score -= 8.0
        if (motorExpected) {
            val motorCoverage = points.count { it.motorPowerW != null } / n
            if (motorCoverage < 0.5) score -= 25.0 else if (motorCoverage < 0.9) score -= 10.0
        }
        return score.roundToLong().toInt().coerceIn(0, 100)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
