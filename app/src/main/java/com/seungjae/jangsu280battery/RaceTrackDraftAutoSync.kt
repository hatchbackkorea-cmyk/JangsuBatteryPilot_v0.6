package com.seungjae.jangsu280battery

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.WeakHashMap

/**
 * Keeps an administrator phone's active RACE course draft visible in Rider Control Center.
 *
 * The draft is uploaded even when START/FINISH is incomplete. The PC can therefore add/fix traps
 * before the course is applied to an event. Uploads are debounced and only happen when GPS/traps
 * actually changed.
 */
object RaceTrackDraftAutoSync {
    private const val TAG = "RaceDraftAutoSync"
    private const val PERIOD_MS = 4_000L

    private val handlers = WeakHashMap<RaceTrackBuilderActivity, Handler>()
    private val runnables = WeakHashMap<RaceTrackBuilderActivity, Runnable>()
    private val lastSuccess = WeakHashMap<RaceTrackBuilderActivity, String>()
    private val inFlight = WeakHashMap<RaceTrackBuilderActivity, Boolean>()

    fun install(activity: RaceTrackBuilderActivity) {
        if (runnables.containsKey(activity)) return
        val handler = Handler(Looper.getMainLooper())
        val task = object : Runnable {
            override fun run() {
                trySync(activity)
                handler.postDelayed(this, PERIOD_MS)
            }
        }
        handlers[activity] = handler
        runnables[activity] = task
        handler.postDelayed(task, 800L)
    }

    fun pause(activity: RaceTrackBuilderActivity) {
        val h = handlers.remove(activity)
        val r = runnables.remove(activity)
        if (h != null && r != null) h.removeCallbacks(r)
        inFlight.remove(activity)
    }

    private fun trySync(activity: RaceTrackBuilderActivity) {
        if (inFlight[activity] == true) return
        val sync = RiderServerSync(activity)
        if (!sync.configured() || !sync.isAdminDeviceCached()) return
        val store = RaceTrackDraftStore(activity)
        val draft = store.active() ?: return
        val points = store.points(draft.id)
        if (points.size < 2 || (points.lastOrNull()?.routeM ?: 0.0) < 10.0) return
        val gates = store.traps(draft.id)
        val signature = buildString {
            append(draft.id).append('|').append(points.size).append('|')
            append(points.last().timeMs).append('|').append(points.last().routeM.toLong()).append('|')
            gates.sortedBy { it.routeM }.forEach { append(it.toJson().toString()).append(';') }
        }
        if (signature == lastSuccess[activity]) return

        val file = File(activity.cacheDir, "race_draft_sync_${draft.id}.gpx")
        runCatching { writeGpx(file, draft.name, points, gates) }.onFailure {
            Log.w(TAG, "draft GPX build failed", it)
            return
        }
        inFlight[activity] = true
        RaceCoursePublisher(sync).publishDraftAsync(draft.id, draft.name, file, gates) { result ->
            inFlight[activity] = false
            if (result.ok) {
                lastSuccess[activity] = signature
                Log.i(TAG, "PC sync ok course=${result.serverCourseId} ready=${result.raceReady}")
            } else {
                Log.w(TAG, result.message)
            }
            runCatching { file.delete() }
        }
    }

    private fun writeGpx(
        target: File,
        name: String,
        points: List<RaceTrackDraftStore.Point>,
        gates: List<RaceGate>
    ) {
        target.bufferedWriter(Charsets.UTF_8).use { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.append("<gpx version=\"1.1\" creator=\"Ride Copilot RACE Draft Sync\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.append("<metadata><name>${xml(name)}</name><desc>administrator_phone_draft=true</desc></metadata>\n")
            gates.sortedBy { it.routeM }.forEach { g ->
                val type = when (g.type.uppercase()) {
                    "START" -> "RACE_START"
                    "FINISH" -> "RACE_FINISH"
                    else -> "RACE_SECTOR"
                }
                w.append("<wpt lat=\"${fmt(g.lat)}\" lon=\"${fmt(g.lon)}\"><name>${xml(g.name)}</name><desc>bearing=${fmt(g.bearingDeg)};width=${fmt(g.widthM)};route_m=${fmt(g.routeM)}</desc><type>$type</type></wpt>\n")
            }
            w.append("<trk><name>${xml(name)}</name><trkseg>\n")
            points.forEach { p ->
                w.append("<trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lon)}\"><ele>${fmt(p.ele)}</ele><time>${Instant.ofEpochMilli(p.timeMs).toString()}</time></trkpt>\n")
            }
            w.append("</trkseg></trk></gpx>\n")
        }
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    private fun fmt(v: Double) = String.format(Locale.US, "%.7f", v)
}
