package com.seungjae.jangsu280battery

import android.location.Location
import kotlin.math.*

/** GPS -> 1D course progress matcher and oriented gate crossing math. */
class RaceRouteMatcher(private val course: CourseData) {
    data class Match(val routeM: Double, val distanceM: Double, val segmentIndex: Int)
    private var lastRouteM: Double? = null

    fun reset(routeM: Double? = null) { lastRouteM = routeM }

    fun match(location: Location): Match {
        val track = course.track
        if (track.size < 2) return Match(0.0, Double.POSITIVE_INFINITY, 0)
        val last = lastRouteM
        val startKm = if (last == null) 0.0 else ((last / 1000.0) - 0.12).coerceAtLeast(0.0)
        val endKm = if (last == null) course.totalKm else ((last / 1000.0) + 0.40).coerceAtMost(course.totalKm)
        var start = if (last == null) 0 else course.indexAtKm(startKm).coerceAtMost(track.lastIndex - 1)
        var end = if (last == null) track.lastIndex - 1 else course.indexAtKm(endKm).coerceIn(start, track.lastIndex - 1)
        var best = projectRange(location.latitude, location.longitude, start, end)
        if (best.distanceM > 85.0 && last == null) best = projectRange(location.latitude, location.longitude, 0, track.lastIndex - 1)
        if (best.distanceM > 120.0 && last != null) {
            // Lost-course recovery still rejects implausible folded-trail jumps by clamping the search to +/-800m.
            start = course.indexAtKm(((last / 1000.0) - 0.25).coerceAtLeast(0.0)).coerceAtMost(track.lastIndex - 1)
            end = course.indexAtKm(((last / 1000.0) + 0.80).coerceAtMost(course.totalKm)).coerceIn(start, track.lastIndex - 1)
            best = projectRange(location.latitude, location.longitude, start, end)
        }
        if (best.distanceM < 150.0) lastRouteM = best.routeM
        return best
    }

    private fun projectRange(lat: Double, lon: Double, start: Int, end: Int): Match {
        val track = course.track
        var bestD = Double.POSITIVE_INFINITY; var bestM = 0.0; var bestI = start
        val latScale = 110_540.0
        for (i in start..end) {
            val a = track[i]; val b = track[i + 1]
            val lonScale = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) * 0.5))
            val ax = (a.lon - lon) * lonScale; val ay = (a.lat - lat) * latScale
            val bx = (b.lon - lon) * lonScale; val by = (b.lat - lat) * latScale
            val vx = bx - ax; val vy = by - ay
            val vv = vx * vx + vy * vy
            val t = if (vv < 1e-9) 0.0 else (-(ax * vx + ay * vy) / vv).coerceIn(0.0, 1.0)
            val px = ax + vx * t; val py = ay + vy * t
            val d = sqrt(px * px + py * py)
            if (d < bestD) {
                bestD = d; bestI = i
                bestM = (a.routeKm + (b.routeKm - a.routeKm) * t) * 1000.0
            }
        }
        return Match(bestM, bestD, bestI)
    }
}

object RaceGateMath {
    fun practiceConfig(courseId: String, course: CourseData, sectorCount: Int = 4): RaceEventConfig {
        val totalM = course.totalKm * 1000.0
        val embedded = embeddedGates(course)
        val gates = if (embedded.size >= 2 && embedded.first().type == "START" && embedded.last().type == "FINISH") {
            embedded
        } else {
            val count = sectorCount.coerceIn(2, 8)
            mutableListOf<RaceGate>().apply {
                add(gateAt(course, 0.0, "START", "START"))
                for (i in 1 until count) add(gateAt(course, totalM * i / count, "S$i", "SECTOR"))
                add(gateAt(course, totalM, "FINISH", "FINISH"))
            }
        }
        val name = if (embedded.isNotEmpty()) "연습 RACE · 저장 트랩" else "연습 RACE"
        return RaceEventConfig(0L, "PRACTICE", name, null, course.name, totalM, gates)
    }

    fun normalize(config: RaceEventConfig, course: CourseData): RaceEventConfig {
        val gates = config.gates.map { g ->
            if (abs(g.lat) > 0.00001 || abs(g.lon) > 0.00001) g
            else gateAt(course, g.routeM.coerceIn(0.0, course.totalKm * 1000.0), g.name, g.type, g.widthM)
        }.sortedBy { it.routeM }
        return config.copy(distanceM = if (config.distanceM > 10.0) config.distanceM else course.totalKm * 1000.0, gates = gates)
    }

