package com.seungjae.jangsu280battery

import kotlin.math.max

/** 목표시간 기반 ROAD 그란폰도 사전 시뮬레이션. */
data class SimulationRiderConfig(
    val nickname: String,
    val targetSec: Double,
    val startOffsetSec: Double = 0.0,
    val defaultAidStopSec: Double = 0.0
)

data class SimulationAidStop(
    val name: String,
    val km: Double,
    val arrivalRaceSec: Double,
    val departureRaceSec: Double
) {
    val durationSec: Double get() = (departureRaceSec - arrivalRaceSec).coerceAtLeast(0.0)
}

data class SimulationCheckpointPass(
    val name: String,
    val km: Double,
    val raceSec: Double
)

data class SimulationRiderPlan(
    val nickname: String,
    val config: SimulationRiderConfig,
    val motionPlan: RoadPlan,
    val aidStops: List<SimulationAidStop>,
    val checkpoints: List<SimulationCheckpointPass>,
    val finishRaceSec: Double
)

data class SimulationRiderState(
    val nickname: String,
    val raceSec: Double,
    val routeKm: Double,
    val speedKph: Double,
    val status: String,
    val aidName: String? = null,
    val aidElapsedSec: Double = 0.0,
    val finished: Boolean = false,
    val started: Boolean = true
)

data class SimulationCheckpointStanding(
    val checkpointName: String,
    val km: Double,
    val riders: List<Pair<String, Double>>
)

object RoadRaceSimulationEngine {
    fun buildRiderPlan(course: CourseData, config: SimulationRiderConfig): SimulationRiderPlan {
        val aidSelections = if (config.defaultAidStopSec > 0.0) {
            aidStations(course).map { RoadAidSelection(it.name.ifBlank { "보급소" }, it.routeKm, config.defaultAidStopSec) }
        } else emptyList()
        val motion = RoadGranfondoEngine.buildTargetPlan(course, config.targetSec, aidSelections)
        val aidStops = motion.aidStops.map {
            SimulationAidStop(
                it.name,
                it.km,
                config.startOffsetSec + it.arrivalElapsedSec,
                config.startOffsetSec + it.departureElapsedSec
            )
        }
        val cps = motion.checkpoints.map { cp ->
            SimulationCheckpointPass(cp.name, cp.km, config.startOffsetSec + cp.targetElapsedSec)
        }
        return SimulationRiderPlan(
            nickname = config.nickname,
            config = config,
            motionPlan = motion,
            aidStops = aidStops,
            checkpoints = cps,
            finishRaceSec = config.startOffsetSec + config.targetSec
        )
    }

    fun stateAt(course: CourseData, rider: SimulationRiderPlan, raceSecRaw: Double): SimulationRiderState {
        val raceSec = raceSecRaw.coerceAtLeast(0.0)
        if (raceSec < rider.config.startOffsetSec) {
            return SimulationRiderState(rider.nickname, raceSec, 0.0, 0.0, "출발 대기", started = false)
        }
        if (raceSec >= rider.finishRaceSec) {
            return SimulationRiderState(rider.nickname, raceSec, course.totalKm, 0.0, "완주", finished = true)
        }
        rider.aidStops.firstOrNull { raceSec >= it.arrivalRaceSec && raceSec < it.departureRaceSec }?.let { stop ->
            return SimulationRiderState(
                rider.nickname,
                raceSec,
                stop.km,
                0.0,
                "${stop.name} 휴식중",
                aidName = stop.name,
                aidElapsedSec = raceSec - stop.arrivalRaceSec
            )
        }
        val completedStopSec = rider.aidStops.filter { raceSec >= it.departureRaceSec }.sumOf { it.durationSec }
        val motionSec = raceSec - rider.config.startOffsetSec - completedStopSec
        val km = invertKm(rider.motionPlan, motionSec).coerceIn(0.0, course.totalKm)
        val speed = localSpeed(rider.motionPlan, km)
        val passedAid = rider.aidStops.lastOrNull { it.departureRaceSec <= raceSec && raceSec - it.departureRaceSec <= 90.0 }
        val status = if (passedAid != null) "${passedAid.name} 출발" else "주행중"
        return SimulationRiderState(rider.nickname, raceSec, km, speed, status)
    }

    fun checkpointStandings(riders: List<SimulationRiderPlan>): List<SimulationCheckpointStanding> {
        val keys = riders.flatMap { it.checkpoints }.map { it.name to it.km }
            .distinctBy { "${it.first}|${"%.3f".format(java.util.Locale.US, it.second)}" }
            .sortedBy { it.second }
        return keys.map { (name, km) ->
            val arrivals = riders.mapNotNull { rider ->
                rider.checkpoints.minByOrNull { kotlin.math.abs(it.km - km) }
                    ?.takeIf { kotlin.math.abs(it.km - km) <= 0.4 }
                    ?.let { rider.nickname to it.raceSec }
            }.sortedBy { it.second }
            SimulationCheckpointStanding(name, km, arrivals)
        }
    }

    fun aidStations(course: CourseData): List<RoutePoi> = course.supplyPois.sortedBy { it.routeKm }

    fun timeline(riders: List<SimulationRiderPlan>, course: CourseData, stepSec: Double = 60.0): List<List<SimulationRiderState>> {
        if (riders.isEmpty()) return emptyList()
        val end = riders.maxOf { it.finishRaceSec }
        val out = mutableListOf<List<SimulationRiderState>>()
        var t = 0.0
        val step = max(5.0, stepSec)
        while (t < end) {
            out += riders.map { stateAt(course, it, t) }
            t += step
        }
        out += riders.map { stateAt(course, it, end) }
        return out
    }

    private fun invertKm(plan: RoadPlan, elapsedSecRaw: Double): Double {
        if (plan.samplesKm.isEmpty()) return 0.0
        val elapsedSec = elapsedSecRaw.coerceIn(0.0, plan.samplesElapsedSec.last())
        var lo = 0
        var hi = plan.samplesElapsedSec.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (plan.samplesElapsedSec[mid] < elapsedSec) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return plan.samplesKm[0]
        val t1 = plan.samplesElapsedSec[lo - 1]
        val t2 = plan.samplesElapsedSec[lo]
        val k1 = plan.samplesKm[lo - 1]
        val k2 = plan.samplesKm[lo]
        val f = if (t2 > t1) ((elapsedSec - t1) / (t2 - t1)).coerceIn(0.0, 1.0) else 1.0
        return k1 + (k2 - k1) * f
    }

    private fun localSpeed(plan: RoadPlan, km: Double): Double {
        if (plan.samplesKm.size < 2) return 0.0
        var idx = plan.samplesKm.indexOfFirst { it >= km }
        if (idx < 1) idx = 1
        idx = idx.coerceAtMost(plan.samplesKm.lastIndex)
        val dk = plan.samplesKm[idx] - plan.samplesKm[idx - 1]
        val dt = plan.samplesElapsedSec[idx] - plan.samplesElapsedSec[idx - 1]
        return if (dk > 0.0 && dt > 0.0) (dk / (dt / 3600.0)).coerceIn(0.0, 100.0) else 0.0
    }
}
