package com.seungjae.jangsu280battery

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan

/** FTP/Wkg를 GPX 경사에 적용해 목표 순수 주행시간을 역산하는 ROAD 물리 기반 알파 모델. */
data class RoadPowerPaceEstimate(
    val ridingTargetSec: Double,
    val averageKph: Double,
    val ftpW: Double,
    val weightKg: Double,
    val wattsPerKg: Double,
    val enduranceFraction: Double,
    val modelLabel: String
)

object RoadPowerPaceEstimator {
    private const val BIKE_KG = 8.5
    private const val C_RR = 0.0045
    private const val CDA = 0.32
    private const val AIR_RHO = 1.225
    private const val G = 9.80665
    private const val DRIVETRAIN = 0.97

    fun estimate(
        course: CourseData,
        riderWeightKg: Double,
        ftpW: Double,
        curve: StravaPowerCurve? = null
    ): RoadPowerPaceEstimate {
        require(course.totalKm > 0.1) { "유효한 GPX 코스가 필요합니다." }
        require(riderWeightKg in 30.0..200.0) { "체중을 확인해 주세요." }
        require(ftpW in 50.0..600.0) { "FTP를 확인해 주세요." }
        val endurance = StravaPerformanceEstimator.enduranceFraction(curve, ftpW)
        val mass = riderWeightKg + BIKE_KG
        val stepKm = 0.20
        var km = 0.0
        var totalSec = 0.0
        while (km < course.totalKm - 1e-6) {
            val next = (km + stepKm).coerceAtMost(course.totalKm)
            val distKm = next - km
            val a = course.pointAtKm(km)
            val b = course.pointAtKm(next)
            val gradePct = if (course.hasElevation && distKm > 0.0001) {
                ((b.ele - a.ele) / (distKm * 1000.0) * 100.0).coerceIn(-20.0, 24.0)
            } else 0.0
            val pct = terrainPowerFraction(gradePct, endurance)
            val targetPower = ftpW * pct
            val speedMps = solveSpeedMps(targetPower, mass, gradePct)
            val maxKph = when {
                gradePct <= -7.0 -> 75.0
                gradePct <= -3.0 -> 68.0
                gradePct < 1.0 -> 52.0
                gradePct < 4.0 -> 40.0
                else -> 30.0
            }
            val speedKph = (speedMps * 3.6).coerceIn(4.0, maxKph)
            totalSec += distKm / speedKph * 3600.0
            km = next
        }
        val avg = course.totalKm / (totalSec / 3600.0)
        return RoadPowerPaceEstimate(
            ridingTargetSec = totalSec.coerceAtLeast(600.0),
            averageKph = avg,
            ftpW = ftpW,
            weightKg = riderWeightKg,
            wattsPerKg = ftpW / riderWeightKg,
            enduranceFraction = endurance,
            modelLabel = "FTP/Wkg + GPX 경사 물리 추정"
        )
    }

    private fun terrainPowerFraction(gradePct: Double, endurance: Double): Double = when {
        gradePct <= -6.0 -> 0.12
        gradePct <= -2.0 -> (endurance - 0.28).coerceAtLeast(0.25)
        gradePct < 1.0 -> endurance
        gradePct < 3.0 -> (endurance + 0.04).coerceAtMost(0.86)
        gradePct < 6.0 -> (endurance + 0.10).coerceAtMost(0.90)
        else -> (endurance + 0.15).coerceAtMost(0.94)
    }

    /** 일정 파워에서 정상상태 속도를 이분법으로 풉니다. 내리막에서는 중력 항도 포함합니다. */
    private fun solveSpeedMps(powerW: Double, massKg: Double, gradePct: Double): Double {
        val theta = atan(gradePct / 100.0)
        fun required(v: Double): Double {
            val gravity = massKg * G * sin(theta)
            val rolling = C_RR * massKg * G * cos(theta)
            val aero = 0.5 * AIR_RHO * CDA * v * v
            return ((gravity + rolling + aero) * v / DRIVETRAIN)
        }
        var lo = 0.3
        var hi = 25.0
        // 아주 급한 내리막은 목표 파워보다 중력 이득이 커도 공기저항이 커지는 지점까지 탐색.
        repeat(70) {
            val mid = (lo + hi) / 2.0
            if (required(mid) < powerW) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }
}
