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

/** 컷오프 시각은 대회 출발일 기준 시각(0~1439분)으로 저장한다. */
data class RoadCutoffSelection(
    val name: String,
    val km: Double,
    val cutoffMinuteOfDay: Int
)

data class RoadCutoffConstraint(
    val name: String,
    val km: Double,
    val cutoffMinuteOfDay: Int,
    /** 출발 후 컷오프까지 허용되는 실제 경과시간. */
    val deadlineElapsedSec: Double,
    /** 이 컷오프 도착 전에 이미 사용한 보급 정차시간. */
    val stopBeforeSec: Double,
    /** 코스 전체 순수 주행시간 중 해당 지점까지 필요한 비율. */
    val ridingShare: Double,
    /** 이 컷오프를 정확히 맞출 수 있는 코스 전체 최대 순수 주행시간. */
    val maxRidingTargetSec: Double
)

data class RoadCutoffSolution(
    val ridingTargetSec: Double,
    val requiredAvgKph: Double,
    val controlling: RoadCutoffConstraint,
    val constraints: List<RoadCutoffConstraint>
)

data class RoadCutoffPlan(
    val name: String,
    val km: Double,
    val cutoffMinuteOfDay: Int,
    val deadlineElapsedSec: Double,
    val plannedArrivalElapsedSec: Double,
    val marginSec: Double
)

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
    val cutoffs: List<RoadCutoffPlan> = emptyList(),
    val modelLabel: String
) {
    val totalStopSec: Double get() = aidStops.sumOf { it.stopSec }

    /** 정차시간을 제외한 순수 주행 누적시간. */
    fun ridingElapsedSecAt(km: Double): Double {
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
        return ridingElapsedSecAt(x) + priorStops
    }

    fun nextCheckpoint(km: Double): RoadCheckpoint? = checkpoints.firstOrNull { it.km > km + 0.05 }
}

/**
 * 개인 파워 예측을 하지 않는 목표 페이스 배분 엔진.
 * GPX 경사 난이도로 구간별 시간 비율만 만든 뒤 사용자가 정한 순수 주행시간에 정확히 맞춘다.
 * 보급소 정차시간은 순수 주행시간과 별도로 더해 최종 계획 완주시간을 만든다.
 * 컷오프 모드는 사용자가 속도를 정하지 않고 컷오프 시각 + 보급 정차만 주면 필요한 최소 주행평속을 역산한다.
 */
object RoadGranfondoEngine {
    fun buildTargetPlan(
        course: CourseData,
        ridingTargetSec: Double,
        aidSelections: List<RoadAidSelection> = emptyList()
    ): RoadPlan = buildPlanInternal(course, ridingTargetSec, aidSelections, emptyList(), "GPX 경사 가중 목표 페이스 배분")

    /**
     * 컷오프 기준 자동 페이스 계획.
     * 가장 빡빡한 컷오프를 정확히 맞추는 순수 주행시간을 찾아 전체 코스에 동일한 경사 가중 페이스 비율로 적용한다.
     */
    fun buildCutoffPlan(
        course: CourseData,
        startMinuteOfDay: Int,
        cutoffSelections: List<RoadCutoffSelection>,
        aidSelections: List<RoadAidSelection> = emptyList()
    ): RoadPlan {
        val solution = solveCutoffTarget(course, startMinuteOfDay, cutoffSelections, aidSelections)
        val base = buildPlanInternal(
            course = course,
            ridingTargetSec = solution.ridingTargetSec,
            aidSelections = aidSelections,
            cutoffSeeds = cutoffSelections,
            modelLabel = "GPX 경사 가중 컷오프 역산 페이스"
        )
        val cutoffPlans = solution.constraints.map { c ->
            val planned = base.expectedElapsedSec(c.km)
            RoadCutoffPlan(
                name = c.name,
                km = c.km,
                cutoffMinuteOfDay = c.cutoffMinuteOfDay,
                deadlineElapsedSec = c.deadlineElapsedSec,
                plannedArrivalElapsedSec = planned,
                marginSec = c.deadlineElapsedSec - planned
            )
        }.sortedBy { it.km }
        return base.copy(cutoffs = cutoffPlans)
    }

