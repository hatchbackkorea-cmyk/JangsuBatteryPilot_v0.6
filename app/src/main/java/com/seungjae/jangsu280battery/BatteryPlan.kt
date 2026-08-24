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

class BatteryPlan(private val course: CourseData) {
    private val markers = course.batteryMarkers

    val checkpoints: List<Checkpoint> = listOf(
        checkpointFromMarker(50, "1보급소 1차", 70.0),
        checkpointFromMarker(75, "1보급소 복귀", 70.0),
        checkpointFromMarker(100, "점심 / 충전", 75.0),
        Checkpoint(course.totalKm, "스테이지1 종점", markers[135]?.arrivalPct ?: 15.0, null)
    )

    val segments: List<BatterySegment> = listOf(
        BatterySegment(0.0, 50.0, 100.0, checkpoints[0].arrivalPct, "출발 → 1보급소 1차"),
        BatterySegment(50.0, 75.0, checkpoints[0].chargeToPct ?: 70.0, checkpoints[1].arrivalPct, "싱글1 루프"),
        BatterySegment(75.0, 100.0, checkpoints[1].chargeToPct ?: 70.0, checkpoints[2].arrivalPct, "1보급소 → 점심"),
        BatterySegment(100.0, course.totalKm, checkpoints[2].chargeToPct ?: 75.0, checkpoints[3].arrivalPct, "점심 → 스테이지1 종점")
    )

    private fun checkpointFromMarker(km: Int, name: String, fallbackCharge: Double): Checkpoint {
        val m = markers[km]
        return Checkpoint(
            km = km.toDouble(),
            name = name,
            arrivalPct = m?.arrivalPct ?: 0.0,
            chargeToPct = m?.chargeToPct ?: fallbackCharge
        )
    }

    fun estimate(routeKm: Double): BatteryEstimate {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        if (km <= 0.0) return BatteryEstimate(100.0, "출발 100% 기준 · 참고 예측치")

        for (cp in checkpoints) {
            if (cp.chargeToPct != null && abs(km - cp.km) <= 0.10) {
                return BatteryEstimate(
                    percent = cp.arrivalPct,
                    note = "${cp.name}: ${cp.arrivalPct.toInt()}% → ${cp.chargeToPct.toInt()}% 충전",
                    atChargePoint = true
                )
            }
        }

        if (km >= 135.0) {
            val p = markers[135]?.arrivalPct ?: 15.0
            return BatteryEstimate(p, "스테이지1 종점 목표 약 ${p.toInt()}%")
        }

        val leftKm = floor(km).toInt().coerceAtLeast(0)
        val rightKm = (leftKm + 1).coerceAtMost(135)
        val frac = (km - leftKm).coerceIn(0.0, 1.0)

        fun arrivalAt(k: Int): Double = when (k) {
            0 -> 100.0
            else -> markers[k]?.arrivalPct
                ?: markers.entries.minByOrNull { abs(it.key - k) }?.value?.arrivalPct
                ?: 0.0
        }

        val leftMarker = markers[leftKm]
        val chargeStart = leftMarker?.chargeToPct
        val leftPct = if (chargeStart != null && km > leftKm + 0.10) chargeStart else arrivalAt(leftKm)
        val rightPct = arrivalAt(rightKm)
        val p = leftPct + (rightPct - leftPct) * frac

        return BatteryEstimate(
            percent = p.coerceIn(0.0, 100.0),
            note = "GPX·거리·고도 기반 계획값 · 실제 배터리와 차이 가능"
        )
    }

    /** 주행 소비량 계산용 계획 퍼센트. 충전 지점에서는 '충전 후 출발값'을 반환한다. */
    fun travelReferencePercent(routeKm: Double): Double {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        val segment = segmentFor(km)
        if (abs(km - segment.startKm) <= 0.12) return segment.startPct
        return estimate(km).percent
    }

    fun segmentFor(routeKm: Double): BatterySegment {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        return segments.firstOrNull { km >= it.startKm - 0.12 && km < it.endKm - 0.12 }
            ?: segments.last()
    }

    fun plannedConsumption(fromKm: Double, toKm: Double): Double {
        if (toKm <= fromKm) return 0.0
        val segment = segmentFor(fromKm)
        // 세그먼트 종점(50/75/100/종점)까지의 소비는 해당 세그먼트에 포함한다.
        if (toKm > segment.endKm + 0.12) return 0.0
        return (travelReferencePercent(fromKm) - estimate(toKm).percent).coerceAtLeast(0.0)
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

    fun hasChargeBetween(fromKm: Double, toKm: Double): Boolean = checkpoints.any {
        it.chargeToPct != null && it.km > fromKm + 0.1 && it.km <= toKm + 0.1
    }

    fun assistText(routeKm: Double, battery: BatteryEstimate, stats10: ElevationStats): String {
        val cp = checkpoints.firstOrNull { it.chargeToPct != null && abs(routeKm - it.km) <= 0.15 }
        if (cp != null) return "⚡ ${cp.chargeToPct!!.toInt()}%까지 충전 후 다음 구간을 시작하세요."
        if (battery.percent <= 20.0) return "배터리 여유가 적은 후반입니다 · 보조 강도 보수적으로"
        if (stats10.ascentM >= 600.0) return "강한 업힐 구간 예정 · 초반 과도한 어시스트 사용 주의"
        if (stats10.ascentM >= 400.0) return "오르막 비중 높음 · 계획 페이스 유지"
        if (stats10.descentM >= 550.0) return "다운힐 비중 큼 · 배터리 소비가 적은 구간"
        return "현재 계획 페이스 유지"
    }
}
