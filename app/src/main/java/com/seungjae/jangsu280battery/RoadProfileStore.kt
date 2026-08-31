package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

private val ROAD_GRADE_BINS = listOf(-99.0 to -4.0, -4.0 to -1.0, -1.0 to 1.0, 1.0 to 3.0, 3.0 to 6.0, 6.0 to 99.0)

data class RoadPowerProfile(
    val oneMinuteW: Double? = null,
    val fiveMinuteW: Double? = null,
    val twentyMinuteW: Double? = null,
    val sixtyMinuteW: Double? = null
) {
    fun sustainableW(): Double? = sixtyMinuteW?.takeIf { it > 0.0 }
        ?: twentyMinuteW?.takeIf { it > 0.0 }?.times(0.95)
        ?: fiveMinuteW?.takeIf { it > 0.0 }?.times(0.82)
        ?: oneMinuteW?.takeIf { it > 0.0 }?.times(0.58)
}

data class RoadGradeBin(
    val minGrade: Double,
    val maxGrade: Double,
    val seconds: Double,
    val speedWeighted: Double,
    val powerSeconds: Double,
    val powerWeighted: Double
) {
    fun avgSpeedKph(): Double? = if (seconds >= 30.0) speedWeighted / seconds else null
    fun avgPowerW(): Double? = if (powerSeconds >= 30.0) powerWeighted / powerSeconds else null
    fun contains(grade: Double): Boolean = grade >= minGrade && grade < maxGrade
}

data class RoadTrainingProfile(
    val fitCount: Int,
    val totalDistanceKm: Double,
    val totalMovingSec: Double,
    val bins: List<RoadGradeBin>,
    val importedNames: List<String>,
    val power: RoadPowerProfile
) {
    fun overallSpeedKph(): Double? = if (totalMovingSec > 60.0) totalDistanceKm / (totalMovingSec / 3600.0) else null
    fun speedForGrade(grade: Double): Double? = bins.firstOrNull { it.contains(grade) }?.avgSpeedKph()
    fun avgPowerW(): Double? {
        val sec = bins.sumOf { it.powerSeconds }
        return if (sec > 30.0) bins.sumOf { it.powerWeighted } / sec else null
    }
}

class RoadProfileStore(context: Context) {
    companion object {
        private const val PREFS = "road_granfondo_profile_v1"
        private const val KEY = "profile_json"
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): RoadTrainingProfile {
        val raw = prefs.getString(KEY, null) ?: return emptyProfile()
        return runCatching {
            val o = JSONObject(raw)
            val binsJson = o.optJSONArray("bins") ?: JSONArray()
            val bins = (0 until binsJson.length()).map { i ->
                val b = binsJson.getJSONObject(i)
                RoadGradeBin(
                    b.getDouble("min"), b.getDouble("max"), b.optDouble("seconds", 0.0),
                    b.optDouble("speedWeighted", 0.0), b.optDouble("powerSeconds", 0.0), b.optDouble("powerWeighted", 0.0)
                )
            }
            val p = o.optJSONObject("power") ?: JSONObject()
            RoadTrainingProfile(
                fitCount = o.optInt("fitCount", 0),
                totalDistanceKm = o.optDouble("totalDistanceKm", 0.0),
                totalMovingSec = o.optDouble("totalMovingSec", 0.0),
                bins = if (bins.size == ROAD_GRADE_BINS.size) bins else emptyBins(),
                importedNames = (o.optJSONArray("names") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
                power = RoadPowerProfile(
                    p.optNullableDouble("one"), p.optNullableDouble("five"), p.optNullableDouble("twenty"), p.optNullableDouble("sixty")
                )
            )
        }.getOrElse { emptyProfile() }
    }

    fun addFit(analysis: HistoricalRideAnalysis): RoadTrainingProfile {
        val old = load()
        val mutable = old.bins.map { it.copy() }.toMutableList()
        val points = analysis.telemetry
        var movingSec = 0.0
        var distanceKm = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val ta = a.timestampMs ?: continue
            val tb = b.timestampMs ?: continue
            val dt = (tb - ta) / 1000.0
            if (dt !in 0.2..30.0) continue
            val dk = b.routeKm - a.routeKm
            if (dk <= 0.00001 || dk > 0.5) continue
            val speed = listOfNotNull(a.speedKph, b.speedKph).averageOrNull() ?: (dk / (dt / 3600.0))
            if (!speed.isFinite() || speed !in 2.0..100.0) continue
            val ea = a.elevationM
            val eb = b.elevationM
            val grade = if (ea != null && eb != null && dk > 0.005) ((eb - ea) / (dk * 1000.0) * 100.0).coerceIn(-20.0, 25.0) else 0.0
            val idx = ROAD_GRADE_BINS.indexOfFirst { grade >= it.first && grade < it.second }.coerceAtLeast(0)
            val prev = mutable[idx]
            val riderPower = listOfNotNull(a.riderPowerW, b.riderPowerW).averageOrNull()?.takeIf { it in 0.0..2000.0 }
            mutable[idx] = prev.copy(
                seconds = prev.seconds + dt,
                speedWeighted = prev.speedWeighted + speed * dt,
                powerSeconds = prev.powerSeconds + if (riderPower != null) dt else 0.0,
                powerWeighted = prev.powerWeighted + (riderPower ?: 0.0) * dt
            )
            movingSec += dt
            distanceKm += dk
        }
        val nameList = (old.importedNames + analysis.displayName).takeLast(12)
        val next = old.copy(
            fitCount = old.fitCount + 1,
            totalDistanceKm = old.totalDistanceKm + max(distanceKm, analysis.distanceKm.takeIf { movingSec <= 1.0 } ?: 0.0),
            totalMovingSec = old.totalMovingSec + max(movingSec, analysis.durationSec?.toDouble()?.takeIf { movingSec <= 1.0 } ?: 0.0),
            bins = mutable,
            importedNames = nameList
        )
        save(next)
        return next
    }

    fun savePower(power: RoadPowerProfile): RoadTrainingProfile {
        val next = load().copy(power = power)
        save(next)
        return next
    }

    fun clearFits(): RoadTrainingProfile {
        val next = emptyProfile().copy(power = load().power)
        save(next)
        return next
    }

    private fun save(p: RoadTrainingProfile) {
        val o = JSONObject()
            .put("fitCount", p.fitCount)
            .put("totalDistanceKm", p.totalDistanceKm)
            .put("totalMovingSec", p.totalMovingSec)
        val bins = JSONArray()
        p.bins.forEach { b -> bins.put(JSONObject().put("min", b.minGrade).put("max", b.maxGrade).put("seconds", b.seconds).put("speedWeighted", b.speedWeighted).put("powerSeconds", b.powerSeconds).put("powerWeighted", b.powerWeighted)) }
        o.put("bins", bins)
        o.put("names", JSONArray(p.importedNames))
        o.put("power", JSONObject().apply {
            put("one", p.power.oneMinuteW ?: JSONObject.NULL)
            put("five", p.power.fiveMinuteW ?: JSONObject.NULL)
            put("twenty", p.power.twentyMinuteW ?: JSONObject.NULL)
            put("sixty", p.power.sixtyMinuteW ?: JSONObject.NULL)
        })
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private fun emptyProfile() = RoadTrainingProfile(0, 0.0, 0.0, emptyBins(), emptyList(), RoadPowerProfile())
    private fun emptyBins() = ROAD_GRADE_BINS.map { (a, b) -> RoadGradeBin(a, b, 0.0, 0.0, 0.0, 0.0) }
    private fun JSONObject.optNullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() && it > 0.0 }
    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
