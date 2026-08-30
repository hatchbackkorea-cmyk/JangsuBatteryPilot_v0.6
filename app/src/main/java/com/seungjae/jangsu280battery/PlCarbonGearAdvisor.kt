package com.seungjae.jangsu280battery

import kotlin.math.abs

/**
 * Amflow PL Carbon Pro 순정 구동계 기준의 권장 기어 계산기.
 *
 * - 앞 체인링: 34T
 * - SRAM XS-1295 12단: 1단 52T ... 12단 10T
 * - 타이어: 29 x 2.4, 25psi 사용을 감안한 실용 유효 둘레 2.33m
 *
 * 주의: 현재 SRAM 실제 단수를 BLE에서 읽는 기능이 아니다.
 * 현재 속도와 목표 케이던스로 '어느 단을 쓰면 목표 케이던스에 가장 가까운지' 계산한다.
 */
object PlCarbonGearAdvisor {
    private const val FRONT_TEETH = 34.0
    private const val WHEEL_CIRCUMFERENCE_M = 2.33

    // 사용자가 보는 단수 기준: 1단=가장 가벼움(52T), 12단=가장 무거움(10T)
    private val REAR_TEETH_BY_GEAR = intArrayOf(52, 44, 38, 32, 28, 24, 21, 18, 16, 14, 12, 10)

    fun recommendedGear(speedKph: Double, cadence: IntRangeTarget?): Int? {
        if (cadence == null || speedKph < 3.5) return null
        val wheelRpm = (speedKph * 1000.0 / 60.0) / WHEEL_CIRCUMFERENCE_M
        if (wheelRpm <= 1.0) return null
        val targetMid = (cadence.low + cadence.high) / 2.0

        var bestGear = 1
        var bestScore = Double.POSITIVE_INFINITY
        REAR_TEETH_BY_GEAR.forEachIndexed { index, rearTeeth ->
            val predictedCadence = wheelRpm * rearTeeth / FRONT_TEETH
            // 목표 범위 안에 들어오면 중앙값과의 차이만, 범위 밖이면 이탈량에 큰 가중치를 준다.
            val outside = when {
                predictedCadence < cadence.low -> cadence.low - predictedCadence
                predictedCadence > cadence.high -> predictedCadence - cadence.high
                else -> 0.0
            }
            val score = outside * 4.0 + abs(predictedCadence - targetMid)
            if (score < bestScore) {
                bestScore = score
                bestGear = index + 1
            }
        }
        return bestGear
    }

    fun compactAdvice(speedKph: Double, cadence: IntRangeTarget?, fallback: String?): String {
        val gear = recommendedGear(speedKph, cadence)
        return when {
            gear != null -> "권장기어 ${gear}단"
            fallback?.contains("페달 최소") == true -> "페달 최소"
            else -> "권장기어 —"
        }
    }
}
