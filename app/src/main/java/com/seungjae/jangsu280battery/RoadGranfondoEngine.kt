package com.seungjae.jangsu280battery

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp

/** 보급소 정차 계획. 정차시간은 목표 주행시간과 별도로 더한다. */
data class RoadAidSelection(
    val name: String,
    val km: Double,
    val stopSec: Double
)

data class RoadAidPlan(
    val name: String,
    val km: Double,
    val arrivalElapsedSec: Double,
    val departureElapsedSec: Double
) {
    val stopSec: Double get() = (departureElapsedSec - arrivalElapsedSec).coerceAtLeast(0.0)
}

data class RoadCheckpoint(
    val name: String,
    val km: Double,
    val targetElapsedSec: Double,
    val stopSec: Double = 0.0
)

data class RoadPlan(
    /** 보급 정차를 제외한 순수 목표 주행시간. */
    val ridingTargetSec: Double,
    /** 목표 주행시간 + 선택한 보급 정차시간. */
    val totalSec: Double,
    val targetSpecified: Boolean,
    val checkpoints: List<RoadCheckpoint>,
    val samplesKm: DoubleArray,
    /** 정차시간을 제외한 순수 주행 누적시간. */
    val samplesElapsedSec: DoubleArray,
    val aidStops: List<RoadAidPlan>,
    val modelLabel: String
) {
    val totalStopSec: Double get() = aidStops.sumOf { it.stopSec }

    private fun ridingElapsedSec(km: Double): Double {
        if (samplesKm.isEmpty()) return 0.0
        val x = km.coerceIn(0.0, samplesKm.last())
        var lo = 0
        var hi = samplesKm.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samplesKm[mid] < x) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return samplesElapsedSec[0]
        val x1 = samplesKm[lo - 1]; val x2 = samplesKm[lo]
        val y1 = samplesElapsedSec[lo - 1]; val y2 = samplesElapsedSec[lo]
        val f = if (x2 > x1) ((x - x1) / (x2 - x1)).coerceIn(0.0, 1.0) else 1.0
        return y1 + (y2 - y1) * f
    }

    /** 실제 대회 경과시간 기준. 이미 지나간 보급소의 정차시간을 포함한다. */
    fun expectedElapsedSec(km: Double): Double {
        val x = km.coerceAtLeast(0.0)
        val priorStops = aidStops.filter { it.km < x - 0.001 }.sumOf { it.stopSec }
        return ridingElapsedSec(x) + priorStops
    }

    fun nextCheckpoint(km: Double): RoadCheckpoint? = checkpoints.firstOrNull { it.km > km + 0.05 }
}

/**
 * 개인 파워 예측을 하지 않는 목표 페이스 배분 엔진.
 * GPX 경사 난이도로 구간별 시간 비율만 만든 뒤 사용자가 정한 순수 주행시간에 정확히 맞춘다.
 * 보급소 정차시간은 순수 주행시간과 별도로 더해 최종 계획 완주시간을 만든다.
 */
