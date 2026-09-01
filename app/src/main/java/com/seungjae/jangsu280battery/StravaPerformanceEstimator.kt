package com.seungjae.jangsu280battery

import android.content.Context
import kotlin.math.max

/** Strava 현재 프로필 + 선택 연도 PR을 페이스 계획용 라이더 능력값으로 정리한다. */
data class StravaPerformanceSnapshot(
    val year: Int,
    val weightKg: Double?,
    val currentProfileFtpW: Double?,
    val yearEstimatedFtpW: Double?,
    val effectiveFtpW: Double?,
    val wattsPerKg: Double?,
    val powerCurve: StravaPowerCurve,
    val sourceLabel: String
)

object StravaPerformanceEstimator {
    /**
     * 선택 연도 PR에서 FTP를 보수적으로 추정한다.
     * 20분 95%, 40분 98%, 60분 100% 후보 중 가장 높은 값을 사용한다.
     * 이 값은 Strava 프로필에 저장된 FTP와 구분해 '연도 추정 FTP'로 표시한다.
     */
    fun estimateYearFtp(curve: StravaPowerCurve): Double? {
        val candidates = listOfNotNull(
            curve.p20m?.times(0.95),
            curve.p40m?.times(0.98),
            curve.p1h
        ).filter { it in 50.0..600.0 }
        return candidates.maxOrNull()
    }

    fun snapshot(context: Context, yearOverride: Int? = null): StravaPerformanceSnapshot? {
        val active = StravaReviewStore(context).loadActive() ?: return null
        val secure = StravaSecureStore(context)
        val year = yearOverride?.takeIf { it in active.availableYears } ?: active.resolvedYear()
        val curve = active.yearPower(year)
        val yearFtp = estimateYearFtp(curve)
        val currentFtp = secure.athleteFtpW()
        // 사용자가 '기준년도'를 고른 경우 그 연도의 실제 PR 추정치를 우선한다.
        val effective = yearFtp ?: currentFtp
        val weight = secure.athleteWeightKg()?.takeIf { it in 30.0..200.0 }
            ?: RiderServerSync(context).weightKg().takeIf { it in 30.0..200.0 }
        val wkg = if (effective != null && weight != null && weight > 0.0) effective / weight else null
        val label = when {
            yearFtp != null -> "Strava ${year}년 PR 추정 FTP"
            currentFtp != null -> "Strava 현재 프로필 FTP"
            else -> "Strava ${year}년 파워 프로필"
        }
        return StravaPerformanceSnapshot(year, weight, currentFtp, yearFtp, effective, wkg, curve, label)
    }

    fun enduranceFraction(curve: StravaPowerCurve?, ftpW: Double): Double {
        if (ftpW <= 0.0) return 0.68
        val candidates = mutableListOf<Double>()
        curve?.p4h?.let { candidates += it / ftpW }
        curve?.p2h?.let { candidates += (it / ftpW) * 0.93 }
        curve?.p1h?.let { candidates += (it / ftpW) * 0.82 }
        curve?.p40m?.let { candidates += (it / ftpW) * 0.78 }
        val best = candidates.filter { it.isFinite() && it in 0.40..1.10 }.maxOrNull() ?: 0.68
        return max(0.58, best.coerceIn(0.58, 0.82))
    }
}
