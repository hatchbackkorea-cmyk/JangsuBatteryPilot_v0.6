package com.seungjae.jangsu280battery

import kotlin.math.max

/**
 * ROAD 그란폰도 사전 시뮬레이션 전용 모델.
 * 실제 대회 기록을 꾸며내는 용도가 아니라, 참가자별 과거 FIT/파워 + 공통 GPX를 이용해
 * 예상 진행/보급소 체류/체크포인트 통과 순서를 재생하기 위한 계획 모델이다.
 */
data class SimulationRiderConfig(
    val nickname: String,
    val profile: RoadTrainingProfile,
    val targetSec: Double? = null,
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
        val motion = RoadGranfondoEngine.buildPlan(course, config.targetSec, config.profile)
        val aids = aidStations(course)
        var cumulativeStop = 0.0
        val aidStops = mutableListOf<SimulationAidStop>()
        aids.forEach { poi ->
            val arrival = config.startOffsetSec + motion.expectedElapsedSec(poi.routeKm) + cumulativeStop
            val stop = config.defaultAidStopSec.coerceAtLeast(0.0)
            val departure = arrival + stop
            aidStops += SimulationAidStop(poi.name.ifBlank { "보급소" }, poi.routeKm, arrival, departure)
            cumulativeStop += stop
        }
        val cps = motion.checkpoints.map { cp ->
            val priorStops = aidStops.filter { it.km < cp.km - 0.02 }.sumOf { it.durationSec }
            SimulationCheckpointPass(cp.name, cp.km, config.startOffsetSec + cp.targetElapsedSec + priorStops)
        }
        return SimulationRiderPlan(
            nickname = config.nickname,
            config = config,
            motionPlan = motion,
            aidStops = aidStops,
            checkpoints = cps,
            finishRaceSec = config.startOffsetSec + motion.totalSec + aidStops.sumOf { it.durationSec }
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

/** 참가자 한 명의 여러 과거 FIT을 시뮬레이션용 ROAD 프로필로 합친다. */
object RoadSimulationProfileBuilder {
    private val bins = listOf(-99.0 to -4.0, -4.0 to -1.0, -1.0 to 1.0, 1.0 to 3.0, 3.0 to 6.0, 6.0 to 99.0)

    fun fromFits(analyses: List<HistoricalRideAnalysis>, manualPower: RoadPowerProfile = RoadPowerProfile()): RoadTrainingProfile {
        val out = bins.map { (a, b) -> RoadGradeBin(a, b, 0.0, 0.0, 0.0, 0.0) }.toMutableList()
        var totalDistance = 0.0
        var totalMoving = 0.0
        analyses.forEach { analysis ->
            var fitDistance = 0.0
            var fitMoving = 0.0
            val points = analysis.telemetry
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val ta = a.timestampMs ?: continue
                val tb = b.timestampMs ?: continue
                val dt = (tb - ta) / 1000.0
                if (dt !in 0.2..30.0) continue
                val dk = b.routeKm - a.routeKm
                if (dk <= 0.00001 || dk > 0.5) continue
                val speed = listOfNotNull(a.speedKph, b.speedKph).let { if (it.isEmpty()) dk / (dt / 3600.0) else it.average() }
                if (!speed.isFinite() || speed !in 2.0..100.0) continue
                val ea = a.elevationM
                val eb = b.elevationM
                val grade = if (ea != null && eb != null && dk > 0.005) ((eb - ea) / (dk * 1000.0) * 100.0).coerceIn(-20.0, 25.0) else 0.0
                val idx = bins.indexOfFirst { grade >= it.first && grade < it.second }.coerceAtLeast(0)
                val prev = out[idx]
                val power = listOfNotNull(a.riderPowerW, b.riderPowerW).let { if (it.isEmpty()) null else it.average() }?.takeIf { it in 0.0..2000.0 }
                out[idx] = prev.copy(
                    seconds = prev.seconds + dt,
                    speedWeighted = prev.speedWeighted + speed * dt,
                    powerSeconds = prev.powerSeconds + if (power != null) dt else 0.0,
                    powerWeighted = prev.powerWeighted + (power ?: 0.0) * dt
                )
                fitMoving += dt
                fitDistance += dk
            }
            totalMoving += max(fitMoving, analysis.durationSec?.toDouble()?.takeIf { fitMoving <= 1.0 } ?: 0.0)
            totalDistance += max(fitDistance, analysis.distanceKm.takeIf { fitMoving <= 1.0 } ?: 0.0)
        }
        return RoadTrainingProfile(
            fitCount = analyses.size,
            totalDistanceKm = totalDistance,
            totalMovingSec = totalMoving,
            bins = out,
            importedNames = analyses.map { it.displayName }.takeLast(12),
            power = manualPower
        )
    }
}
