package com.seungjae.jangsu280battery

import kotlin.math.max
import kotlin.math.pow
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
    val riderPowerW: IntRangeTarget?,
    val motorPowerW: IntRangeTarget?,
    val cadenceRpm: IntRangeTarget?,
    val gearAdvice: String?,
    val learnedSampleCount: Int,
    val confidence: Int,
    val learned: Boolean,
    val speedAction: String? = null
)

/**
 * 과거 FIT에서 학습된 "그 지형에서 내가 실제로 썼던" 속도/모터파워/케이던스를
 * 선택 GPX의 앞으로 1km 지형에 매칭하고, SOC 여유와 실시간 소비 보정을 적용해
 * 권장속도 → 파워분담 → 케이던스 → 기어 순서로 목표 범위를 제시한다.
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
                riderPowerW = null,
                motorPowerW = null,
                cadenceRpm = null,
                gearAdvice = null,
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
                riderPowerW = null,
                motorPowerW = IntRangeTarget(0, 0),
                cadenceRpm = null,
                gearAdvice = "페달 최소 · 안전 우선",
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
                PacingTerrain.CLIMB -> "업힐 · 일정한 페이스"
                PacingTerrain.ROLLING -> "구릉 · 가속/감속 반복 줄이기"
                else -> "완만한 구간 · 불필요한 가속 줄이기"
            }
            val gear = gearAdviceFor(terrain, grade)
            return PacingAdvice(
                terrain = terrain,
                terrainGradePct = grade,
                title = "에너지 페이스 · ${terrain.label}",
                displayText = "$fallback\nRIDER·MOTOR·CAD 학습중 · GEAR $gear",
                voiceText = "$fallback. 기어는 $gear. 라이더 파워와 모터 파워, 케이던스 목표는 학습 데이터가 더 필요합니다.",
                speedKph = null,
                riderPowerW = null,
                motorPowerW = null,
                cadenceRpm = null,
                gearAdvice = gear,
                learnedSampleCount = 0,
                confidence = 0,
                learned = false
            )
        }

        // v0.28.2: SOC 관리 순서
        // SOC 여유/실시간 소비보정 → 권장 속도 → 라이더/모터 분담 → 권장 케이던스 → 권장 기어
        // 배터리 예측 엔진 자체는 건드리지 않고, 이미 계산된 reserve 값을 주행 코치의 목표값에만 반영한다.
        val socSpeedFactor = when {
            reserve.differencePct <= -6.0 -> 0.78
            reserve.differencePct < -2.0 -> 0.86
            reserve.differencePct < 2.0 -> 0.93
            reserve.differencePct >= 9.0 -> 1.03
            else -> 1.0
        }
        val consumptionSpeedFactor = when {
            reserve.consumptionFactor >= 1.30 -> 0.94
            reserve.consumptionFactor >= 1.15 -> 0.97
            reserve.consumptionFactor <= 0.85 && reserve.differencePct >= 4.0 -> 1.02
            else -> 1.0
        }
        val terrainSpeedFactor = if (terrain == PacingTerrain.STEEP_CLIMB) 0.84 else 1.0

        val uncertainty = when {
            profile.sampleCount <= 1 -> 1.65
            profile.sampleCount <= 3 -> 1.35
            profile.quality < 70 -> 1.25
            else -> 1.0
        }

        // 1) 먼저 SOC를 만족시키는 속도 범위를 정한다.
        val speed = profile.avgSpeedKph?.let { center ->
            val combinedFactor = (socSpeedFactor * consumptionSpeedFactor * terrainSpeedFactor).coerceIn(0.72, 1.05)
            val adjusted = center * combinedFactor
            val baseHalf = if (terrain == PacingTerrain.CLIMB || terrain == PacingTerrain.STEEP_CLIMB) 1.5 else 2.0
            rangeAround(adjusted, baseHalf * uncertainty, 5, 45, 1)
        }
        val targetSpeedMid = speed?.let { (it.low + it.high) / 2.0 }
        val learnedSpeed = profile.avgSpeedKph?.takeIf { it > 1.0 }
        val targetVsLearnedSpeed = if (targetSpeedMid != null && learnedSpeed != null) {
            (targetSpeedMid / learnedSpeed).coerceIn(0.72, 1.08)
        } else 1.0

        // 2) 속도를 정한 뒤 라이더가 조금 더 분담하고, 모터는 목표 속도에 맞춰 예산을 조절한다.
        val riderReserveFactor = when {
            reserve.differencePct <= -6.0 -> 1.12
            reserve.differencePct < -2.0 -> 1.08
            reserve.differencePct < 2.0 -> 1.04
            reserve.differencePct >= 9.0 -> 0.98
            else -> 1.0
        }
        val riderTerrainFactor = if (terrain == PacingTerrain.STEEP_CLIMB) 1.05 else 1.0
        val rider = profile.avgRiderPowerW?.let { center ->
            val adjusted = center * riderReserveFactor * riderTerrainFactor
            rangeAround(adjusted, max(12.0, adjusted * 0.12) * uncertainty, 50, 500, 5)
        }

        val motorBudgetFactor = when {
            reserve.differencePct <= -6.0 -> 0.92
            reserve.differencePct < -2.0 -> 0.96
            reserve.differencePct < 2.0 -> 0.98
            reserve.differencePct >= 9.0 -> 1.02
            else -> 1.0
        }
        val motorSpeedExponent = when (terrain) {
            PacingTerrain.FLAT -> 1.7
            PacingTerrain.ROLLING -> 1.35
            PacingTerrain.CLIMB, PacingTerrain.STEEP_CLIMB -> 1.0
            PacingTerrain.DOWNHILL -> 1.0
        }
        val motorSpeedFactor = targetVsLearnedSpeed.pow(motorSpeedExponent).coerceIn(0.62, 1.12)
        val steepMotorFactor = if (terrain == PacingTerrain.STEEP_CLIMB) 1.06 else 1.0
        val motor = profile.avgMotorPowerW?.let { center ->
            val adjusted = center * motorBudgetFactor * motorSpeedFactor * steepMotorFactor
            rangeAround(adjusted, max(25.0, adjusted * 0.18) * uncertainty, 0, 1000, 10)
        }

        // 3) SOC가 빠듯하거나 경사가 커지면 저회전 고토크로 버티지 않도록 학습 RPM을 약간 위로 이동한다.
        val cadenceSocBoost = when {
            reserve.differencePct <= -6.0 -> 4.0
            reserve.differencePct < -2.0 -> 3.0
            reserve.differencePct < 2.0 -> 1.0
            else -> 0.0
        }
        val cadenceTerrainFloor = when (terrain) {
            PacingTerrain.STEEP_CLIMB -> 74.0
            PacingTerrain.CLIMB -> 70.0
            else -> 0.0
        }
        val cadence = profile.avgCadenceRpm?.let { center ->
            val adjusted = max(center + cadenceSocBoost, cadenceTerrainFloor).coerceAtMost(92.0)
            rangeAround(adjusted, 6.0 * uncertainty, 55, 115, 1)
        }

        // 4) 현재 속도가 목표 범위 밖이면 '목표 속도에서 탈 기어'를 제시한다.
        // 이렇게 해야 속도를 줄이라는 조언과 무거운 기어 권장이 서로 충돌하지 않는다.
        val gearReferenceSpeed = speed?.let {
            currentSpeedKph.coerceIn(it.low.toDouble(), it.high.toDouble())
        } ?: currentSpeedKph
        val gearAdvice = PlCarbonGearAdvisor.compactAdvice(gearReferenceSpeed, cadence, gearAdviceFor(terrain, grade))

        val speedAction = when {
            speed != null && currentSpeedKph > speed.high + 2.0 && reserve.differencePct < 2.0 -> {
                val drop = (currentSpeedKph - speed.high).roundToInt().coerceIn(1, 8)
                "현재 속도에서 약 ${drop}km/h 낮추면 절약에 유리"
            }
            else -> null
        }

        val lines = mutableListOf<String>()
        val targets = mutableListOf<String>()
        rider?.let { targets += "RIDER ${it.text("W")}" }
        motor?.let { targets += "MOTOR ${it.text("W")}" }
        cadence?.let { targets += "CAD ${it.text("rpm")}" }
        if (targets.isNotEmpty()) lines += targets.joinToString(" · ")
        lines += "GEAR $gearAdvice"
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
        rider?.let { voiceParts += "라이더 파워 ${it.low}에서 ${it.high}와트." }
        motor?.let { voiceParts += "목표 모터 출력 ${it.low}에서 ${it.high}와트." }
        cadence?.let { voiceParts += "케이던스 ${it.low}에서 ${it.high}알피엠." }
        voiceParts += "기어는 $gearAdvice."
        speed?.let { voiceParts += "속도 ${it.low}에서 ${it.high}킬로미터를 참고하세요." }
        speedAction?.let { voiceParts += it + "." }

        return PacingAdvice(
            terrain = terrain,
            terrainGradePct = grade,
            title = "에너지 페이스 · ${terrain.label}",
            displayText = lines.joinToString("\n"),
            voiceText = voiceParts.joinToString(" "),
            speedKph = speed,
            riderPowerW = rider,
            motorPowerW = motor,
            cadenceRpm = cadence,
            gearAdvice = gearAdvice,
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

    /**
     * SRAM 실제 현재 단수는 아직 riding BLE에서 해독하지 못했다.
     * 학습 케이던스가 없을 때만 쓰는 짧은 fallback 문구다.
     * 학습 케이던스가 있으면 PlCarbonGearAdvisor가 실제 단수 형태의 권장값을 계산한다.
     */
    private fun gearAdviceFor(terrain: PacingTerrain, gradePct: Double): String = when {
        terrain == PacingTerrain.DOWNHILL -> "페달 최소"
        terrain == PacingTerrain.STEEP_CLIMB || gradePct >= 9.0 -> "저단 권장"
        terrain == PacingTerrain.CLIMB || gradePct >= 4.0 -> "저단 권장"
        terrain == PacingTerrain.ROLLING -> "권장기어 —"
        gradePct <= -1.5 -> "고단 권장"
        else -> "권장기어 —"
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
