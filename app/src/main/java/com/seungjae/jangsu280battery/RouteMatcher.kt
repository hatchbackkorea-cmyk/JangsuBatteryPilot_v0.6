package com.seungjae.jangsu280battery

import kotlin.math.max


data class MatchResult(
    val index: Int,
    val routeKm: Double,
    val courseElevationM: Double,
    val offCourseMeters: Double
)

/**
 * 임의 GPX에서도 포인트 밀도와 무관하게 동작하도록 '인덱스 개수'가 아니라
 * 직전 진행거리 기준의 km 창(window) 안에서 위치를 매칭한다.
 * 동일 장소를 두 번 지나는 코스에서도 과거/미래 다른 랩으로 순간이동하지 않게 한다.
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
            val s = start.coerceIn(track.indices)
            val e = end.coerceIn(track.indices)
            var bestI = s
            var bestD = Double.MAX_VALUE
            if (e < s) return bestI to bestD
            for (i in s..e) {
                val p = track[i]
                val d = Geo.distanceMeters(lat, lon, p.lat, p.lon)
                if (d < bestD) { bestD = d; bestI = i }
            }
            return bestI to bestD
        }

        val candidate = if (!hasMatchedFix) {
            val local = findBest(0, course.indexAtKm(5.0.coerceAtMost(course.totalKm)))
            if (local.second <= 300.0) local else findBest(0, track.lastIndex)
        } else {
            val currentKm = currentKm()
            val local = findBest(
                course.indexAtKm((currentKm - 2.0).coerceAtLeast(0.0)),
                course.indexAtKm((currentKm + 14.0).coerceAtMost(course.totalKm))
            )
            if (local.second <= 350.0) local else findBest(
                course.indexAtKm((currentKm - 3.0).coerceAtLeast(0.0)),
                course.indexAtKm((currentKm + 20.0).coerceAtMost(course.totalKm))
            )
        }

        // 진행도는 기본적으로 증가. 교차로 GPS 튐은 최대 약 150m 후퇴만 허용한다.
        val candidateKm = track[candidate.first].routeKm
        val lastKm = track[lastIndex].routeKm
        if (!hasMatchedFix || candidateKm >= lastKm - 0.15) {
            lastIndex = if (candidateKm >= lastKm) candidate.first else max(0, candidate.first)
        }
        hasMatchedFix = true
        val p = track[lastIndex]
        return MatchResult(lastIndex, p.routeKm, p.ele, Geo.distanceMeters(lat, lon, p.lat, p.lon))
    }
}