    /** 컷오프 시각과 보급시간만으로 필요한 최소 평속을 역산한다. */
    fun solveCutoffTarget(
        course: CourseData,
        startMinuteOfDay: Int,
        cutoffSelections: List<RoadCutoffSelection>,
        aidSelections: List<RoadAidSelection> = emptyList()
    ): RoadCutoffSolution {
        require(course.totalKm > 0.1) { "유효한 GPX 코스가 필요합니다." }
        val cutoffs = cutoffSelections
            .filter { it.km in 0.05..(course.totalKm + 0.05) }
            .distinctBy { String.format(Locale.US, "%.3f|%d", it.km, it.cutoffMinuteOfDay) }
            .sortedBy { it.km }
        require(cutoffs.isNotEmpty()) { "컷오프 지점을 1곳 이상 체크해 주세요." }

        val aids = aidSelections
            .filter { it.km in 0.05..(course.totalKm - 0.05) && it.stopSec > 0.0 }
            .sortedBy { it.km }

        // 1시간짜리 단위 계획을 만들어 각 지점까지의 '순수 주행시간 비율'만 얻는다.
        val unit = buildPlanInternal(course, 3600.0, emptyList(), emptyList(), "컷오프 비율 계산")
        val constraints = cutoffs.map { cutoff ->
            val km = cutoff.km.coerceIn(0.05, course.totalKm)
            val share = (unit.ridingElapsedSecAt(km) / 3600.0).coerceIn(0.0001, 1.0)
            val deadline = elapsedSecondsToClock(startMinuteOfDay, cutoff.cutoffMinuteOfDay)
            val stopBefore = aids.filter { it.km < km - 0.001 }.sumOf { it.stopSec }
            val rideAvailableToCutoff = deadline - stopBefore
            require(rideAvailableToCutoff > 60.0) {
                "${cutoff.name} 컷오프 전 보급시간이 너무 길거나 컷오프 시각이 출발시각보다 빠릅니다."
            }
            val maxWholeRide = rideAvailableToCutoff / share
            require(maxWholeRide >= 600.0) { "${cutoff.name} 컷오프 조건으로는 유효한 페이스를 만들 수 없습니다." }
            RoadCutoffConstraint(
                name = cutoff.name,
                km = km,
                cutoffMinuteOfDay = cutoff.cutoffMinuteOfDay.coerceIn(0, 1439),
                deadlineElapsedSec = deadline,
                stopBeforeSec = stopBefore,
                ridingShare = share,
                maxRidingTargetSec = maxWholeRide
            )
        }
        val controlling = constraints.minByOrNull { it.maxRidingTargetSec }
            ?: error("컷오프 계산에 실패했습니다.")
        val ridingTarget = controlling.maxRidingTargetSec
        val requiredAvg = course.totalKm / (ridingTarget / 3600.0)
        return RoadCutoffSolution(ridingTarget, requiredAvg, controlling, constraints)
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

    private fun buildPlanInternal(
        course: CourseData,
        ridingTargetSec: Double,
        aidSelections: List<RoadAidSelection>,
        cutoffSeeds: List<RoadCutoffSelection>,
        modelLabel: String
    ): RoadPlan {
        require(course.totalKm > 0.1) { "유효한 GPX 코스가 필요합니다." }
        require(ridingTargetSec >= 600.0) { "목표 주행시간을 확인해 주세요." }

        val aids = aidSelections
            .filter { it.km in 0.05..(course.totalKm - 0.05) && it.stopSec > 0.0 }
            .sortedBy { it.km }
        val totalStopSec = aids.sumOf { it.stopSec }

        // 보급소/컷오프 위치도 샘플에 넣어 도착시간 계산이 끊기지 않게 한다.
        val step = 0.25
        val sampleSet = sortedSetOf<Double>()
        sampleSet += 0.0
        var k = step
        while (k < course.totalKm) { sampleSet += k; k += step }
        aids.forEach { sampleSet += it.km.coerceIn(0.0, course.totalKm) }
        cutoffSeeds.forEach { sampleSet += it.km.coerceIn(0.0, course.totalKm) }
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

        val checkpoints = buildCheckpointSeeds(course, aidPlans, cutoffSeeds).map { (name, cpKm) ->
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
            cutoffs = emptyList(),
            modelLabel = modelLabel
        )
    }

    private fun elapsedSecondsToClock(startMinuteOfDay: Int, targetMinuteOfDay: Int): Double {
        val start = ((startMinuteOfDay % 1440) + 1440) % 1440
        val target = ((targetMinuteOfDay % 1440) + 1440) % 1440
        var deltaMin = target - start
        if (deltaMin <= 0) deltaMin += 1440
        return deltaMin * 60.0
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

    private fun buildCheckpointSeeds(
        course: CourseData,
        aids: List<RoadAidPlan>,
        cutoffs: List<RoadCutoffSelection>
    ): List<Pair<String, Double>> {
        val all = mutableListOf<Pair<String, Double>>()
        var k = 10.0
        while (k < course.totalKm - 0.5) { all += ("${k.toInt()} km" to k); k += 10.0 }

        aids.forEach { a -> all += (a.name.ifBlank { "보급소" } to a.km) }
        cutoffs.forEach { c -> all += ("컷오프 · ${c.name}" to c.km.coerceIn(0.0, course.totalKm)) }

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
            if (acc.isEmpty() || item.second - acc.last().second > 0.35) {
                acc.add(item)
            } else {
                val isCutoff = item.first.startsWith("컷오프")
                val lastIsCutoff = acc.last().first.startsWith("컷오프")
                when {
                    item.first == "FINISH" -> acc[acc.lastIndex] = item
                    isCutoff -> acc[acc.lastIndex] = item
                    lastIsCutoff -> Unit
                    aids.any { kotlin.math.abs(it.km - item.second) <= 0.02 } -> acc[acc.lastIndex] = item
                }
            }
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
