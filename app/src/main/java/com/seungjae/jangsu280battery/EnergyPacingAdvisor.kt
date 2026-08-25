package com.seungjae.jangsu280battery

import kotlin.math.max
import kotlin.math.roundToInt

enum class PacingTerrain(val label: String) {
    FLAT("평지/완만"),
    ROLLING("구릉"),
    CLIMB("업힐"),
    STEEP_CLIMB("급경사"),
    DOWNHILL("다운힐")
}

data class IntRangeTarget(val low: Int, val high: Int) {
    fun text(unit: String): String = if (low == high) "$low$unit" else "$low~$high$unit"
}

data class PacingAdvice(
    val terrain: PacingTerrain,
    val terrainGradePct: Double,
    val title: String,
    val displayText: String,
    val voiceText: String,
    val speedKph: IntRangeTarget?,
    val motorPowerW: IntRangeTarget?,
    val cadenceRpm: IntRangeTarget?,
    val learnedSampleCount: Int,
    val confidence: Int,
    val learned: Boolean,
    val speedAction: String? = null
)

/**
 * 과거 FIT에서 학습된 "그 지형에서 내가 실제로 썼던" 속도/모터파워/케이던스를
 * 선택 GPX의 앞으로 1km 지형에 매칭해 목표 범위로 제시한다.
 *
 * 주행 중 Avinox 파워/케이던스는 실시간 수신하지 못하므로 이 값들은 '목표값'일 뿐
 * 현재값이라고 표현하지 않는다. 실시간 피드백은 휴대폰 GPS 속도와 배터리 여유만 사용한다.
 */
