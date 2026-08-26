package com.seungjae.jangsu280battery

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Avinox 800Wh 사용자가 현장에서 확인한 충전 특성을 먼저 단순 2구간 곡선으로 사용한다.
 * 0→80% 약 90분, 80→100% 추가 약 60분.
 * 실제 충전 로그가 충분히 쌓이면 이 곡선을 개인화하는 것이 다음 단계다.
 */
object AvinoxChargeCurve {
    private const val FAST_END_SOC = 80.0
    private const val FAST_END_MIN = 90.0
    private const val FULL_MIN = 150.0

    fun cumulativeMinutes(socPct: Double): Double {
        val soc = socPct.coerceIn(0.0, 100.0)
        return if (soc <= FAST_END_SOC) {
            FAST_END_MIN * soc / FAST_END_SOC
        } else {
            FAST_END_MIN + (FULL_MIN - FAST_END_MIN) * (soc - FAST_END_SOC) / (100.0 - FAST_END_SOC)
        }
    }

    fun minutesBetween(fromPct: Double, toPct: Double): Double {
        val from = fromPct.coerceIn(0.0, 100.0)
        val to = toPct.coerceIn(0.0, 100.0)
        if (to <= from) return 0.0
        return (cumulativeMinutes(to) - cumulativeMinutes(from)).coerceAtLeast(0.0)
    }

    fun minutesText(minutes: Double): String {
        if (minutes <= 0.4) return "도달"
        val total = ceil(minutes).toInt().coerceAtLeast(1)
        return if (total < 60) "약 ${total}분" else {
            val h = total / 60
            val m = total % 60
            if (m == 0) "약 ${h}시간" else "약 ${h}시간 ${m}분"
        }
    }

    fun curveLabel(): String = "Avinox 기준 · 0→80% 약 90분 · 80→100% 추가 약 60분"
}

data class ChargePlanAdvice(
    val stationName: String,
    val stationKm: Double,
    val predictedArrivalPct: Double,
    val appRecommendedPct: Double,
    val userTargetPct: Double,
    val nextTargetName: String,
    val nextTargetKm: Double,
    val requiredArrivalPctAtNext: Double,
    val expectedUseToNextPct: Double,
    val consumptionFactor: Double,
    val feasibleAt100: Boolean,
    val shortagePctAt100: Double,
    val minutesArrivalToRecommended: Double,
    val minutesArrivalToUserTarget: Double
) {
    fun recommendationComparisonText(): String = when {
        !feasibleAt100 -> "100%로도 약 ${shortagePctAt100.roundToInt()}% 부족"
        userTargetPct + 0.49 < appRecommendedPct -> "사용자 목표가 권장보다 ${(appRecommendedPct - userTargetPct).roundToInt()}% 낮음"
        userTargetPct > appRecommendedPct + 0.49 -> "사용자가 권장보다 ${(userTargetPct - appRecommendedPct).roundToInt()}% 여유 설정"
        else -> "사용자 목표와 권장값 일치"
    }

    fun compactText(): String = buildString {
        append("앱 권장 ${appRecommendedPct.roundToInt()}% · 사용자 ${userTargetPct.roundToInt()}%")
        append(" · 권장 ${AvinoxChargeCurve.minutesText(minutesArrivalToRecommended)}")
        if (userTargetPct.roundToInt() != appRecommendedPct.roundToInt()) {
            append(" / 사용자 ${AvinoxChargeCurve.minutesText(minutesArrivalToUserTarget)}")
        }
    }
}

/**
 * GPX + 등록 충전지점 + 개인 학습 모델을 바탕으로 충전 목표를 보조한다.
 * 앱 권장값은 어디까지나 추천이며 ChargingStation.chargeToPct(사용자 목표)를 덮어쓰지 않는다.
 * 주행 중에는 AdaptiveBatteryPlan의 현재 소비계수를 사용해 권장값을 다시 계산한다.
 */
