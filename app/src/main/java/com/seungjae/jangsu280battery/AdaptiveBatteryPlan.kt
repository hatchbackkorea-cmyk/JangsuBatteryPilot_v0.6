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

    fun calibration(routeKm: Double): ConsumptionCalibration? {
        val km = routeKm.coerceAtLeast(0.0)
        val segment = base.segmentFor(km)
        val history = store.entries()
            .filter { it.kind != ActualEntryKind.ARRIVAL }
            .filter { it.routeKm >= segment.startKm - 0.40 && it.routeKm <= km + 0.12 }
            .sortedBy { it.timestampMs }

        val latest = history.lastOrNull() ?: return null
        if (latest.routeKm < segment.startKm - 0.35) return null

        val startEntry = history.lastOrNull {
            it.kind == ActualEntryKind.POST_CHARGE && abs(it.routeKm - segment.startKm) <= 0.45
        }
        val startActualPct = startEntry?.percent ?: segment.startPct
        val startKm = startEntry?.routeKm ?: segment.startKm

        var longFactor = 1.0
        val plannedLong = base.plannedConsumption(startKm, latest.routeKm)
        val actualLong = startActualPct - latest.percent
        if (plannedLong >= 1.5 && actualLong >= 0.0) longFactor = actualLong / plannedLong

        var recentFactor: Double? = null
        if (history.size >= 2) {
            val prev = history.dropLast(1).lastOrNull { it.routeKm < latest.routeKm - 0.20 }
            if (prev != null) {
                val plannedRecent = base.plannedConsumption(prev.routeKm, latest.routeKm)
                val actualRecent = prev.percent - latest.percent
                if (plannedRecent >= 1.0 && actualRecent >= 0.0) recentFactor = actualRecent / plannedRecent
            }
        }

        val raw = if (recentFactor != null) longFactor * 0.45 + recentFactor * 0.55 else longFactor
        val factor = raw.coerceIn(0.55, 1.80)
        val count = history.size
        val label = when {
            factor >= 1.16 -> "계획보다 ${(factor * 100 - 100).roundToInt()}% 빠른 소비"
            factor <= 0.88 -> "계획보다 ${(100 - factor * 100).roundToInt()}% 느린 소비"
            else -> "계획과 비슷한 소비"
        }
        return ConsumptionCalibration(factor, latest.routeKm, latest.percent, count, label)
    }

    fun estimate(routeKm: Double): BatteryEstimate {
        val km = routeKm.coerceAtLeast(0.0)
        val baseEstimate = base.estimate(km)
        val history = store.entries()

        val exact = history.lastOrNull { abs(it.routeKm - km) <= 0.12 }
        if (exact != null) {
            return BatteryEstimate(
                percent = exact.percent,
                note = "실제 배터리 ${exact.percent.toInt()}% 입력값 반영",
                atChargePoint = baseEstimate.atChargePoint,
                calibrated = true
            )
        }

        val cal = calibration(km) ?: return baseEstimate
        if (base.hasChargeStrictlyBeforeTarget(cal.anchorKm, km)) return baseEstimate

        val plannedDrop = base.plannedConsumption(cal.anchorKm, km)
        val predicted = cal.anchorPercent - plannedDrop * cal.factor
        return BatteryEstimate(
            percent = predicted.coerceIn(0.0, 100.0),
            note = "${cal.anchorKm.format1()}km 실제 ${cal.anchorPercent.toInt()}% · ${cal.label}",
            atChargePoint = baseEstimate.atChargePoint,
            calibrated = true
        )
    }

    /** 현재 지점에서 특정 미래 지점까지 예측. 중간에 계획 충전이 있으면 그 이후는 계획값으로 리셋한다. */
    fun forecast(currentKm: Double, targetKm: Double): BatteryEstimate {
        val current = currentKm.coerceAtLeast(0.0)
        val target = targetKm.coerceAtLeast(current)
        if (base.hasChargeStrictlyBeforeTarget(current, target)) return base.estimate(target)

        val exactOrCurrent = store.entries().lastOrNull {
            it.kind != ActualEntryKind.ARRIVAL && it.routeKm <= current + 0.15 &&
                !base.hasChargeStrictlyBeforeTarget(it.routeKm, current)
        }
        val cal = calibration(current)
        if (exactOrCurrent == null || cal == null) return base.estimate(target)

        val anchorPct = estimate(current).percent
        val plannedDrop = base.plannedConsumption(current, target)
        val predicted = anchorPct - plannedDrop * cal.factor
        return BatteryEstimate(
            predicted.coerceIn(0.0, 100.0),
            "현재 소비율 ${String.format(java.util.Locale.US, "%.2f", cal.factor)}배 적용",
            calibrated = true
        )
    }

    fun confidenceRange(routeKm: Double): ClosedFloatingPointRange<Double> {
        val p = estimate(routeKm)
        val cal = calibration(routeKm)
        val margin = when {
            cal == null -> 4.0
            cal.sampleCount >= 3 -> 2.0
            cal.sampleCount >= 2 -> 2.5
            else -> 3.0
        }
        return (p.percent - margin).coerceAtLeast(0.0)..(p.percent + margin).coerceAtMost(100.0)
    }

    fun reserveStatus(currentKm: Double, finishTargetPct: Double): ReserveStatus {
        val cp = base.currentOrNextCheckpoint(currentKm)
        val targetName: String
        val targetPct: Double
        val targetKm: Double
        if (currentKm >= 100.0 || cp == null || cp.chargeToPct == null) {
            targetName = "스테이지1 종점"
            targetPct = finishTargetPct
            targetKm = base.checkpoints.last().km
        } else {
            targetName = cp.name
            targetPct = cp.arrivalPct
            targetKm = cp.km
        }
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
        val delta = entry.percent - planned
        val active = entry.kind != ActualEntryKind.ARRIVAL &&
            entry.routeKm <= currentKm + 0.12 &&
            !base.hasChargeStrictlyBeforeTarget(entry.routeKm, currentKm)
        return ActualBatteryStatus(entry, planned, delta, active, calibration(currentKm)?.factor ?: 1.0)
    }

    fun currentOrNextCheckpoint(routeKm: Double): Checkpoint? = base.currentOrNextCheckpoint(routeKm)
    fun checkpointAt(routeKm: Double, toleranceKm: Double = 0.30): Checkpoint? = base.checkpointAt(routeKm, toleranceKm)
    fun hasChargeBetween(fromKm: Double, toKm: Double): Boolean = base.hasChargeBetween(fromKm, toKm)
    fun assistText(routeKm: Double, battery: BatteryEstimate, stats10: ElevationStats): String = base.assistText(routeKm, battery, stats10)

    fun classifyInput(routeKm: Double, percent: Double, forcePostCharge: Boolean = false): ActualEntryKind {
        val cp = base.checkpointAt(routeKm, 0.40)
        if (forcePostCharge && cp?.chargeToPct != null) return ActualEntryKind.POST_CHARGE
        if (cp?.chargeToPct != null) {
            return if (percent >= cp.chargeToPct - 3.0) ActualEntryKind.POST_CHARGE else ActualEntryKind.ARRIVAL
        }
        return ActualEntryKind.RIDING
    }

    private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
}
