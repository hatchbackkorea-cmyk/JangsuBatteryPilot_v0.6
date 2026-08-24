package com.seungjae.jangsu280battery

import kotlin.math.abs
import kotlin.math.floor


data class BatteryEstimate(
    val percent: Double,
    val note: String,
    val atChargePoint: Boolean = false,
    val calibrated: Boolean = false
)

data class Checkpoint(
    val km: Double,
    val name: String,
    val arrivalPct: Double,
    val chargeToPct: Double? = null
)

data class BatterySegment(
    val startKm: Double,
    val endKm: Double,
    val startPct: Double,
    val targetArrivalPct: Double,
    val name: String
)

class BatteryPlan(
    private val course: CourseData,
    private val learning: BatteryLearningStore
) {
    private val markers = course.batteryMarkers
    val isLegacyPlannedCourse: Boolean = markers.size >= 20 && markers.containsKey(50) && markers.containsKey(75) && markers.containsKey(100)

    val checkpoints: List<Checkpoint> = if (isLegacyPlannedCourse) {
        listOf(
            checkpointFromMarker(50, "1보급소 1차", 70.0),
            checkpointFromMarker(75, "1보급소 복귀", 70.0),
            checkpointFromMarker(100, "점심 / 충전", 75.0),
            Checkpoint(course.totalKm, "종점", markers[135]?.arrivalPct ?: 15.0, null)
        )
    } else {
        val supplies = course.supplyPois
            .filter { it.routeKm > 0.3 && it.routeKm < course.totalKm - 0.2 }
            .distinctBy { (it.routeKm * 10).toInt() }
            .map { poi -> Checkpoint(poi.routeKm, poi.name, genericEstimate(poi.routeKm), null) }
        supplies + Checkpoint(course.totalKm, "종점", genericEstimate(course.totalKm), null)
    }

    val segments: List<BatterySegment> = if (isLegacyPlannedCourse) {
        listOf(
            BatterySegment(0.0, 50.0, 100.0, checkpoints[0].arrivalPct, "출발 → 1보급소 1차"),
            BatterySegment(50.0, 75.0, checkpoints[0].chargeToPct ?: 70.0, checkpoints[1].arrivalPct, "싱글1 루프"),
            BatterySegment(75.0, 100.0, checkpoints[1].chargeToPct ?: 70.0, checkpoints[2].arrivalPct, "1보급소 → 점심"),
            BatterySegment(100.0, course.totalKm, checkpoints[2].chargeToPct ?: 75.0, checkpoints.last().arrivalPct, "점심 → 종점")
        )
    } else {
        listOf(BatterySegment(0.0, course.totalKm, 100.0, genericEstimate(course.totalKm), "출발 → 종점"))
    }

    private fun checkpointFromMarker(km: Int, name: String, fallbackCharge: Double): Checkpoint {
        val m = markers[km]
        return Checkpoint(km.toDouble(), name, m?.arrivalPct ?: legacyArrivalAt(km), m?.chargeToPct ?: fallbackCharge)
    }

    fun estimate(routeKm: Double): BatteryEstimate {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        if (!isLegacyPlannedCourse) {
            val p = genericEstimate(km)
            val learned = learning.samples().isNotEmpty()
            return BatteryEstimate(
                percent = p,
                note = if (learned) "GPX 거리·상승 + 개인 주행 학습 모델" else "GPX 거리·상승 + 장수 실측 기반 기본 모델",
                calibrated = learned
            )
        }

        if (km <= 0.0) return BatteryEstimate(100.0, "출발 100% 기준 · 장수 계획")
        for (cp in checkpoints) {
            if (cp.chargeToPct != null && abs(km - cp.km) <= 0.10) {
                return BatteryEstimate(cp.arrivalPct, "${cp.name}: ${cp.arrivalPct.toInt()}% → ${cp.chargeToPct.toInt()}% 충전", true)
            }
        }
        if (km >= 135.0) {
            val p = markers[135]?.arrivalPct ?: 15.0
            return BatteryEstimate(p, "종점 목표 약 ${p.toInt()}%")
        }

        val leftKm = floor(km).toInt().coerceAtLeast(0)
        val rightKm = (leftKm + 1).coerceAtMost(135)
        val frac = (km - leftKm).coerceIn(0.0, 1.0)
        val leftMarker = markers[leftKm]
        val chargeStart = leftMarker?.chargeToPct
        val leftPct = if (chargeStart != null && km > leftKm + 0.10) chargeStart else legacyArrivalAt(leftKm)
        val rightPct = legacyArrivalAt(rightKm)
        val p = leftPct + (rightPct - leftPct) * frac
        return BatteryEstimate(p.coerceIn(0.0, 100.0), "장수 GPX 1km 배터리 계획값")
    }

    fun travelReferencePercent(routeKm: Double): Double {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        if (!isLegacyPlannedCourse) return genericEstimate(km)
        val segment = segmentFor(km)
        if (abs(km - segment.startKm) <= 0.12) return segment.startPct
        return estimate(km).percent
    }

    fun segmentFor(routeKm: Double): BatterySegment {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        return segments.firstOrNull { km >= it.startKm - 0.12 && km < it.endKm - 0.12 } ?: segments.last()
    }

    fun plannedConsumption(fromKm: Double, toKm: Double): Double {
        if (toKm <= fromKm) return 0.0
        val from = fromKm.coerceIn(0.0, course.totalKm)
        val to = toKm.coerceIn(from, course.totalKm)
        if (!isLegacyPlannedCourse) return learning.estimateConsumption(course, from, to)
        val segment = segmentFor(from)
        if (to > segment.endKm + 0.12) return 0.0
        return (travelReferencePercent(from) - estimate(to).percent).coerceAtLeast(0.0)
    }

    fun confidenceRange(routeKm: Double, margin: Double = 4.0): ClosedFloatingPointRange<Double> {
        val p = estimate(routeKm).percent
        return (p - margin).coerceAtLeast(0.0)..(p + margin).coerceAtMost(100.0)
    }

    fun currentOrNextCheckpoint(routeKm: Double): Checkpoint? {
        checkpoints.firstOrNull { abs(routeKm - it.km) <= 0.15 }?.let { return it }
        return checkpoints.firstOrNull { it.km > routeKm }
    }

    fun checkpointAt(routeKm: Double, toleranceKm: Double = 0.15): Checkpoint? =
        checkpoints.firstOrNull { abs(routeKm - it.km) <= toleranceKm }

    fun plannedReferencePercent(routeKm: Double, kind: ActualEntryKind): Double {
        val cp = checkpointAt(routeKm, 0.35)
        if (kind == ActualEntryKind.POST_CHARGE && cp?.chargeToPct != null) return cp.chargeToPct
        return estimate(routeKm).percent
    }

    fun hasChargeStrictlyBeforeTarget(fromKm: Double, toKm: Double): Boolean = checkpoints.any {
        it.chargeToPct != null && it.km > fromKm + 0.10 && it.km < toKm - 0.10
    }

    fun hasChargeAtStartOrBeforeTarget(fromKm: Double, toKm: Double): Boolean = checkpoints.any {
        it.chargeToPct != null && it.km >= fromKm - 0.12 && it.km < toKm - 0.10
    }

    fun hasChargeBetween(fromKm: Double, toKm: Double): Boolean = checkpoints.any {
        it.chargeToPct != null && it.km > fromKm + 0.1 && it.km <= toKm + 0.1
    }

    fun assistText(routeKm: Double, battery: BatteryEstimate, stats10: ElevationStats): String {
        val cp = checkpoints.firstOrNull { it.chargeToPct != null && abs(routeKm - it.km) <= 0.15 }
        if (cp != null) return "⚡ ${cp.chargeToPct!!.toInt()}%까지 충전 후 다음 구간을 시작하세요."
        if (battery.percent <= 20.0) return "배터리 여유가 적습니다 · 보조 강도를 보수적으로"
        if (stats10.ascentM >= 600.0) return "강한 업힐 구간 예정 · 초반 과도한 어시스트 사용 주의"
        if (stats10.ascentM >= 400.0) return "오르막 비중 높음 · 계획 페이스 유지"
        if (stats10.descentM >= 550.0) return "다운힐 비중 큼 · 배터리 소비가 적은 구간"
        return "현재 소비 페이스 유지"
    }

    fun predictedTotalUsePct(): Double = if (isLegacyPlannedCourse) {
        segments.sumOf { (it.startPct - it.targetArrivalPct).coerceAtLeast(0.0) }
    } else learning.estimateConsumption(course, 0.0, course.totalKm)

    fun recommendedChargeKm(finishTargetPct: Double = 15.0): Double? {
        if (isLegacyPlannedCourse) return checkpoints.firstOrNull { it.chargeToPct != null }?.km
        val allowedUse = 100.0 - finishTargetPct.coerceIn(1.0, 99.0)
        val totalUse = predictedTotalUsePct()
        if (totalUse <= allowedUse) return null
        // 목표 잔량에 따라 첫 충전 검토 시점을 조정한다. 낮은 목표에서는 기존처럼 약 25% 잔량을 하한으로 사용한다.
        val thresholdUse = allowedUse.coerceIn(1.0, 75.0)
        var km = 1.0
        while (km < course.totalKm) {
            if (learning.estimateConsumption(course, 0.0, km) >= thresholdUse) return km
            km += 1.0
        }
        return (course.totalKm * 0.6).coerceAtLeast(1.0)
    }

    fun modelLabel(): String = when {
        isLegacyPlannedCourse -> "장수 전용 1km 계획 모델"
        learning.samples().isNotEmpty() -> "GPX + 개인 소비 학습"
        else -> "GPX + 장수 실측 기본 모델"
    }

    private fun genericEstimate(km: Double): Double =
        (100.0 - learning.estimateConsumption(course, 0.0, km.coerceIn(0.0, course.totalKm))).coerceIn(0.0, 100.0)

    private fun legacyArrivalAt(k: Int): Double = when (k) {
        0 -> 100.0
        else -> markers[k]?.arrivalPct ?: markers.entries.minByOrNull { abs(it.key - k) }?.value?.arrivalPct ?: 0.0
    }
}
