package com.seungjae.jangsu280battery

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AvinoxProtoHeader(
    val version: Int,
    val rideId: Int,
    val startUnixSec: Long,
    val endUnixSec: Long,
    val distanceM: Int,
    val ascentM: Double,
    val descentM: Double,
    val declaredSamples: Int
)

data class AvinoxProtoSample(
    val record: Long?,
    val timestampMs: Long?,
    val distanceKm: Double?,
    val speedKph: Double?,
    val assistCode: Int?,
    val cadenceRpm: Double?,
    val motorTorqueNm: Double?,
    val riderTorqueNm: Double?,
    val riderPowerW: Double?,
    val motorPowerW: Double?,
    val gear: Int?,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val gradientPct: Double?,
    val temperatureC: Double?,
    val heartRateBpm: Double?,
    val batteryPct: Double?
)

data class AvinoxProtoRide(
    val fileName: String,
    val header: AvinoxProtoHeader,
    val samples: List<AvinoxProtoSample>,
    val qualityScore: Int,
    val warnings: List<String>
) {
    val startSoc: Double? get() = samples.firstNotNullOfOrNull { it.batteryPct }
    val endSoc: Double? get() = samples.asReversed().firstNotNullOfOrNull { it.batteryPct }

    /** 실제 SOC 하락량 합계. 중간 충전이 있어도 순소비를 잃지 않는다. */
    fun consumedSocPct(): Double {
        val points = samples.mapNotNull { it.batteryPct }.filter { it in 0.0..100.0 }
        if (points.size < 2) return 0.0
        var used = 0.0
        for (i in 1 until points.size) {
            val d = points[i - 1] - points[i]
            if (d > 0.0) used += d
        }
        return used
    }

    /** 주행 중 충전/배터리 상승량 합계. */
    fun chargedSocPct(): Double {
        val points = samples.mapNotNull { it.batteryPct }.filter { it in 0.0..100.0 }
        if (points.size < 2) return 0.0
        var added = 0.0
        for (i in 1 until points.size) {
            val d = points[i] - points[i - 1]
            if (d > 0.0) added += d
        }
        return added
    }

    fun batteryEntries(): List<ActualBatteryEntry> {
        val out = ArrayList<ActualBatteryEntry>()
        var lastSoc: Int? = null
        for (s in samples) {
            val soc = s.batteryPct?.toInt() ?: continue
            val km = s.distanceKm ?: continue
            val ts = s.timestampMs ?: continue
            if (lastSoc == null || soc != lastSoc) {
                out += ActualBatteryEntry(
                    percent = soc.toDouble(),
                    routeKm = km.coerceAtLeast(0.0),
                    timestampMs = ts,
                    kind = ActualEntryKind.RIDING,
                    source = ActualEntrySource.IMPORTED
                )
                lastSoc = soc
            }
        }
        return out
    }

    fun assistWindows(): List<AssistModeWindow> {
        val out = ArrayList<AssistModeWindow>()
        var currentMode: AvinoxAssistMode? = null
        var startMs = 0L
        var lastMs = 0L

        fun closeWindow(endMs: Long) {
            val mode = currentMode ?: return
            if (startMs > 0L && endMs >= startMs) out += AssistModeWindow(startMs, endMs, mode, null)
        }

        for (s in samples) {
            val ts = s.timestampMs ?: continue
            val mode = when (s.assistCode) {
                1 -> AvinoxAssistMode.ECO
                2 -> AvinoxAssistMode.TRAIL
                3 -> AvinoxAssistMode.TURBO
                4 -> AvinoxAssistMode.AUTO
                else -> null // unknown/Boost-like codes never contaminate mode learning
            }

            // 전원 OFF/센서 공백을 같은 모드의 연속 구간으로 이어붙이지 않는다.
            if (lastMs > 0L && ts - lastMs > 30_000L) {
                closeWindow(lastMs + 1_000L)
                currentMode = null
                startMs = 0L
            }

            if (mode != currentMode) {
                closeWindow(lastMs)
                currentMode = mode
                startMs = if (mode != null) ts else 0L
            }
            lastMs = ts
        }
        closeWindow(lastMs + 1_000L)
        return out
    }

    fun telemetry(): List<HistoricalTelemetryPoint> {
        var previousTs: Long? = null
        return samples.mapNotNull { s ->
            val km = s.distanceKm ?: return@mapNotNull null
            val lat = s.lat ?: Double.NaN
            val lon = s.lon ?: Double.NaN
            val state = when {
                previousTs != null && s.timestampMs != null && s.timestampMs - previousTs!! > 30_000L -> TelemetryState.SENSOR_GAP
                (s.speedKph ?: 0.0) < 1.5 -> TelemetryState.STOPPED
                else -> TelemetryState.NORMAL
            }
            s.timestampMs?.let { previousTs = it }
            HistoricalTelemetryPoint(
                timestampMs = s.timestampMs,
                routeKm = km,
                lat = lat,
                lon = lon,
                elevationM = s.altitudeM,
                speedKph = s.speedKph,
                cadenceRpm = s.cadenceRpm,
                riderPowerW = s.riderPowerW,
                motorPowerW = s.motorPowerW,
                state = state,
                heartRateBpm = s.heartRateBpm,
                batterySocPercent = s.batteryPct,
                ebikeBatteryLevelPercent = s.batteryPct,
                ebikeAssistMode = s.assistCode,
                temperatureC = s.temperatureC,
                riderTorqueNm = s.riderTorqueNm,
                motorTorqueNm = s.motorTorqueNm,
                gear = s.gear,
                gradientPct = s.gradientPct
            )
        }
    }

    fun course(): CourseData {
        // Terrain learning needs distance/elevation from the very first sample. Avinox may omit GPS
        // for the first few seconds, so back/forward-fill coordinates while preserving original altitude.
        val firstGps = samples.firstOrNull { s ->
            val la = s.lat; val lo = s.lon
            la != null && lo != null && la.isFinite() && lo.isFinite() && abs(la) >= 0.0001 && abs(lo) >= 0.0001
        }
        val firstLat = firstGps?.lat ?: error("Avinox 원본에 유효한 GPS가 없습니다.")
        val firstLon = firstGps.lon ?: error("Avinox 원본에 유효한 GPS가 없습니다.")
        var lastLat: Double = firstLat
        var lastLon: Double = firstLon
        val track = ArrayList<TrackPoint>()
        var lastMeter = Long.MIN_VALUE
        for (s in samples) {
            val km = s.distanceKm ?: continue
            val ele = s.altitudeM ?: continue
            val la = s.lat; val lo = s.lon
            if (la != null && lo != null && la.isFinite() && lo.isFinite() && abs(la) >= 0.0001 && abs(lo) >= 0.0001) {
                lastLat = la; lastLon = lo
            }
            val meter = (km * 1000.0).toLong()
            if (meter == lastMeter) continue
            track += TrackPoint(lastLat, lastLon, ele, km)
            lastMeter = meter
        }
        require(track.size >= 2) { "Avinox 원본에 유효한 거리/고도 샘플이 부족합니다." }
        if (track.first().routeKm > 0.001) track.add(0, track.first().copy(routeKm = 0.0))
        val smooth = smoothElevation(track)
        val officialKm = header.distanceM / 1000.0
        val adjusted = if (smooth.last().routeKm + 0.001 < officialKm) {
            smooth + smooth.last().copy(routeKm = officialKm)
        } else smooth
        return CourseData(
            name = "Avinox 원본 #${header.rideId}",
            track = adjusted,
            batteryMarkers = emptyMap(),
            pois = emptyList(),
            hasElevation = true,
            totalAscentM = header.ascentM,
            totalDescentM = header.descentM
        )
    }

    private fun smoothElevation(points: List<TrackPoint>): List<TrackPoint> {
        if (points.size < 7) return points
        val radiusKm = 0.025
        var left = 0
        var right = 0
        var sum = 0.0
        val out = ArrayList<TrackPoint>(points.size)
        for (i in points.indices) {
            val center = points[i].routeKm
            while (right < points.size && points[right].routeKm <= center + radiusKm) {
                sum += points[right].ele
                right++
            }
            while (left < right && points[left].routeKm < center - radiusKm) {
                sum -= points[left].ele
                left++
            }
            val n = (right - left).coerceAtLeast(1)
            out += points[i].copy(ele = sum / n)
        }
        return out
    }
}

