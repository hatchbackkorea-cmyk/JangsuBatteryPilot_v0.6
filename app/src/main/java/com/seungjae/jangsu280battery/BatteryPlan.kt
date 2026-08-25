package com.seungjae.jangsu280battery

import kotlin.math.abs


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
    val chargeToPct: Double? = null,
    val detourKm: Double = 0.0,
    val source: String = ""
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
    private val learning: BatteryLearningStore,
    chargingStations: List<ChargingStation> = emptyList(),
    private val avinoxReference: AvinoxCourseReference? = null
) {
    private val configuredStations = chargingStations
        .filter { it.routeKm > 0.25 && it.routeKm < course.totalKm - 0.15 }
        .sortedBy { it.routeKm }
        .distinctBy { (it.routeKm * 100).toInt() }

    /** v0.11.0부터 모든 코스는 동일한 중립 모델 + 실제 학습 데이터만 사용한다. */
    val isLegacyPlannedCourse: Boolean = false

    val hasConfiguredChargingStations: Boolean get() = configuredStations.isNotEmpty()

    /**
     * Avinox GPX 예상 소비율은 실제 학습 샘플과 분리된 외부 기준이다.
     * 선택 모드 값이 있을 때만 전체 코스 소비량의 prior로 제한적으로 사용하고,
     * 실제 개인 학습이 쌓일수록 가중치를 자동으로 낮춘다.
     */
    private val internalModelTotalUsePct: Double by lazy {
        learning.estimateConsumption(course, 0.0, course.totalKm).coerceAtLeast(0.0)
    }
    private val activeAvinoxUsePct: Double? by lazy { avinoxReference?.selectedValue() }
    private val avinoxWeight: Double by lazy {
        if (activeAvinoxUsePct == null) 0.0 else when (learning.samples().size) {
            0 -> 0.45
            1, 2 -> 0.30
            in 3..5 -> 0.20
            in 6..11 -> 0.12
            else -> 0.08
        }
    }
    private val requestedBlendedTotalUsePct: Double by lazy {
        val internal = internalModelTotalUsePct
        val external = activeAvinoxUsePct
        if (external == null || internal <= 0.05) internal
        else (internal * (1.0 - avinoxWeight) + external * avinoxWeight).coerceIn(0.0, 100.0)
    }
    private val externalScale: Double by lazy {
        if (internalModelTotalUsePct <= 0.05) 1.0
        else (requestedBlendedTotalUsePct / internalModelTotalUsePct).coerceIn(0.70, 1.35)
    }
    private val plannedModelTotalUsePct: Double by lazy {
        (internalModelTotalUsePct * externalScale).coerceIn(0.0, 100.0)
    }

    private fun modelConsumption(fromKm: Double, toKm: Double): Double =
        (learning.estimateConsumption(course, fromKm, toKm) * externalScale).coerceAtLeast(0.0)

    fun internalTotalUsePct(): Double = internalModelTotalUsePct
    fun avinoxUsePct(): Double? = activeAvinoxUsePct
    fun avinoxMode(): AvinoxRideMode? = avinoxReference?.selectedMode?.takeIf { avinoxReference.value(it) != null }
    fun avinoxWeightPct(): Int = (avinoxWeight * 100.0).toInt()
    fun hasAvinoxReference(): Boolean = activeAvinoxUsePct != null

    val checkpoints: List<Checkpoint> = if (configuredStations.isNotEmpty()) {
        buildConfiguredCheckpoints()
    } else {
        listOf(Checkpoint(course.totalKm, "종점", genericEstimateFromStart(course.totalKm), null))
    }

    val segments: List<BatterySegment> = buildGenericSegments()

    private fun buildConfiguredCheckpoints(): List<Checkpoint> {
        val out = mutableListOf<Checkpoint>()
        var startKm = 0.0
        var startPct = 100.0
        for (station in configuredStations) {
            val use = modelConsumption(startKm, station.routeKm) + detourUsePct(station.detourKm)
            val arrival = (startPct - use).coerceIn(0.0, 100.0)
            out += Checkpoint(
                km = station.routeKm,
                name = station.name,
                arrivalPct = arrival,
                chargeToPct = station.chargeToPct.coerceIn(1.0, 100.0),
                detourKm = station.detourKm,
                source = station.source
            )
            startKm = station.routeKm
            startPct = station.chargeToPct.coerceIn(1.0, 100.0)
        }
        val finishUse = modelConsumption(startKm, course.totalKm)
        out += Checkpoint(course.totalKm, "종점", (startPct - finishUse).coerceIn(0.0, 100.0), null)
        return out
    }

    private fun buildGenericSegments(): List<BatterySegment> {
        val result = mutableListOf<BatterySegment>()
        var startKm = 0.0
        var startPct = 100.0
        for (cp in checkpoints) {
            result += BatterySegment(
                startKm = startKm,
                endKm = cp.km,
                startPct = startPct,
                targetArrivalPct = cp.arrivalPct,
                name = if (cp.km >= course.totalKm - 0.05) "${if (startKm <= 0.01) "출발" else "충전소"} → 종점" else "${if (startKm <= 0.01) "출발" else "충전소"} → ${cp.name}"
            )
            startKm = cp.km
            if (cp.chargeToPct != null) startPct = cp.chargeToPct
        }
        return result
    }


    fun estimate(routeKm: Double): BatteryEstimate {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        val cp = checkpointAt(km, 0.10)
        if (cp?.chargeToPct != null) {
            return BatteryEstimate(
                cp.arrivalPct,
                "${cp.name}: 도착 예상 ${cp.arrivalPct.toInt()}% → ${cp.chargeToPct.toInt()}% 충전",
                atChargePoint = true,
                calibrated = learning.samples().isNotEmpty()
            )
        }
        val priorCp = checkpoints.lastOrNull { it.chargeToPct != null && it.km < km - 0.10 }
        val startKm = priorCp?.km ?: 0.0
        val startPct = priorCp?.chargeToPct ?: 100.0
        val use = modelConsumption(startKm, km)
        val p = (startPct - use).coerceIn(0.0, 100.0)
        val learned = learning.samples().isNotEmpty()
        return BatteryEstimate(
            percent = p,
            note = if (hasConfiguredChargingStations) "GPX + 선택 충전소 계획${if (learned) " + 개인 학습" else ""}${if (hasAvinoxReference()) " + Avinox 기준" else ""}"
                else if (learned) "GPX 거리·상승 + 개인 주행 학습 모델${if (hasAvinoxReference()) " + Avinox 기준" else ""}"
                else "GPX 거리·상승 중립 기본 모델${if (hasAvinoxReference()) " + Avinox 기준" else ""}",
            calibrated = learned
        )
    }

    fun travelReferencePercent(routeKm: Double): Double = estimate(routeKm.coerceIn(0.0, course.totalKm)).percent

    fun segmentFor(routeKm: Double): BatterySegment {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        return segments.firstOrNull { km >= it.startKm - 0.12 && km < it.endKm - 0.12 } ?: segments.last()
    }

    fun plannedConsumption(fromKm: Double, toKm: Double): Double {
        if (toKm <= fromKm) return 0.0
        val from = fromKm.coerceIn(0.0, course.totalKm)
        val to = toKm.coerceIn(from, course.totalKm)
        if (hasChargeStrictlyBeforeTarget(from, to)) return 0.0
        var use = modelConsumption(from, to)
        val targetCp = checkpoints.firstOrNull { it.chargeToPct != null && abs(it.km - to) <= 0.12 && it.km > from + 0.10 }
        if (targetCp != null) use += detourUsePct(targetCp.detourKm)
        return use.coerceAtLeast(0.0)
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

    fun predictedTotalUsePct(): Double = plannedModelTotalUsePct

    fun recommendedChargeKm(finishTargetPct: Double = 15.0): Double? {
        if (configuredStations.isNotEmpty()) return null
        val allowedUse = 100.0 - finishTargetPct.coerceIn(1.0, 99.0)
        val totalUse = predictedTotalUsePct()
        if (totalUse <= allowedUse) return null
        val thresholdUse = allowedUse.coerceIn(1.0, 75.0)
        var km = 1.0
        while (km < course.totalKm) {
            if (modelConsumption(0.0, km) >= thresholdUse) return km
            km += 1.0
        }
        return (course.totalKm * 0.6).coerceAtLeast(1.0)
    }

    fun modelLabel(): String {
        val core = when {
            configuredStations.isNotEmpty() && learning.samples().isNotEmpty() -> "GPX + 선택 충전소 + 개인 소비 학습"
            configuredStations.isNotEmpty() -> "GPX + 선택 충전소 계획"
            learning.samples().isNotEmpty() -> "GPX + 개인 소비 학습"
            else -> "GPX + 중립 기본 모델"
        }
        val avinox = avinoxMode()?.let { mode -> " + Avinox ${mode.label} 외부기준" }.orEmpty()
        return core + avinox
    }

    private fun genericEstimateFromStart(km: Double): Double =
        (100.0 - modelConsumption(0.0, km.coerceIn(0.0, course.totalKm))).coerceIn(0.0, 100.0)

    /** 코스 이탈 우회거리는 GPX 평균 예상 소비율로 보수적으로 환산한다. */
    private fun detourUsePct(detourKm: Double): Double {
        if (detourKm <= 0.0 || course.totalKm <= 0.1) return 0.0
        val averagePctPerKm = predictedTotalUsePct() / course.totalKm
        return (detourKm * averagePctPerKm).coerceAtLeast(0.0)
    }

}
