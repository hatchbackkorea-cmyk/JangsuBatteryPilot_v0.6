package com.seungjae.jangsu280battery

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
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
    val lon: Double
)

data class ElevationStats(val ascentM: Double, val descentM: Double)

data class MajorClimb(
    val startKm: Double,
    val endKm: Double,
    val distanceKm: Double,
    val ascentM: Double,
    val averageGradePct: Double
)

class CourseData(
    val track: List<TrackPoint>,
    val batteryMarkers: Map<Int, BatteryMarker>,
    val pois: List<RoutePoi>
) {
    val totalKm: Double = track.lastOrNull()?.routeKm ?: 0.0

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
        val idx = indexAtKm(km)
        if (idx == 0) return track[0]
        val b = track[idx]
        val a = track[idx - 1]
        val span = b.routeKm - a.routeKm
        if (span <= 0.000001) return b
        val f = ((km - a.routeKm) / span).coerceIn(0.0, 1.0)
        return TrackPoint(
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            ele = a.ele + (b.ele - a.ele) * f,
            routeKm = km.coerceIn(0.0, totalKm)
        )
    }

    fun elevationAhead(fromKm: Double, spanKm: Double = 5.0): ElevationStats {
        if (track.size < 2) return ElevationStats(0.0, 0.0)
        val start = indexAtKm(fromKm)
        val end = indexAtKm((fromKm + spanKm).coerceAtMost(totalKm))
        var up = 0.0
        var down = 0.0
        for (i in (start + 1)..end) {
            if (i !in track.indices) break
            val diff = track[i].ele - track[i - 1].ele
            if (diff > 0) up += diff else down += -diff
        }
        return ElevationStats(up, down)
    }

    fun nextPoi(afterKm: Double): RoutePoi? =
        pois.firstOrNull { it.routeKm > afterKm + 0.04 }

    /**
     * 250m 단위 고도 변화를 부드럽게 묶어 다음 주요 업힐을 찾는다.
     * 정확한 도로 경사계가 아니라 GPX 기반 라이딩 사전 안내용이다.
     */
    fun nextMajorClimb(afterKm: Double, searchKm: Double = 22.0): MajorClimb? {
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
        private data class RawTrack(val lat: Double, val lon: Double, val ele: Double)
        private data class RawWpt(
            val lat: Double,
            val lon: Double,
            var ele: Double = 0.0,
            var name: String = "",
            var desc: String = "",
            var type: String = ""
        )

        private val batteryNameRegex = Regex("""^(\d{3})K\s+(\d+)%\s*(?:→\s*(\d+)%)?.*$""")
        private val exactNormalRegex = Regex("""잔량:\s*([0-9.]+)%""")
        private val exactChargeRegex = Regex("""예상\s*([0-9.]+)%""")

        fun load(context: Context, rawResId: Int): CourseData {
            val rawTrack = mutableListOf<RawTrack>()
            val rawWpts = mutableListOf<RawWpt>()

            context.resources.openRawResource(rawResId).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")

                var currentWpt: RawWpt? = null
                var currentTrk: RawTrack? = null
                var currentTag: String? = null
                var trkLat = 0.0
                var trkLon = 0.0
                var trkEle = 0.0

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name
                            when (parser.name) {
                                "wpt" -> {
                                    currentWpt = RawWpt(
                                        lat = parser.getAttributeValue(null, "lat").toDouble(),
                                        lon = parser.getAttributeValue(null, "lon").toDouble()
                                    )
                                }
                                "trkpt" -> {
                                    trkLat = parser.getAttributeValue(null, "lat").toDouble()
                                    trkLon = parser.getAttributeValue(null, "lon").toDouble()
                                    trkEle = 0.0
                                    currentTrk = RawTrack(trkLat, trkLon, trkEle)
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val txt = parser.text?.trim().orEmpty()
                            if (txt.isNotEmpty()) {
                                if (currentWpt != null) {
                                    when (currentTag) {
                                        "name" -> currentWpt.name = txt
                                        "desc" -> currentWpt.desc = txt
                                        "type" -> currentWpt.type = txt
                                        "ele" -> currentWpt.ele = txt.toDoubleOrNull() ?: currentWpt.ele
                                    }
                                } else if (currentTrk != null && currentTag == "ele") {
                                    trkEle = txt.toDoubleOrNull() ?: 0.0
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "wpt" -> {
                                    currentWpt?.let(rawWpts::add)
                                    currentWpt = null
                                }
                                "trkpt" -> {
                                    rawTrack.add(RawTrack(trkLat, trkLon, trkEle))
                                    currentTrk = null
                                }
                            }
                            currentTag = null
                        }
                    }
                    event = parser.next()
                }
            }

            require(rawTrack.size >= 2) { "GPX 트랙 포인트를 읽지 못했습니다." }

            val track = ArrayList<TrackPoint>(rawTrack.size)
            var cumM = 0.0
            rawTrack.forEachIndexed { i, p ->
                if (i > 0) {
                    val prev = rawTrack[i - 1]
                    cumM += Geo.distanceMeters(prev.lat, prev.lon, p.lat, p.lon)
                }
                track.add(TrackPoint(p.lat, p.lon, p.ele, cumM / 1000.0))
            }

            fun nearestTrackIndex(lat: Double, lon: Double): Int {
                var bestIndex = 0
                var best = Double.MAX_VALUE
                for (i in track.indices) {
                    val p = track[i]
                    val d = Geo.distanceMeters(lat, lon, p.lat, p.lon)
                    if (d < best) {
                        best = d
                        bestIndex = i
                    }
                }
                return bestIndex
            }

            val battery = sortedMapOf<Int, BatteryMarker>()
            val pois = mutableListOf<RoutePoi>()

            for (w in rawWpts) {
                val m = batteryNameRegex.matchEntire(w.name.trim())
                if (m != null) {
                    val km = m.groupValues[1].toInt()
                    val roundedArrival = m.groupValues[2].toDouble()
                    val charge = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    val exact = if (charge != null) {
                        exactChargeRegex.find(w.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    } else {
                        exactNormalRegex.find(w.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    }
                    battery[km] = BatteryMarker(km, exact ?: roundedArrival, charge, w.lat, w.lon)
                } else if (w.name.isNotBlank()) {
                    // 배터리 안내는 별도 처리. 같은 물리 위치를 두 번 지나는 1보급소는
                    // 배터리 체크포인트(50/75km)가 담당하므로 일반 POI 안내에서는 제외한다.
                    if (!w.name.contains("1보급소")) {
                        val idx = nearestTrackIndex(w.lat, w.lon)
                        pois.add(RoutePoi(w.name.replace('_', ' '), track[idx].routeKm, w.lat, w.lon))
                    }
                }
            }

            return CourseData(track, battery, pois.sortedBy { it.routeKm })
        }
    }
}