object AvinoxProtoParser {
    private const val HEADER_SIZE = 0xFD
    private const val DECLARED_COUNT_OFFSET = 0xF5
    private const val MAX_SAMPLES = 100_000

    fun parse(file: File): AvinoxProtoRide = parse(file.name, file.readBytes())

    fun parse(fileName: String, bytes: ByteArray): AvinoxProtoRide {
        require(bytes.size > HEADER_SIZE + 8) { "Avinox 원본 파일이 너무 작습니다." }
        require(bytes[0] == 0xA5.toByte() && bytes[1] == 0xA5.toByte() && bytes[2] == 0xA5.toByte() && bytes[3] == 0xA5.toByte()) {
            "지원하지 않는 Avinox 원본 형식입니다."
        }
        val version = u16(bytes, 4)
        require(version == 8) { "Avinox 원본 형식 v$version 은 아직 검증되지 않아 학습하지 않습니다." }
        val header = AvinoxProtoHeader(
            version = version,
            rideId = u16(bytes, 6),
            startUnixSec = u32(bytes, 10),
            endUnixSec = u32(bytes, 14),
            distanceM = u32(bytes, 18).toInt(),
            ascentM = f32(bytes, 22).toDouble(),
            descentM = f32(bytes, 26).toDouble(),
            declaredSamples = u32(bytes, DECLARED_COUNT_OFFSET).toInt()
        )
        require(header.declaredSamples in 1..MAX_SAMPLES) { "Avinox 샘플 수가 비정상입니다: ${header.declaredSamples}" }
        require(header.distanceM in 100..1_000_000) { "Avinox 주행 거리가 비정상입니다." }
        require(header.endUnixSec >= header.startUnixSec) { "Avinox 시간 정보가 비정상입니다." }

        val samples = ArrayList<AvinoxProtoSample>(header.declaredSamples)
        var offset = HEADER_SIZE
        repeat(header.declaredSamples) { index ->
            require(offset + 2 <= bytes.size) { "Avinox 레코드가 중간에서 끝났습니다." }
            val len = u16(bytes, offset)
            offset += 2
            require(len in 1..4096 && offset + len <= bytes.size) { "Avinox 레코드 길이가 비정상입니다." }
            samples += parseRecord(bytes, offset, offset + len)
            offset += len
            if (index < header.declaredSamples - 1) {
                require(offset + 4 <= bytes.size) { "Avinox 레코드 체크 영역이 잘렸습니다." }
                offset += 4
            }
        }

        val warnings = ArrayList<String>()
        val parsedRatio = samples.size.toDouble() / header.declaredSamples
        val batteryCoverage = samples.count { it.batteryPct != null }.toDouble() / samples.size.coerceAtLeast(1)
        val gpsCoverage = samples.count { it.lat != null && it.lon != null }.toDouble() / samples.size.coerceAtLeast(1)
        val timeCoverage = samples.count { it.timestampMs != null }.toDouble() / samples.size.coerceAtLeast(1)
        val powerCoverage = samples.count { it.riderPowerW != null && it.motorPowerW != null }.toDouble() / samples.size.coerceAtLeast(1)
        if (batteryCoverage < 0.90) warnings += "배터리 SOC 기록률이 낮습니다 (${(batteryCoverage * 100).toInt()}%)."
        if (gpsCoverage < 0.60) warnings += "GPS 기록률이 낮습니다 (${(gpsCoverage * 100).toInt()}%)."
        if (powerCoverage < 0.60) warnings += "파워 기록률이 낮습니다 (${(powerCoverage * 100).toInt()}%)."

        var quality = 100
        if (parsedRatio < 0.98) quality -= 25
        if (batteryCoverage < 0.98) quality -= ((0.98 - batteryCoverage) * 100).toInt().coerceAtMost(25)
        if (gpsCoverage < 0.90) quality -= ((0.90 - gpsCoverage) * 40).toInt().coerceAtMost(15)
        if (timeCoverage < 0.98) quality -= 15
        if (powerCoverage < 0.90) quality -= ((0.90 - powerCoverage) * 30).toInt().coerceAtMost(10)
        quality = quality.coerceIn(0, 100)

        require(batteryCoverage >= 0.80) { "배터리 SOC가 충분히 기록되지 않아 A+ 학습에서 제외했습니다." }
        require(timeCoverage >= 0.90) { "시간 기록이 충분하지 않아 A+ 학습에서 제외했습니다." }
        require(gpsCoverage >= 0.50) { "GPS/거리 기록이 충분하지 않아 A+ 학습에서 제외했습니다." }

        return AvinoxProtoRide(fileName, header, samples, quality, warnings)
    }

