package com.seungjae.jangsu280battery

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max

data class RoadCheckpoint(val name: String, val km: Double, val targetElapsedSec: Double)

data class RoadPlan(
    val totalSec: Double,
    val targetSpecified: Boolean,
    val checkpoints: List<RoadCheckpoint>,
    val samplesKm: DoubleArray,
    val samplesElapsedSec: DoubleArray,
    val modelLabel: String
) {
    fun expectedElapsedSec(km: Double): Double {
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

    fun nextCheckpoint(km: Double): RoadCheckpoint? = checkpoints.firstOrNull { it.km > km + 0.05 }
}

object RoadGranfondoEngine {
    fun buildPlan(course: CourseData, targetSec: Double?, profile: RoadTrainingProfile): RoadPlan {
        require(course.totalKm > 0.1) { "유효한 GPX 코스가 필요합니다." }
        val step = 0.25
        val kmList = mutableListOf(0.0)
        val rawSec = mutableListOf(0.0)
        var km = 0.0
        var elapsed = 0.0
        while (km < course.totalKm - 0.0001) {
            val next = (km + step).coerceAtMost(course.totalKm)
            val a = course.pointAtKm(km)
            val b = course.pointAtKm(next)
            val distKm = (next - km).coerceAtLeast(0.001)
            val grade = if (course.hasElevation) ((b.ele - a.ele) / (distKm * 1000.0) * 100.0).coerceIn(-18.0, 22.0) else 0.0
            val speed = speedForGrade(grade, profile).coerceIn(5.0, 75.0)
            elapsed += distKm / speed * 3600.0
            kmList += next
            rawSec += elapsed
            km = next
        }
        val wanted = targetSec?.takeIf { it > 600.0 }
        val scale = if (wanted != null && elapsed > 1.0) wanted / elapsed else 1.0
        val scaled = rawSec.map { it * scale }
        val total = scaled.lastOrNull() ?: 0.0
        val cps = buildCheckpointSeeds(course).map { (name, cpKm) ->
            RoadCheckpoint(name, cpKm, interpolate(kmList, scaled, cpKm))
        }
        val modelLabel = when {
            profile.fitCount > 0 && profile.power.sustainableW() != null -> "내 FIT ${profile.fitCount}개 + 시간별 파워"
            profile.fitCount > 0 -> "내 FIT ${profile.fitCount}개"
            profile.power.sustainableW() != null -> "시간별 파워 + GPX 고도"
            else -> "GPX 고도 기본 모델"
        }
        return RoadPlan(total, wanted != null, cps, kmList.toDoubleArray(), scaled.toDoubleArray(), modelLabel)
    }

    fun courseKey(course: CourseData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder().append(String.format(Locale.US, "%.3f|%d|", course.totalKm, course.track.size))
        if (course.track.isNotEmpty()) {
            val picks = (0..12).map { i -> ((course.track.lastIndex.toDouble() * i / 12.0).toInt()).coerceIn(course.track.indices) }
            picks.forEach { idx -> val p = course.track[idx]; sb.append(String.format(Locale.US, "%.5f,%.5f;", p.lat, p.lon)) }
        }
        val bytes = digest.digest(sb.toString().toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun speedForGrade(grade: Double, profile: RoadTrainingProfile): Double {
        profile.speedForGrade(grade)?.takeIf { it in 5.0..75.0 }?.let { return it }
        val baseFromFit = profile.overallSpeedKph()?.takeIf { it in 15.0..50.0 }
        val sustainable = profile.power.sustainableW()
        val base = baseFromFit ?: (28.0 + ((sustainable ?: 180.0) - 180.0) * 0.035).coerceIn(22.0, 40.0)
        val powerFactor = ((sustainable ?: 180.0) / 180.0).coerceIn(0.65, 1.6)
        return when {
            grade <= -6.0 -> (base * 1.55).coerceAtMost(70.0)
            grade <= -2.0 -> base * (1.18 + (-grade - 2.0) * 0.045)
            grade < 1.0 -> base * (1.0 - max(grade, 0.0) * 0.025)
            else -> base * exp(-0.095 * grade / powerFactor)
        }
    }

    private fun buildCheckpointSeeds(course: CourseData): List<Pair<String, Double>> {
        val all = mutableListOf<Pair<String, Double>>()
        var k = 10.0
        while (k < course.totalKm - 0.5) { all += ("${k.toInt()} km" to k); k += 10.0 }
        course.pois.forEach { p -> if (p.routeKm in 0.2..(course.totalKm - 0.2)) all += (p.name.ifBlank { "포인트" } to p.routeKm) }
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
            acc
        }
    }

    private fun interpolate(xs: List<Double>, ys: List<Double>, xRaw: Double): Double {
        val x = xRaw.coerceIn(0.0, xs.lastOrNull() ?: 0.0)
        val idx = xs.indexOfFirst { it >= x }.let { if (it < 0) xs.lastIndex else it }
        if (idx <= 0) return ys.getOrElse(0) { 0.0 }
        val a = xs[idx - 1]; val b = xs[idx]
        val f = if (b > a) (x - a) / (b - a) else 1.0
        return ys[idx - 1] + (ys[idx] - ys[idx - 1]) * f
    }
}
