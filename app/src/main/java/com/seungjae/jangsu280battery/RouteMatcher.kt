package com.seungjae.jangsu280battery

import kotlin.math.max
import kotlin.math.min


data class MatchResult(
    val index: Int,
    val routeKm: Double,
    val courseElevationM: Double,
    val offCourseMeters: Double
)

/**
 * GPX의 50km/75km 지점처럼 같은 물리 위치를 다시 통과하는 코스를 위해
 * '가장 가까운 좌표'만 보지 않고 직전 진행도 주변의 전방 창(window)에서 매칭한다.
 */
class RouteMatcher(private val course: CourseData, initialKm: Double = 0.0) {
    private var lastIndex: Int = course.indexAtKm(initialKm)
    private var hasMatchedFix: Boolean = initialKm > 0.1

    fun seekToKm(km: Double) {
        lastIndex = course.indexAtKm(km)
        hasMatchedFix = km > 0.1
    }

    fun currentKm(): Double = course.track.getOrNull(lastIndex)?.routeKm ?: 0.0

    fun match(lat: Double, lon: Double): MatchResult {
        val track = course.track
        if (track.isEmpty()) return MatchResult(0, 0.0, 0.0, Double.MAX_VALUE)

        fun findBest(start: Int, end: Int): Pair<Int, Double> {
            var bestI = start.coerceIn(track.indices)
            var bestD = Double.MAX_VALUE
            for (i in start.coerceAtLeast(0)..end.coerceAtMost(track.lastIndex)) {
                val p = track[i]
                val d = Geo.distanceMeters(lat, lon, p.lat, p.lon)
                if (d < bestD) {
                    bestD = d
                    bestI = i
                }
            }
            return bestI to bestD
        }

        val candidate: Pair<Int, Double> = if (!hasMatchedFix) {
            // 새 주행은 출발부 우선. 출발부에서 너무 멀면 전체 코스로 재탐색하여
            // 중간 지점에서 앱을 켜는 경우도 최소한 동작하게 한다.
            val local = findBest(0, min(track.lastIndex, 500))
            if (local.second <= 300.0) local else findBest(0, track.lastIndex)
        } else {
            // 약 2km 후방, 약 14km 전방까지만 탐색. 50→75km의 같은 보급소로
            // 순간이동하는 오인식을 막는 핵심 장치다.
            val local = findBest(max(0, lastIndex - 70), min(track.lastIndex, lastIndex + 450))
            if (local.second <= 350.0) {
                local
            } else {
                // GPS가 잠시 튄 경우 진행도 근처를 조금 넓게 보되 20km 이상은 점프하지 않는다.
                findBest(max(0, lastIndex - 100), min(track.lastIndex, lastIndex + 600))
            }
        }

        // 랠리 코스 진행도는 기본적으로 증가한다. 교차로 GPS 튐으로 뒤로 가는 것을 방지.
        if (!hasMatchedFix || candidate.first >= lastIndex - 8) {
            lastIndex = max(lastIndex, candidate.first)
        }
        hasMatchedFix = true

        val p = track[lastIndex]
        val off = Geo.distanceMeters(lat, lon, p.lat, p.lon)
        return MatchResult(lastIndex, p.routeKm, p.ele, off)
    }
}