class EnergyTripPlanner(
    private val base: BatteryPlan,
    private val adaptive: AdaptiveBatteryPlan? = null
) {
    fun nextChargeAdvice(currentKm: Double, finishTargetPct: Double): ChargePlanAdvice? {
        val cp = base.currentOrNextCheckpoint(currentKm) ?: return null
        if (cp.chargeToPct == null) return null
        val factor = adaptive?.calibration(currentKm)?.factor ?: 1.0
        val arrival = adaptive?.forecast(currentKm, cp.km)?.percent ?: base.estimate(cp.km).percent
        return adviceAtStation(cp.km, finishTargetPct, factor, arrival)
    }

    fun adviceAtStation(
        stationKm: Double,
        finishTargetPct: Double,
        consumptionFactor: Double = 1.0,
        arrivalPctOverride: Double? = null
    ): ChargePlanAdvice? {
        val cp = base.checkpointAt(stationKm, 0.35)?.takeIf { it.chargeToPct != null } ?: return null
        val next = base.checkpoints.firstOrNull { it.km > cp.km + 0.10 } ?: return null
        val factor = consumptionFactor.coerceIn(0.55, 1.80)
        val expectedUse = base.plannedConsumption(cp.km, next.km) * factor
        val requiredArrival = finishTargetPct.coerceIn(1.0, 99.0)
        val rawRecommended = requiredArrival + expectedUse
        val feasible = rawRecommended <= 100.0
        val recommended = rawRecommended.coerceIn(1.0, 100.0)
        val arrival = (arrivalPctOverride ?: base.estimate(cp.km).percent).coerceIn(0.0, 100.0)
        val userTarget = cp.chargeToPct!!.coerceIn(1.0, 100.0)
        return ChargePlanAdvice(
            stationName = cp.name,
            stationKm = cp.km,
            predictedArrivalPct = arrival,
            appRecommendedPct = recommended,
            userTargetPct = userTarget,
            nextTargetName = next.name,
            nextTargetKm = next.km,
            requiredArrivalPctAtNext = requiredArrival,
            expectedUseToNextPct = expectedUse,
            consumptionFactor = factor,
            feasibleAt100 = feasible,
            shortagePctAt100 = (rawRecommended - 100.0).coerceAtLeast(0.0),
            minutesArrivalToRecommended = AvinoxChargeCurve.minutesBetween(arrival, recommended),
            minutesArrivalToUserTarget = AvinoxChargeCurve.minutesBetween(arrival, userTarget)
        )
    }

    fun chargingStatusText(advice: ChargePlanAdvice, currentSocPct: Double): String {
        val current = currentSocPct.coerceIn(0.0, 100.0)
        if (!advice.feasibleAt100) {
            return "⚠ 다음 ${advice.nextTargetName}: 100%로도 ${advice.shortagePctAt100.roundToInt()}% 부족 예상 · 모드 절약/추가 충전지점 검토"
        }
        val appRemain = AvinoxChargeCurve.minutesBetween(current, advice.appRecommendedPct)
        val userRemain = AvinoxChargeCurve.minutesBetween(current, advice.userTargetPct)
        return when {
            current + 0.49 >= advice.userTargetPct && advice.userTargetPct + 0.49 < advice.appRecommendedPct ->
                "⚠ 사용자 목표 ${advice.userTargetPct.roundToInt()}% 도달 · 앱 권장 ${advice.appRecommendedPct.roundToInt()}%까지 ${AvinoxChargeCurve.minutesText(appRemain)} · 출발은 사용자 판단"
            current + 0.49 >= advice.userTargetPct ->
                "✓ 사용자 목표 ${advice.userTargetPct.roundToInt()}% 도달 · 출발 가능"
            current + 0.49 >= advice.appRecommendedPct ->
                "✓ 앱 권장 ${advice.appRecommendedPct.roundToInt()}% 도달 · 사용자 목표 ${advice.userTargetPct.roundToInt()}%까지 ${AvinoxChargeCurve.minutesText(userRemain)}"
            else ->
                "충전 ${current.roundToInt()}% · 앱 권장 ${advice.appRecommendedPct.roundToInt()}%까지 ${AvinoxChargeCurve.minutesText(appRemain)} · 사용자 ${advice.userTargetPct.roundToInt()}%까지 ${AvinoxChargeCurve.minutesText(userRemain)}"
        }
    }

    fun factorText(currentKm: Double): String {
        val factor = adaptive?.calibration(currentKm)?.factor ?: 1.0
        return String.format(Locale.US, "%.2fx", factor)
    }
}