    fun gateAt(course: CourseData, routeM: Double, name: String, type: String, widthM: Double = 30.0): RaceGate {
        val m = routeM.coerceIn(0.0, course.totalKm * 1000.0)
        val p = course.pointAtKm(m / 1000.0)
        val before = course.pointAtKm(((m - 12.0).coerceAtLeast(0.0)) / 1000.0)
        val after = course.pointAtKm(((m + 12.0).coerceAtMost(course.totalKm * 1000.0)) / 1000.0)
        val bearing = bearingDeg(before.lat, before.lon, after.lat, after.lon)
        return RaceGate(name, type, m, p.lat, p.lon, bearing, widthM.coerceIn(8.0, 100.0))
    }

    private fun embeddedGates(course: CourseData): List<RaceGate> {
        val out = course.pois.mapNotNull { p ->
            val t = when (p.type.uppercase()) {
                "RACE_START" -> "START"
                "RACE_FINISH" -> "FINISH"
                "RACE_SECTOR" -> "SECTOR"
                else -> return@mapNotNull null
            }
            val fallback = gateAt(course, p.routeKm * 1000.0, p.name, t, 50.0)
            val bearing = Regex("(?:^|;)bearing=([-+0-9.]+)").find(p.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback.bearingDeg
            val width = Regex("(?:^|;)width=([-+0-9.]+)").find(p.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback.widthM
            RaceGate(p.name.ifBlank { t }, t, p.routeKm * 1000.0, p.lat, p.lon, bearing, width.coerceIn(8.0, 100.0))
        }.sortedBy { it.routeM }
        if (out.count { it.type == "START" } != 1 || out.count { it.type == "FINISH" } != 1) return emptyList()
        val start = out.firstOrNull { it.type == "START" } ?: return emptyList()
        val finish = out.lastOrNull { it.type == "FINISH" } ?: return emptyList()
        if (start.routeM >= finish.routeM) return emptyList()
        return out.filter { it.routeM in start.routeM..finish.routeM }.sortedBy { it.routeM }
    }

    fun crossingFraction(prev: Location, cur: Location, gate: RaceGate): Double? {
        val dt = cur.time - prev.time
        if (dt <= 0L || dt > 10_000L) return null
        val segmentM = Geo.distanceMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
        if (segmentM > max(140.0, gate.widthM * 4.0)) return null
        val latScale = 110_540.0
        val lonScale = 111_320.0 * cos(Math.toRadians(gate.lat))
        fun xy(lat: Double, lon: Double) = Pair((lon - gate.lon) * lonScale, (lat - gate.lat) * latScale)
        val p1 = xy(prev.latitude, prev.longitude); val p2 = xy(cur.latitude, cur.longitude)
        val rad = Math.toRadians(gate.bearingDeg)
        val routeX = sin(rad); val routeY = cos(rad)
        val moveX = p2.first - p1.first; val moveY = p2.second - p1.second
        if (moveX * routeX + moveY * routeY <= 0.0) return null
        val half = gate.widthM / 2.0
        val perpX = cos(rad); val perpY = -sin(rad)
        val aX = -perpX * half; val aY = -perpY * half
        val bX = perpX * half; val bY = perpY * half
        val rX = moveX; val rY = moveY; val sX = bX - aX; val sY = bY - aY
        fun cross(x1: Double, y1: Double, x2: Double, y2: Double) = x1 * y2 - y1 * x2
        val den = cross(rX, rY, sX, sY)
        if (abs(den) < 1e-7) return null
        val qpx = aX - p1.first; val qpy = aY - p1.second
        val t = cross(qpx, qpy, sX, sY) / den
        val u = cross(qpx, qpy, rX, rY) / den
        return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
    }

    fun crossingTimeMs(prev: Location, cur: Location, gate: RaceGate): Long? {
        val f = crossingFraction(prev, cur, gate) ?: return null
        return prev.time + ((cur.time - prev.time) * f).roundToLong()
    }

    fun interpolateReference(points: List<RaceReferencePoint>, routeM: Double): Long? {
        if (points.size < 2) return null
        if (routeM <= points.first().routeM) return points.first().elapsedMs
        if (routeM >= points.last().routeM) return points.last().elapsedMs
        var lo = 0; var hi = points.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].routeM <= routeM) lo = mid else hi = mid
        }
        val a = points[lo]; val b = points[hi]
        val span = b.routeM - a.routeM
        if (span <= 0.01) return a.elapsedMs
        val f = ((routeM - a.routeM) / span).coerceIn(0.0, 1.0)
        return (a.elapsedMs + (b.elapsedMs - a.elapsedMs) * f).roundToLong()
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