object RoadGranfondoEngine {
    fun buildTargetPlan(
        course: CourseData,
        ridingTargetSec: Double,
        aidSelections: List<RoadAidSelection> = emptyList()
    ): RoadPlan {
        require(course.totalKm > 0.1) { "유효한 GPX 코스가 필요합니다." }
        require(ridingTargetSec >= 600.0) { "목표 주행시간을 확인해 주세요." }

        val aids = aidSelections
            .filter { it.km in 0.05..(course.totalKm - 0.05) && it.stopSec > 0.0 }
            .sortedBy { it.km }
        val totalStopSec = aids.sumOf { it.stopSec }

        // 보급소 위치도 샘플에 넣어 도착시간 계산이 끊기지 않게 한다.
        val step = 0.25
        val sampleSet = sortedSetOf<Double>()
        sampleSet += 0.0
        var k = step
        while (k < course.totalKm) { sampleSet += k; k += step }
        aids.forEach { sampleSet += it.km.coerceIn(0.0, course.totalKm) }
        sampleSet += course.totalKm
        val kms = sampleSet.toList().sorted()

        val raw = MutableList(kms.size) { 0.0 }
        var rawElapsed = 0.0
        for (i in 1 until kms.size) {
            val aKm = kms[i - 1]
            val bKm = kms[i]
            val distKm = (bKm - aKm).coerceAtLeast(0.0001)
            val a = course.pointAtKm(aKm)
            val b = course.pointAtKm(bKm)
            val grade = if (course.hasElevation) {
                ((b.ele - a.ele) / (distKm * 1000.0) * 100.0).coerceIn(-18.0, 22.0)
            } else 0.0
            val referenceSpeed = terrainReferenceSpeed(grade)
            rawElapsed += distKm / referenceSpeed * 3600.0
            raw[i] = rawElapsed
        }
        require(rawElapsed > 1.0) { "코스 시간 배분을 만들 수 없습니다." }
        val scale = ridingTargetSec / rawElapsed
        val ridingScaled = raw.map { it * scale }

        fun rideAt(km: Double): Double = interpolate(kms, ridingScaled, km)
        var priorStop = 0.0
        val aidPlans = aids.map { aid ->
            val arrival = rideAt(aid.km) + priorStop
            val departure = arrival + aid.stopSec
            priorStop += aid.stopSec
            RoadAidPlan(aid.name, aid.km, arrival, departure)
        }

        val checkpoints = buildCheckpointSeeds(course, aidPlans).map { (name, cpKm) ->
            val matchingAid = aidPlans.minByOrNull { kotlin.math.abs(it.km - cpKm) }
                ?.takeIf { kotlin.math.abs(it.km - cpKm) <= 0.02 }
            val elapsed = if (matchingAid != null) matchingAid.arrivalElapsedSec else {
                rideAt(cpKm) + aidPlans.filter { it.km < cpKm - 0.001 }.sumOf { it.stopSec }
            }
            RoadCheckpoint(name, cpKm, elapsed, matchingAid?.stopSec ?: 0.0)
        }

        return RoadPlan(
            ridingTargetSec = ridingTargetSec,
            totalSec = ridingTargetSec + totalStopSec,
            targetSpecified = true,
            checkpoints = checkpoints,
            samplesKm = kms.toDoubleArray(),
            samplesElapsedSec = ridingScaled.toDoubleArray(),
            aidStops = aidPlans,
            modelLabel = "GPX 경사 가중 목표 페이스 배분"
        )
    }

    fun courseKey(course: CourseData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder().append(String.format(Locale.US, "%.3f|%d|", course.totalKm, course.track.size))
        if (course.track.isNotEmpty()) {
            val picks = (0..12).map { i -> ((course.track.lastIndex.toDouble() * i / 12.0).toInt()).coerceIn(course.track.indices) }
            picks.forEach { idx ->
                val p = course.track[idx]
                sb.append(String.format(Locale.US, "%.5f,%.5f;", p.lat, p.lon))
            }
        }
        val bytes = digest.digest(sb.toString().toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun terrainReferenceSpeed(grade: Double): Double {
        // 절대 속도를 예측하는 값이 아니다. 목표시간을 구간별로 나누기 위한 상대 난이도 전용이다.
        return when {
            grade <= -8.0 -> 58.0
            grade <= -4.0 -> 50.0
            grade <= -1.0 -> 39.0
            grade < 1.0 -> 30.0
            else -> (30.0 * exp(-0.105 * grade)).coerceIn(6.5, 28.0)
        }
    }

    private fun buildCheckpointSeeds(course: CourseData, aids: List<RoadAidPlan>): List<Pair<String, Double>> {
        val all = mutableListOf<Pair<String, Double>>()
        var k = 10.0
        while (k < course.totalKm - 0.5) { all += ("${k.toInt()} km" to k); k += 10.0 }

        aids.forEach { a -> all += (a.name.ifBlank { "보급소" } to a.km) }

        var search = 0.0
        var climbNo = 1
        while (search < course.totalKm - 1.0 && climbNo <= 12) {
            val c = course.nextMajorClimb(search, 40.0) ?: break
            all += ("업힐 $climbNo 시작" to c.startKm)
            all += ("업힐 $climbNo 정상" to c.endKm)
            search = c.endKm + 0.5
            climbNo++
        }
        all += ("FINISH" to course.totalKm)

        return all.sortedBy { it.second }.fold(mutableListOf<Pair<String, Double>>()) { acc, item ->
            if (acc.isEmpty() || item.second - acc.last().second > 0.35) acc.add(item)
            else if (item.first == "FINISH") acc[acc.lastIndex] = item
            else if (aids.any { kotlin.math.abs(it.km - item.second) <= 0.02 }) acc[acc.lastIndex] = item
            acc
        }
    }

    private fun interpolate(xs: List<Double>, ys: List<Double>, xRaw: Double): Double {
        val x = xRaw.coerceIn(0.0, xs.lastOrNull() ?: 0.0)
        var lo = 0
        var hi = xs.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] < x) lo = mid + 1 else hi = mid
        }
        if (lo <= 0) return ys.getOrElse(0) { 0.0 }
        val a = xs[lo - 1]; val b = xs[lo]
        val f = if (b > a) (x - a) / (b - a) else 1.0
        return ys[lo - 1] + (ys[lo] - ys[lo - 1]) * f
    }
}
