package com.seungjae.jangsu280battery

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.abs


data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val routeKm: Double
)

data class BatteryMarker(
    val km: Int,
    val arrivalPct: Double,
    val chargeToPct: Double? = null,
    val lat: Double,
    val lon: Double
)

data class RoutePoi(
    val name: String,
    val routeKm: Double,
    val lat: Double,
    val lon: Double,
    val desc: String = "",
    val type: String = "",
    val userAdded: Boolean = false
) {
    fun isSupplyLike(): Boolean {
        val s = "$name $desc $type".lowercase()
        val tokens = s.split(Regex("[^a-z0-9가-힣]+"))
        return s.contains("보급") || s.contains("급수") || s.contains("충전") || s.contains("점심") ||
            s.contains("lunch") || s.contains("feed") || s.contains("aid station") ||
            tokens.any { it == "as" || it.matches(Regex("as\\d+")) || it == "cp" || it.matches(Regex("cp\\d+")) }
    }
}

data class ElevationStats(val ascentM: Double, val descentM: Double)

data class RouteLocationMatch(
    val routeKm: Double,
    val trackLat: Double,
    val trackLon: Double,
    val distanceM: Double
)

data class MajorClimb(
    val startKm: Double,
    val endKm: Double,
    val distanceKm: Double,
    val ascentM: Double,
    val averageGradePct: Double
)