class EnergyPacingAdvisor(
    private val course: CourseData,
    private val learning: BatteryLearningStore
) {
    fun advice(routeKm: Double, currentSpeedKph: Double, reserve: ReserveStatus): PacingAdvice {
        val km = routeKm.coerceIn(0.0, course.totalKm)
        if (!course.hasElevation) {
            return PacingAdvice(
                terrain = PacingTerrain.FLAT,
                terrainGradePct = 0.0,
                title = "에너지 페이스 · 고도 없음",
                displayText = "GPX 고도 데이터가 없어 지형별 목표 제한\n배터리·거리 어시스트만 사용",
                voiceText = "이 GPX에는 고도 데이터가 없어 지형별 모터 출력과 케이던스 목표는 제시하지 않습니다.",
                speedKph = null,
                motorPowerW = null,
                cadenceRpm = null,
                learnedSampleCount = 0,
                confidence = 0,
                learned = false
            )
        }
        val remaining = (course.totalKm - km).coerceAtLeast(0.0)
        val span = remaining.coerceAtMost(1.0).coerceAtLeast(0.25)
        val end = (km + span).coerceAtMost(course.totalKm)
        val stats = course.elevationBetween(km, end)
        val net = if (course.hasElevation && end > km + 0.02) course.pointAtKm(end).ele - course.pointAtKm(km).ele else 0.0
        val grade = if (end > km + 0.02) net / ((end - km) * 1000.0) * 100.0 else 0.0
        val ascentPerKm = if (end > km + 0.02) stats.ascentM / (end - km) else 0.0
        val descentPerKm = if (end > km + 0.02) stats.descentM / (end - km) else 0.0
        val terrain = classify(grade, ascentPerKm, descentPerKm)

        if (terrain == PacingTerrain.DOWNHILL) {
            val display = "무동력 우선 · 모터 0W 목표\n속도는 노면·시야·노면상태 기준"
            return PacingAdvice(
                terrain = terrain,
                terrainGradePct = grade,
                title = "에너지 페이스 · 다운힐",
                displayText = display,
                voiceText = "다운힐 구간입니다. 모터 보조를 최소화하고 안전 속도를 우선하세요.",
                speedKph = null,
                motorPowerW = IntRangeTarget(0, 0),
                cadenceRpm = null,
                learnedSampleCount = 0,
                confidence = 100,
                learned = false
            )
        }

        val bucket = when (terrain) {
            PacingTerrain.FLAT -> TerrainBucket.FLAT
            PacingTerrain.ROLLING -> TerrainBucket.ROLLING
            PacingTerrain.CLIMB, PacingTerrain.STEEP_CLIMB -> TerrainBucket.CLIMB
            PacingTerrain.DOWNHILL -> TerrainBucket.FLAT
        }
        val profile = learning.assistProfile(bucket)
        if (profile == null || profile.sampleCount < 1) {
            val fallback = when (terrain) {
                PacingTerrain.STEEP_CLIMB -> "급경사 · 초반 과출력 주의"
                PacingTerrain.CLIMB -> "업힐 · 일정한 페이스 유지"
                PacingTerrain.ROLLING -> "구릉 · 가속/감속 반복 줄이기"
                else -> "완만한 구간 · 불필요한 가속 줄이기"
            }
            return PacingAdvice(
                terrain = terrain,
                terrainGradePct = grade,
                title = "에너지 페이스 · ${terrain.label}",
                displayText = "$fallback\nFIT 학습 후 모터·rpm·속도 목표 생성",
                voiceText = "$fallback. 아직 이 지형의 FIT 학습 데이터가 부족합니다.",
                speedKph = null,
                motorPowerW = null,
                cadenceRpm = null,
                learnedSampleCount = 0,
                confidence = 0,
                learned = false
            )
        }

        val reserveFactor = when {
            reserve.differencePct <= -6.0 -> 0.78
            reserve.differencePct < -2.0 -> 0.86
            reserve.differencePct < 2.0 -> 0.93
            reserve.differencePct >= 9.0 -> 1.04
            else -> 1.0
        }
        val steepMotorFactor = if (terrain == PacingTerrain.STEEP_CLIMB) 1.10 else 1.0
        val steepSpeedFactor = if (terrain == PacingTerrain.STEEP_CLIMB) 0.82 else 1.0

        val uncertainty = when {
            profile.sampleCount <= 1 -> 1.65
            profile.sampleCount <= 3 -> 1.35
            profile.quality < 70 -> 1.25
            else -> 1.0
        }
        val motor = profile.avgMotorPowerW?.let { center ->
            val adjusted = center * reserveFactor * steepMotorFactor
            rangeAround(adjusted, max(25.0, adjusted * 0.18) * uncertainty, 0, 1000, 10)
        }
        val cadence = profile.avgCadenceRpm?.let { center ->
            val adjusted = if (terrain == PacingTerrain.STEEP_CLIMB) max(center, 72.0) else center
            rangeAround(adjusted, 6.0 * uncertainty, 55, 115, 1)
        }
        val speed = profile.avgSpeedKph?.let { center ->
            val adjusted = center * reserveFactor.coerceIn(0.84, 1.03) * steepSpeedFactor
            val baseHalf = if (terrain == PacingTerrain.CLIMB || terrain == PacingTerrain.STEEP_CLIMB) 1.5 else 2.0
            rangeAround(adjusted, baseHalf * uncertainty, 5, 45, 1)
        }

        val speedAction = when {
            speed != null && currentSpeedKph > speed.high + 2.0 && reserve.differencePct < 2.0 -> {
                val drop = (currentSpeedKph - speed.high).roundToInt().coerceIn(1, 8)
                "현재 속도에서 약 ${drop}km/h 낮추면 절약에 유리"
            }
            else -> null
        }

        val lines = mutableListOf<String>()
        val targets = mutableListOf<String>()
        motor?.let { targets += "목표 모터 ${it.text("W")}" }
        cadence?.let { targets += "목표 ${it.text("rpm")}" }
        if (targets.isNotEmpty()) lines += targets.joinToString(" · ")
        val confidenceLabel = when {
            profile.sampleCount >= 5 && profile.quality >= 80 -> "높음"
            profile.sampleCount >= 2 && profile.quality >= 60 -> "보통"
            else -> "낮음"
        }
        speed?.let { lines += "목표 속도 ${it.text("km/h")} · 학습 ${profile.sampleCount}구간 · 신뢰 $confidenceLabel" }
        if (speed == null) lines += "학습 ${profile.sampleCount}구간 · 신뢰 $confidenceLabel"
        speedAction?.let { lines += it }
        if (lines.isEmpty()) lines += "FIT 학습값 일부 부족 · 배터리 예측 위주"

        val voiceParts = mutableListOf<String>()
        voiceParts += "${terrain.label} 구간입니다."
        motor?.let { voiceParts += "목표 모터 출력 ${it.low}에서 ${it.high}와트." }
        cadence?.let { voiceParts += "케이던스 ${it.low}에서 ${it.high}알피엠." }
        speed?.let { voiceParts += "속도 ${it.low}에서 ${it.high}킬로미터를 참고하세요." }
        speedAction?.let { voiceParts += it + "." }

        return PacingAdvice(
            terrain = terrain,
            terrainGradePct = grade,
            title = "에너지 페이스 · ${terrain.label}",
            displayText = lines.joinToString("\n"),
            voiceText = voiceParts.joinToString(" "),
            speedKph = speed,
            motorPowerW = motor,
            cadenceRpm = cadence,
            learnedSampleCount = profile.sampleCount,
            confidence = profile.quality,
            learned = true,
            speedAction = speedAction
        )
    }

    fun adviceForKm(targetKm: Double, reserve: ReserveStatus): PacingAdvice =
        advice(targetKm.coerceIn(0.0, course.totalKm), 0.0, reserve)

    private fun classify(gradePct: Double, ascentPerKm: Double, descentPerKm: Double): PacingTerrain = when {
        gradePct <= -3.0 && descentPerKm >= 30.0 && descentPerKm > ascentPerKm * 1.8 -> PacingTerrain.DOWNHILL
        gradePct >= 9.0 || ascentPerKm >= 95.0 -> PacingTerrain.STEEP_CLIMB
        gradePct >= 4.0 || ascentPerKm >= 45.0 -> PacingTerrain.CLIMB
        ascentPerKm >= 15.0 -> PacingTerrain.ROLLING
        else -> PacingTerrain.FLAT
    }

    private fun rangeAround(center: Double, halfWidth: Double, min: Int, max: Int, step: Int): IntRangeTarget {
        fun snap(v: Double): Int = ((v / step).roundToInt() * step).coerceIn(min, max)
        var low = snap(center - halfWidth)
        var high = snap(center + halfWidth)
        if (high < low) high = low
        if (high == low && high + step <= max) high += step
        return IntRangeTarget(low, high)
    }
}
