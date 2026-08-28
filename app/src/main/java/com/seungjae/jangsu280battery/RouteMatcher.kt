package com.seungjae.jangsu280battery

import kotlin.math.abs


data class MatchResult(
    val index: Int,
    val routeKm: Double,
    val courseElevationM: Double,
    val offCourseMeters: Double,
    val gpsHeld: Boolean = false,
    val recoveredFromKm: Double? = null
)

/**
 * GPX route matcher with bad-GPS protection.
 *
 * v0.29.0:
 * - A far-away GPS fix is never allowed to advance the route just because the best point
 *   inside the forward search window happens to be at the end of that window.
 * - Normal matching uses a narrow continuity window.
 * - A large relocation/recovery is accepted only after several consecutive fixes agree.
 *   This lets the app recover from a previously corrupted persisted routeKm without
 *   letting one rainy/poor GPS fix teleport the rider to the finish.
 */
class RouteMatcher(private val course: CourseData, initialKm: Double = 0.0) {
    private var lastIndex: Int = course.indexAtKm(initialKm)
    private var hasMatchedFix: Boolean = initialKm > 0.1

    private var pendingRelocationKm: Double? = null
    private var pendingRelocationIndex: Int = -1
    private var pendingRelocationCount: Int = 0

    fun seekToKm(km: Double) {
        lastIndex = course.indexAtKm(km)
        hasMatchedFix = km > 0.1
        clearPendingRelocation()
    }

    fun currentKm(): Double = course.track.getOrNull(lastIndex)?.routeKm ?: 0.0

    fun match(lat: Double, lon: Double, accuracyM: Float = -1f): MatchResult {
        val track = course.track
        if (track.isEmpty()) return MatchResult(0, 0.0, 0.0, Double.MAX_VALUE, gpsHeld = true)

        fun findBest(start: Int, end: Int): Pair<Int, Double> {
            val s = start.coerceIn(track.indices)
            val e = end.coerceIn(track.indices)
            var bestI = s
            var bestD = Double.MAX_VALUE
            if (e < s) return bestI to bestD
            for (i in s..e) {
                val p = track[i]
                val d = Geo.distanceMeters(lat, lon, p.lat, p.lon)
                if (d < bestD) {
                    bestD = d
                    bestI = i
                }
            }
            return bestI to bestD
        }

        val matchRadiusM = if (accuracyM.isFinite() && accuracyM > 0f) {
            (accuracyM.toDouble() * 3.0).coerceIn(90.0, 220.0)
        } else {
            150.0
        }

        val beforeKm = currentKm()

        // Fresh start: trust a start-area fix immediately, but an arbitrary mid-course fix
        // needs repeated confirmation. This blocks a single bad first fix from jumping to 134 km.
        if (!hasMatchedFix) {
            val startArea = findBest(0, course.indexAtKm(5.0.coerceAtMost(course.totalKm)))
            if (startArea.second <= matchRadiusM) {
                lastIndex = startArea.first
                hasMatchedFix = true
                clearPendingRelocation()
                return resultFor(lat, lon, gpsHeld = false)
            }

            val global = findBest(0, track.lastIndex)
            if (global.second <= matchRadiusM) {
                val relocated = confirmRelocation(track[global.first].routeKm, global.first, required = 3)
                if (relocated) {
                    hasMatchedFix = true
                    return resultFor(lat, lon, gpsHeld = false, recoveredFromKm = beforeKm)
                }
            } else {
                clearPendingRelocation()
            }
            return resultFor(lat, lon, gpsHeld = true)
        }

        // Normal riding: deliberately narrow window. If GPS was absent for a long time,
        // the global recovery path below will relocate after repeated confirmation.
        val local = findBest(
            course.indexAtKm((beforeKm - 1.0).coerceAtLeast(0.0)),
            course.indexAtKm((beforeKm + 3.0).coerceAtMost(course.totalKm))
        )
        val expanded = if (local.second <= matchRadiusM) local else findBest(
            course.indexAtKm((beforeKm - 2.0).coerceAtLeast(0.0)),
            course.indexAtKm((beforeKm + 6.0).coerceAtMost(course.totalKm))
        )

        if (expanded.second <= matchRadiusM) {
            val candidateKm = track[expanded.first].routeKm

            // Small normal backwards tolerance for switchbacks/intersections.
            if (candidateKm >= beforeKm - 0.15) {
                // Even inside the local window, a >1.5 km instant leap gets a 2-fix confirmation.
                if (candidateKm > beforeKm + 1.5) {
                    if (confirmRelocation(candidateKm, expanded.first, required = 2)) {
                        return resultFor(lat, lon, gpsHeld = false, recoveredFromKm = beforeKm)
                    }
                    return resultFor(lat, lon, gpsHeld = true)
                }
                lastIndex = expanded.first
                clearPendingRelocation()
                return resultFor(lat, lon, gpsHeld = false)
            }
        }

        // Current progress is inconsistent with the real GPS position. Search globally, but
        // only recover after 3 consecutive fixes agree on roughly the same route position.
        val global = findBest(0, track.lastIndex)
        val globalKm = track[global.first].routeKm
        if (global.second <= matchRadiusM && abs(globalKm - beforeKm) >= 0.5) {
            if (confirmRelocation(globalKm, global.first, required = 3)) {
                return resultFor(lat, lon, gpsHeld = false, recoveredFromKm = beforeKm)
            }
        } else {
            clearPendingRelocation()
        }

        // Hold the last trusted progress. Crucially: no forward-window endpoint is accepted
        // when the GPS fix is far away from the course.
        return resultFor(lat, lon, gpsHeld = true)
    }

    private fun confirmRelocation(candidateKm: Double, candidateIndex: Int, required: Int): Boolean {
        val pending = pendingRelocationKm
        if (pending != null && abs(candidateKm - pending) <= 0.75) {
            pendingRelocationCount += 1
            pendingRelocationKm = (pending * (pendingRelocationCount - 1) + candidateKm) / pendingRelocationCount
            pendingRelocationIndex = candidateIndex
        } else {
            pendingRelocationKm = candidateKm
            pendingRelocationIndex = candidateIndex
            pendingRelocationCount = 1
        }
        if (pendingRelocationCount >= required && pendingRelocationIndex in course.track.indices) {
            lastIndex = pendingRelocationIndex
            clearPendingRelocation()
            return true
        }
        return false
    }

    private fun clearPendingRelocation() {
        pendingRelocationKm = null
        pendingRelocationIndex = -1
        pendingRelocationCount = 0
    }

    private fun resultFor(
        lat: Double,
        lon: Double,
        gpsHeld: Boolean,
        recoveredFromKm: Double? = null
    ): MatchResult {
        val p = course.track[lastIndex]
        return MatchResult(
            index = lastIndex,
            routeKm = p.routeKm,
            courseElevationM = p.ele,
            offCourseMeters = Geo.distanceMeters(lat, lon, p.lat, p.lon),
            gpsHeld = gpsHeld,
            recoveredFromKm = recoveredFromKm
        )
    }
}
