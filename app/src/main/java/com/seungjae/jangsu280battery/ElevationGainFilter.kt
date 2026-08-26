package com.seungjae.jangsu280battery

import android.location.Location

/**
 * 휴대폰 GPS 고도의 ±수 m 흔들림을 그대로 누적 상승으로 더하지 않기 위한 보수적 필터.
 * - 최근 21개 고도의 중앙값을 사용해 순간 튐을 제거
 * - 기준 고도에서 4m 이상 실제로 벗어났을 때만 상승/하강 추세를 확정
 * - 수평 정확도 35m 초과, 수직 정확도 20m 초과 샘플은 상승 계산에서 제외
 *
 * 원본 GPS 고도 자체는 track.csv에 그대로 남긴다. 이 클래스는 '누적 상승 표시값'만 안정화한다.
 */
class ElevationGainFilter(
    private val windowSize: Int = 21,
    private val hysteresisM: Double = 4.0,
    initialAscentM: Double = 0.0
) {
    private val elevations = ArrayDeque<Double>()
    private var referenceElevationM: Double? = null
    var ascentM: Double = initialAscentM.coerceAtLeast(0.0)
        private set

    fun reset(initialAscentM: Double = 0.0) {
        elevations.clear()
        referenceElevationM = null
        ascentM = initialAscentM.coerceAtLeast(0.0)
    }

    fun update(elevationM: Double): Double {
        if (!elevationM.isFinite()) return ascentM
        elevations.addLast(elevationM)
        while (elevations.size > windowSize.coerceAtLeast(5)) elevations.removeFirst()
        if (elevations.size < 5) return ascentM

        val sorted = elevations.sorted()
        val smoothed = sorted[sorted.size / 2]
        val ref = referenceElevationM
        if (ref == null) {
            referenceElevationM = smoothed
            return ascentM
        }

        when {
            smoothed >= ref + hysteresisM -> {
                ascentM += smoothed - ref
                referenceElevationM = smoothed
            }
            smoothed <= ref - hysteresisM -> {
                referenceElevationM = smoothed
            }
        }
        return ascentM
    }
}

class GpsAscentEstimator(initialAscentM: Double = 0.0) {
    private val filter = ElevationGainFilter(initialAscentM = initialAscentM)

    val ascentM: Double get() = filter.ascentM

    fun reset(initialAscentM: Double = 0.0) = filter.reset(initialAscentM)

    fun update(location: Location): Double {
        if (!location.hasAltitude()) return filter.ascentM
        if (location.hasAccuracy() && location.accuracy > 35f) return filter.ascentM
        // minSdk 26이므로 Location 수직 정확도 API 사용 가능.
        if (location.hasVerticalAccuracy() && location.verticalAccuracyMeters > 20f) return filter.ascentM
        return filter.update(location.altitude)
    }
}
