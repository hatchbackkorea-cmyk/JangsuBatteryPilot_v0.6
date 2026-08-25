package com.seungjae.jangsu280battery

import kotlin.math.abs
import kotlin.math.roundToInt


data class ConsumptionCalibration(
    val factor: Double,
    val anchorKm: Double,
    val anchorPercent: Double,
    val sampleCount: Int,
    val label: String
)

data class ActualBatteryStatus(
    val entry: ActualBatteryEntry,
    val plannedAtEntry: Double,
    val delta: Double,
    val activeForCurrentSegment: Boolean,
    val consumptionFactor: Double
)

data class ReserveStatus(
    val label: String,
    val predictedPct: Double,
    val targetPct: Double,
    val differencePct: Double,
    val targetName: String,
    val consumptionFactor: Double
)

class AdaptiveBatteryPlan(
    private val base: BatteryPlan,
    private val store: BatteryActualStore
) {
    val checkpoints: List<Checkpoint> get() = base.checkpoints

    /** 현재 충전 epoch(마지막 충전 후)의 실측 소비율을 기본 모델 대비 배율로 계산한다. */
    fun calibration(routeKm: Double): ConsumptionCalibration? {
        val km = routeKm.coerceAtLeast(0.0)
        val all = store.entries().filter { it.routeKm <= km + 0.15 }.sortedBy { it.timestampMs }
        if (all.isEmpty()) return null

        val lastPost = all.indexOfLast { it.kind == ActualEntryKind.POST_CHARGE }
        val startKm = if (lastPost >= 0) all[lastPost].routeKm else 0.0
        val startPct = if (lastPost >= 0) all[lastPost].percent else 100.0
        val epoch = if (lastPost >= 0) all.drop(lastPost + 1) else all
        val observations = epoch.filter { it.routeKm > startKm + 0.20 }
        val latest = observations.lastOrNull() ?: return null

        val plannedLong = base.plannedConsumption(startKm, latest.routeKm)
        val actualLong = startPct - latest.percent
        if (plannedLong < 1.0 || actualLong < 0.0) return null
        var longFactor = actualLong / plannedLong

        var recentFactor: Double? = null
        val prev = observations.dropLast(1).lastOrNull { it.routeKm < latest.routeKm - 0.30 }
        if (prev != null && !base.hasChargeStrictlyBeforeTarget(prev.routeKm, latest.routeKm)) {
            val plannedRecent = base.plannedConsumption(prev.routeKm, latest.routeKm)
            val actualRecent = prev.percent - latest.percent
            if (plannedRecent >= 0.8 && actualRecent >= 0.0) recentFactor = actualRecent / plannedRecent
        }

        longFactor = longFactor.coerceIn(0.45, 2.20)
        val raw = if (recentFactor != null) longFactor * 0.45 + recentFactor.coerceIn(0.45, 2.20) * 0.55 else longFactor
        val factor = raw.coerceIn(0.55, 1.80)
        val label = when {
            factor >= 1.16 -> "기본예측보다 ${(factor * 100 - 100).roundToInt()}% 빠른 소비"
            factor <= 0.88 -> "기본예측보다 ${(100 - factor * 100).roundToInt()}% 느린 소비"
            else -> "기본예측과 비슷한 소비"
        }
        return ConsumptionCalibration(factor, latest.routeKm, latest.percent, observations.size, label)
    }

    fun estimate(routeKm: Double): BatteryEstimate {
        val km = routeKm.coerceAtLeast(0.0)
        val baseEstimate = base.estimate(km)
        val history = store.entries().sortedBy { it.timestampMs }
        val exact = history.lastOrNull { abs(it.routeKm - km) <= 0.12 }
        if (exact != null) {
            return BatteryEstimate(exact.percent, "실제 배터리 ${exact.percent.toInt()}% 입력값", baseEstimate.atChargePoint, true)
        }

        val anchor = history.lastOrNull { it.routeKm <= km + 0.12 } ?: return baseEstimate
        if (anchor.kind == ActualEntryKind.ARRIVAL && base.hasChargeAtStartOrBeforeTarget(anchor.routeKm, km)) return baseEstimate
        if (base.hasChargeStrictlyBeforeTarget(anchor.routeKm, km)) return baseEstimate
        if (km <= anchor.routeKm + 0.01) return BatteryEstimate(anchor.percent, "최근 실제값 기준", calibrated = true)

        val plannedDrop = base.plannedConsumption(anchor.routeKm, km)
        if (plannedDrop <= 0.0 && km > anchor.routeKm + 0.2) return baseEstimate
        val factor = calibration(km)?.factor ?: 1.0
        val predicted = anchor.percent - plannedDrop * factor
        return BatteryEstimate(
            percent = predicted.coerceIn(0.0, 100.0),
            note = "${String.format(java.util.Locale.US, "%.1f", anchor.routeKm)}km 실제 ${anchor.percent.toInt()}% · 소비계수 ${String.format(java.util.Locale.US, "%.2f", factor)}x",
            atChargePoint = baseEstimate.atChargePoint,
            calibrated = true
        )
    }

    fun forecast(currentKm: Double, targetKm: Double): BatteryEstimate {
        val current = currentKm.coerceAtLeast(0.0)
        val target = targetKm.coerceAtLeast(current)
        if (base.hasChargeStrictlyBeforeTarget(current, target)) return base.estimate(target)
        val currentEstimate = estimate(current)
        val plannedDrop = base.plannedConsumption(current, target)
        if (plannedDrop <= 0.0 && target > current + 0.2) return base.estimate(target)
        val factor = calibration(current)?.factor ?: 1.0
        return BatteryEstimate(
            percent = (currentEstimate.percent - plannedDrop * factor).coerceIn(0.0, 100.0),
            note = "현재 소비율 ${String.format(java.util.Locale.US, "%.2f", factor)}배 적용",
            calibrated = currentEstimate.calibrated || factor != 1.0
        )
    }

    fun confidenceRange(routeKm: Double): ClosedFloatingPointRange<Double> {
        val p = estimate(routeKm)
        val cal = calibration(routeKm)
        val margin = when {
            cal == null -> 5.0
            cal.sampleCount >= 3 -> 2.0
            cal.sampleCount >= 2 -> 2.5
            else -> 3.5
        }
        return (p.percent - margin).coerceAtLeast(0.0)..(p.percent + margin).coerceAtMost(100.0)
    }

    fun reserveStatus(currentKm: Double, finishTargetPct: Double): ReserveStatus {
        val cp = base.currentOrNextCheckpoint(currentKm) ?: base.checkpoints.last()
        val isChargeTarget = cp.chargeToPct != null
        val targetName = if (isChargeTarget) "다음 충전 · ${cp.name}" else "종점"
        // 사용자 지정 충전소의 도착 최소잔량은 전역 목표잔량을 사용한다.
        // 장수 내장 고정 계획은 기존 도착 계획값을 유지한다.
        val targetPct = if (isChargeTarget && base.isLegacyPlannedCourse) cp.arrivalPct else finishTargetPct.coerceIn(1.0, 99.0)
        val targetKm = cp.km
        val predicted = forecast(currentKm, targetKm).percent
        val diff = predicted - targetPct
        val label = when {
            diff >= 4.0 -> "여유"
            diff >= -2.0 -> "주의"
            else -> "위험"
        }
        return ReserveStatus(label, predicted, targetPct, diff, targetName, calibration(currentKm)?.factor ?: 1.0)
    }

    fun latestStatus(currentKm: Double): ActualBatteryStatus? {
        val entry = store.latest() ?: return null
        val planned = base.plannedReferencePercent(entry.routeKm, entry.kind)
        val active = entry.routeKm <= currentKm + 0.12 &&
            !(entry.kind == ActualEntryKind.ARRIVAL && base.hasChargeAtStartOrBeforeTarget(entry.routeKm, currentKm)) &&
            !base.hasChargeStrictlyBeforeTarget(entry.routeKm, currentKm)
        return ActualBatteryStatus(entry, planned, entry.percent - planned, active, calibration(currentKm)?.factor ?: 1.0)
    }

    fun currentOrNextCheckpoint(routeKm: Double): Checkpoint? = base.currentOrNextCheckpoint(routeKm)
    fun checkpointAt(routeKm: Double, toleranceKm: Double = 0.30): Checkpoint? = base.checkpointAt(routeKm, toleranceKm)
    fun hasChargeBetween(fromKm: Double, toKm: Double): Boolean = base.hasChargeBetween(fromKm, toKm)
    fun assistText(routeKm: Double, battery: BatteryEstimate, stats10: ElevationStats): String = base.assistText(routeKm, battery, stats10)
    fun recommendedChargeKm(finishTargetPct: Double): Double? = base.recommendedChargeKm(finishTargetPct)
    fun predictedTotalUsePct(): Double = base.predictedTotalUsePct()
    fun modelLabel(): String = base.modelLabel()
    fun isLegacyPlan(): Boolean = base.isLegacyPlannedCourse

}