    private fun parseRecord(bytes: ByteArray, start: Int, end: Int): AvinoxProtoSample {
        val r = WireReader(bytes, start, end)
        var record: Long? = null
        var timestampMs: Long? = null
        var distanceKm: Double? = null
        // Avinox v8 uses protobuf default omission for numeric zero values.
        // A missing power/cadence/speed field in a valid sample therefore means 0, not unknown.
        var speedKph: Double? = 0.0
        var assist: Int? = null
        var cadence: Double? = 0.0
        var motorTorque: Double? = 0.0
        var riderTorque: Double? = 0.0
        var riderPower: Double? = 0.0
        var motorPower: Double? = 0.0
        var gear: Int? = null
        var lat: Double? = null
        var lon: Double? = null
        var altitude: Double? = null
        var gradient: Double? = null
        var temperature: Double? = null
        var heartRate: Double? = null
        var battery: Double? = null

        while (!r.done()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                wire == 0 -> {
                    val v = r.readVarint()
                    when (field) {
                        6 -> record = v
                        7 -> speedKph = v / 100.0
                        8 -> assist = v.toInt()
                        9 -> cadence = v / 100.0
                        10 -> motorTorque = v / 100.0
                        11 -> riderTorque = v / 100.0
                        13 -> riderPower = v / 100.0
                        14 -> motorPower = v / 100.0
                        22 -> gear = v.toInt()
                        23 -> distanceKm = v / 1000.0
                        39 -> altitude = v / 100.0
                        40 -> gradient = v / 100.0 // two's-complement int64 naturally survives in Long
                        41 -> temperature = v / 100.0
                        51 -> heartRate = v.toDouble()
                        71 -> battery = v.toDouble().takeIf { it in 0.0..100.0 }
                        72 -> timestampMs = v * 1000L
                    }
                }
                wire == 1 -> {
                    val v = r.readFixed64()
                    when (field) {
                        31 -> lat = java.lang.Double.longBitsToDouble(v).takeIf { it.isFinite() && abs(it) <= 90.0 }
                        32 -> lon = java.lang.Double.longBitsToDouble(v).takeIf { it.isFinite() && abs(it) <= 180.0 }
                    }
                }
                wire == 2 -> r.skip(r.readVarint().toInt())
                wire == 5 -> r.skip(4)
                else -> throw IllegalArgumentException("지원하지 않는 wire type $wire")
            }
        }
        return AvinoxProtoSample(record, timestampMs, distanceKm, speedKph, assist, cadence, motorTorque, riderTorque, riderPower, motorPower, gear, lat, lon, altitude, gradient, temperature, heartRate, battery)
    }

    private class WireReader(private val b: ByteArray, start: Int, private val end: Int) {
        private var p = start
        fun done(): Boolean = p >= end
        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            repeat(10) {
                require(p < end) { "잘린 varint" }
                val x = b[p++].toInt() and 0xFF
                value = value or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("varint가 너무 깁니다.")
        }
        fun readFixed64(): Long {
            require(p + 8 <= end) { "잘린 fixed64" }
            var v = 0L
            for (i in 0 until 8) v = v or ((b[p++].toLong() and 0xFFL) shl (8 * i))
            return v
        }
        fun skip(n: Int) {
            require(n >= 0 && p + n <= end) { "잘린 field" }
            p += n
        }
    }

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun f32(b: ByteArray, o: Int): Float = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).float
}
