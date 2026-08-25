package com.seungjae.jangsu280battery

import kotlin.math.abs

data class EnergyComparisonSnapshot(
    val actualConsumedPct: Double?,
    val chargedAddedPct: Double,
    val modelConsumedPct: Double,
    val modelProjectedTotalPct: Double,
    val avinoxMode: AvinoxRideMode?,
    val avinoxConsumedPct: Double?,
    val avinoxProjectedTotalPct: Double?,
    val modelFactor: Double
)

/**
 * v0.14.0 비교 전용 계산기.
 * - Avinox는 자체 모델/학습에 절대 주입하지 않는다.
 * - 충전으로 잔량이 다시 올라가도 누적 소비량으로 비교가 계속 이어진다.
 * - 실제 누적 소비량 = 첫 실제 SOC + 누적 충전량 - 최신 실제 SOC.
 */
object EnergyComparisonCalculator {
    fun snapshot(
        routeKm: Double,
        course: CourseData,
        base: BatteryPlan,
        adaptive: AdaptiveBatteryPlan,
        actualEntries: List<ActualBatteryEntry>,
        avinoxReference: AvinoxCourseReference?
    ): EnergyComparisonSnapshot {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        val entries = actualEntries.sortedBy { it.timestampMs }
        val chargeAdded = totalChargeAdded(entries)
        val firstActual = entries.firstOrNull()
        val latestActual = entries.lastOrNull()
        val actualConsumed = if (firstActual != null && latestActual != null) {
            (firstActual.percent + chargeAdded - latestActual.percent).coerceAtLeast(0.0)
        } else null

        val factor = adaptive.calibration(km)?.factor ?: 1.0
        val modelBaseToNow = base.cumulativeInternalUsePct(km)
        val modelConsumed = (modelBaseToNow * factor).coerceAtLeast(0.0)
        val remaining = base.internalConsumption(km, course.totalKm)
        val projectedTotal = if (actualConsumed != null) {
            (actualConsumed + remaining * factor).coerceAtLeast(actualConsumed)
        } else {
            base.predictedTotalUsePct().coerceAtLeast(0.0)
        }

        val mode = avinoxReference?.selectedMode?.takeIf { avinoxReference.value(it) != null }
        val avinoxTotal = mode?.let { avinoxReference.value(it) }
        val internalTotal = base.internalTotalUsePct()
        val progressWeight = when {
            km <= 0.0 -> 0.0
            km >= course.totalKm -> 1.0
            internalTotal > 0.05 -> (modelBaseToNow / internalTotal).coerceIn(0.0, 1.0)
            course.totalKm > 0.05 -> (km / course.totalKm).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val avinoxConsumed = avinoxTotal?.let { (it * progressWeight).coerceAtLeast(0.0) }

        return EnergyComparisonSnapshot(
            actualConsumedPct = actualConsumed,
            chargedAddedPct = chargeAdded,
            modelConsumedPct = modelConsumed,
            modelProjectedTotalPct = projectedTotal,
            avinoxMode = mode,
            avinoxConsumedPct = avinoxConsumed,
            avinoxProjectedTotalPct = avinoxTotal,
            modelFactor = factor
        )
    }

    private fun totalChargeAdded(entries: List<ActualBatteryEntry>): Double {
        var total = 0.0
        var arrival: ActualBatteryEntry? = null
        entries.forEach { e ->
            when (e.kind) {
                ActualEntryKind.ARRIVAL -> arrival = e
                ActualEntryKind.POST_CHARGE -> {
                    val a = arrival
                    if (a != null && abs(a.routeKm - e.routeKm) <= 0.60) {
                        total += (e.percent - a.percent).coerceAtLeast(0.0)
                    }
                    arrival = null
                }
                ActualEntryKind.RIDING -> Unit
            }
        }
        return total.coerceAtLeast(0.0)
    }
}