class CourseData(
    val name: String,
    val track: List<TrackPoint>,
    val batteryMarkers: Map<Int, BatteryMarker>,
    val pois: List<RoutePoi>,
    val hasElevation: Boolean,
    val totalAscentM: Double,
    val totalDescentM: Double
) {
    val totalKm: Double = track.lastOrNull()?.routeKm ?: 0.0
    val supplyPois: List<RoutePoi> get() = pois.filter { it.isSupplyLike() }

    fun withAdditionalPois(extra: List<RoutePoi>): CourseData = CourseData(
        name = name,
        track = track,
        batteryMarkers = batteryMarkers,
        pois = (pois + extra).distinctBy { "${it.name}|${String.format(java.util.Locale.US, "%.3f", it.routeKm)}" }.sortedBy { it.routeKm },
        hasElevation = hasElevation,
        totalAscentM = totalAscentM,
        totalDescentM = totalDescentM
    )

    fun indexAtKm(km: Double): Int {
        if (track.isEmpty()) return 0
        val target = km.coerceIn(0.0, totalKm)
        var lo = 0
        var hi = track.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (track[mid].routeKm < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun pointAtKm(km: Double): TrackPoint {
        if (track.isEmpty()) return TrackPoint(0.0, 0.0, 0.0, 0.0)
        val target = km.coerceIn(0.0, totalKm)
        val idx = indexAtKm(target)
        if (idx == 0) return track[0]
        val b = track[idx]
        val a = track[idx - 1]
        val span = b.routeKm - a.routeKm
        if (span <= 0.000001) return b
        val f = ((target - a.routeKm) / span).coerceIn(0.0, 1.0)
        return TrackPoint(
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            ele = a.ele + (b.ele - a.ele) * f,
            routeKm = target
        )
    }

    fun elevationAhead(fromKm: Double, spanKm: Double = 5.0): ElevationStats =
        elevationBetween(fromKm, (fromKm + spanKm).coerceAtMost(totalKm))

    fun elevationBetween(fromKm: Double, toKm: Double): ElevationStats {
        if (!hasElevation || track.size < 2 || toKm <= fromKm) return ElevationStats(0.0, 0.0)
        val start = indexAtKm(fromKm.coerceIn(0.0, totalKm))
        val end = indexAtKm(toKm.coerceIn(0.0, totalKm))
        var up = 0.0
        var down = 0.0
        if (end <= start) return ElevationStats(0.0, 0.0)
        for (i in (start + 1)..end) {
            if (i !in track.indices) break
            if (track[i].routeKm <= track[i - 1].routeKm + 0.00001) continue
            val diff = track[i].ele - track[i - 1].ele
            // GPX의 고도값 자체가 이미 보정/샘플링된 경우가 많다.
            // 1m 초과 변화만 세면 완만한 오르막이 거의 전부 누락되므로
            // 진행한 모든 양/음의 고도 변화를 누적한다.
            if (diff > 0.0) up += diff else if (diff < 0.0) down += -diff
        }
        return ElevationStats(up, down)
    }

    fun nextPoi(afterKm: Double): RoutePoi? = pois.firstOrNull { it.routeKm > afterKm + 0.04 }

    /** 주소/웨이포인트 좌표를 GPX 코스 진행거리로 투영한다. */
    fun nearestRouteLocation(lat: Double, lon: Double): RouteLocationMatch {
        if (track.isEmpty()) return RouteLocationMatch(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY)
        var best = track.first()
        var bestDistance = Double.MAX_VALUE
        for (p in track) {
            val d = Geo.distanceMeters(lat, lon, p.lat, p.lon)
            if (d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        return RouteLocationMatch(best.routeKm, best.lat, best.lon, bestDistance)
    }

    /**
     * 250m 단위 고도 변화를 묶어 다음 주요 업힐을 찾는다.
     * GPX에 고도 데이터가 없으면 업힐 분석을 하지 않는다.
     */
    fun nextMajorClimb(afterKm: Double, searchKm: Double = 22.0): MajorClimb? {
        if (!hasElevation) return null
        val startSearch = afterKm.coerceIn(0.0, totalKm)
        val endSearch = (startSearch + searchKm).coerceAtMost(totalKm)
        val step = 0.25
        var x = startSearch
        var activeStart: Double? = null
        var activeEnd = x
        var positiveAscent = 0.0
        var gapCount = 0

        fun finishCandidate(): MajorClimb? {
            val s = activeStart ?: return null
            val dist = (activeEnd - s).coerceAtLeast(0.0)
            if (dist < 0.8 || positiveAscent < 80.0) return null
            val net = pointAtKm(activeEnd).ele - pointAtKm(s).ele
            val grade = if (dist > 0.05) net / (dist * 1000.0) * 100.0 else 0.0
            return MajorClimb(s, activeEnd, dist, positiveAscent, grade)
        }

        while (x < endSearch - 0.01) {
            val nx = (x + step).coerceAtMost(endSearch)
            val diff = pointAtKm(nx).ele - pointAtKm(x).ele
            val climbing = diff >= 4.0
            val neutral = diff > -8.0
            if (climbing) {
                if (activeStart == null) {
                    activeStart = x
                    positiveAscent = 0.0
                    gapCount = 0
                }
                positiveAscent += diff.coerceAtLeast(0.0)
                activeEnd = nx
                gapCount = 0
            } else if (activeStart != null && neutral && gapCount < 2) {
                if (diff > 0) positiveAscent += diff
                activeEnd = nx
                gapCount++
            } else if (activeStart != null) {
                val candidate = finishCandidate()
                if (candidate != null && candidate.endKm > afterKm + 0.2) return candidate
                activeStart = null
                positiveAscent = 0.0
                gapCount = 0
            }
            x = nx
        }
        return finishCandidate()
    }

    companion object {
        private data class RawTrack(val lat: Double, val lon: Double, val ele: Double?, val segment: Int)
        private data class RawWpt(
            val lat: Double,
            val lon: Double,
            var ele: Double? = null,
            var name: String = "",
            var desc: String = "",
            var type: String = ""
        )

        private val batteryNameRegex = Regex("""^(\d{1,4})K\s+(\d+)%\s*(?:→\s*(\d+)%)?.*$""")
        private val exactNormalRegex = Regex("""잔량:\s*([0-9.]+)%""")
        private val exactChargeRegex = Regex("""예상\s*([0-9.]+)%""")

        fun load(context: Context, rawResId: Int, sourceName: String = "내장 코스"): CourseData =
            context.resources.openRawResource(rawResId).use { parse(it, sourceName) }

        fun parse(input: InputStream, sourceName: String = "GPX 코스"): CourseData {
            val rawTrack = mutableListOf<RawTrack>()
            val rawRoute = mutableListOf<RawTrack>()
            val rawWpts = mutableListOf<RawWpt>()

            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")

            var currentWpt: RawWpt? = null
            var pointKind: String? = null
            var pointLat = 0.0
            var pointLon = 0.0
            var pointEle: Double? = null
            var currentTag: String? = null
            var segment = -1
            var inMetadata = false
            var inTrk = false
            var inRte = false
            var metadataName = ""
            var trackName = ""
            var routeName = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (parser.name) {
                            "metadata" -> inMetadata = true
                            "trk" -> inTrk = true
                            "rte" -> inRte = true
                            "trkseg" -> segment++
                            "wpt" -> currentWpt = RawWpt(
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0,
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            )
                            "trkpt", "rtept" -> {
                                pointKind = parser.name
                                pointLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                pointLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                pointEle = null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            when {
                                currentWpt != null -> when (currentTag) {
                                    "name" -> currentWpt!!.name = txt
                                    "desc", "cmt" -> if (currentWpt!!.desc.isBlank()) currentWpt!!.desc = txt
                                    "type", "sym" -> if (currentWpt!!.type.isBlank()) currentWpt!!.type = txt
                                    "ele" -> currentWpt!!.ele = txt.toDoubleOrNull()
                                }
                                pointKind != null && currentTag == "ele" -> pointEle = txt.toDoubleOrNull()
                                currentTag == "name" && inMetadata && metadataName.isBlank() -> metadataName = txt
                                currentTag == "name" && inTrk && trackName.isBlank() -> trackName = txt
                                currentTag == "name" && inRte && routeName.isBlank() -> routeName = txt
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "metadata" -> inMetadata = false
                            "trk" -> inTrk = false
                            "rte" -> inRte = false
                            "wpt" -> {
                                currentWpt?.takeIf { abs(it.lat) <= 90 && abs(it.lon) <= 180 }?.let(rawWpts::add)
                                currentWpt = null
                            }
                            "trkpt" -> {
                                rawTrack += RawTrack(pointLat, pointLon, pointEle, segment.coerceAtLeast(0))
                                pointKind = null
                            }
                            "rtept" -> {
                                rawRoute += RawTrack(pointLat, pointLon, pointEle, 0)
                                pointKind = null
                            }
                        }
                        currentTag = null
                    }
                }
                event = parser.next()
            }

            val sourcePoints = if (rawTrack.size >= 2) rawTrack else rawRoute
            require(sourcePoints.size >= 2) { "GPX 트랙 또는 루트 포인트를 읽지 못했습니다." }

            val elevationCount = sourcePoints.count { it.ele != null }
            val hasElevation = elevationCount >= maxOf(2, sourcePoints.size / 5)
            var lastEle = sourcePoints.firstNotNullOfOrNull { it.ele } ?: 0.0
            val track = ArrayList<TrackPoint>(sourcePoints.size)
            var cumM = 0.0
            sourcePoints.forEachIndexed { i, p ->
                if (i > 0) {
                    val prev = sourcePoints[i - 1]
                    if (prev.segment == p.segment) {
                        cumM += Geo.distanceMeters(prev.lat, prev.lon, p.lat, p.lon)
                    }
                }
                if (p.ele != null) lastEle = p.ele
                track += TrackPoint(p.lat, p.lon, if (hasElevation) lastEle else 0.0, cumM / 1000.0)
            }
            require(track.last().routeKm > 0.05) { "GPX 코스 길이가 너무 짧습니다." }

            fun nearestTrackIndex(lat: Double, lon: Double): Int {
                var bestIndex = 0
                var best = Double.MAX_VALUE
                for (i in track.indices) {
                    val p = track[i]
                    val d = Geo.distanceMeters(lat, lon, p.lat, p.lon)
                    if (d < best) { best = d; bestIndex = i }
                }
                return bestIndex
            }

            val battery = sortedMapOf<Int, BatteryMarker>()
            val pois = mutableListOf<RoutePoi>()
            rawWpts.forEachIndexed { wptIndex, w ->
                val m = batteryNameRegex.matchEntire(w.name.trim())
                if (m != null) {
                    val km = m.groupValues[1].toInt()
                    val roundedArrival = m.groupValues[2].toDouble()
                    val charge = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    val exact = if (charge != null) exactChargeRegex.find(w.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        else exactNormalRegex.find(w.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    battery[km] = BatteryMarker(km, exact ?: roundedArrival, charge, w.lat, w.lon)
                } else {
                    val idx = nearestTrackIndex(w.lat, w.lon)
                    val displayName = w.name.replace('_', ' ').ifBlank { "웨이포인트 ${wptIndex + 1}" }
                    pois += RoutePoi(displayName, track[idx].routeKm, w.lat, w.lon, w.desc, w.type)
                }
            }

            var totalUp = 0.0
            var totalDown = 0.0
            if (hasElevation) {
                for (i in 1 until track.size) {
                    if (track[i].routeKm <= track[i - 1].routeKm + 0.00001) continue
                    val d = track[i].ele - track[i - 1].ele
                    if (d > 0.0) totalUp += d else if (d < 0.0) totalDown += -d
                }
            }

            val resolvedName = listOf(metadataName, trackName, routeName, sourceName.substringBeforeLast('.'))
                .firstOrNull { it.isNotBlank() } ?: "GPX 코스"
            return CourseData(
                name = resolvedName,
                track = track,
                batteryMarkers = battery,
                pois = pois.sortedBy { it.routeKm },
                hasElevation = hasElevation,
                totalAscentM = totalUp,
                totalDescentM = totalDown
            )
        }
    }
}
