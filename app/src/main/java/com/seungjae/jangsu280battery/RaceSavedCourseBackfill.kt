package com.seungjae.jangsu280battery

import android.content.Context
import android.util.Log
import java.util.Collections

/**
 * Uploads RACE courses that were saved on an administrator phone before automatic draft sync existed.
 * Only GPX files created by the RACE course builder are backfilled; ordinary user GPX files are ignored.
 */
object RaceSavedCourseBackfill {
    private const val TAG = "RaceSavedBackfill"
    private val attempted = Collections.synchronizedSet(mutableSetOf<String>())

    fun sync(context: Context) {
        val app = context.applicationContext
        val sync = RiderServerSync(app)
        if (!sync.configured() || !sync.isAdminDeviceCached()) return

        val repo = CourseRepository(app)
        repo.listCourses().filter { !it.builtIn }.forEach { meta ->
            val file = repo.sourceFile(meta.id) ?: return@forEach
            if (!isRaceBuilderFile(file)) return@forEach
            val key = "${sync.serverUrl()}|${meta.id}|${file.length()}|${file.lastModified()}"
            if (!attempted.add(key)) return@forEach

            val gates = runCatching {
                val course = repo.loadCourse(meta.id)
                course.pois.mapNotNull { p ->
                    val type = when (p.type.uppercase()) {
                        "RACE_START" -> "START"
                        "RACE_FINISH" -> "FINISH"
                        "RACE_SECTOR" -> "SECTOR"
                        else -> return@mapNotNull null
                    }
                    val fallback = RaceGateMath.gateAt(course, p.routeKm * 1000.0, p.name.ifBlank { type }, type, 5.0)
                    val bearing = Regex("(?:^|;)bearing=([-+0-9.]+)").find(p.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback.bearingDeg
                    val width = Regex("(?:^|;)width=([-+0-9.]+)").find(p.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback.widthM
                    RaceGate(
                        name = p.name.ifBlank { type },
                        type = type,
                        routeM = p.routeKm * 1000.0,
                        lat = p.lat,
                        lon = p.lon,
                        bearingDeg = bearing,
                        widthM = width.coerceIn(1.0, 20.0)
                    )
                }.sortedBy { it.routeM }
            }.getOrDefault(emptyList())

            RaceCoursePublisher(sync).publishDraftAsync(meta.id, meta.name, file, gates) { result ->
                if (result.ok) {
                    Log.i(TAG, "backfill ok course=${meta.name} server=${result.serverCourseId} ready=${result.raceReady}")
                } else {
                    attempted.remove(key)
                    Log.w(TAG, "backfill failed course=${meta.name}: ${result.message}")
                }
            }
        }
    }

    private fun isRaceBuilderFile(file: java.io.File): Boolean = runCatching {
        file.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = CharArray(4096)
            val n = r.read(buf)
            if (n <= 0) false else String(buf, 0, n).contains("creator=\"Ride Copilot RACE\"")
        }
    }.getOrDefault(false)
}
