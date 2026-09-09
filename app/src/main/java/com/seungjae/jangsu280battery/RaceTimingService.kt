package com.seungjae.jangsu280battery

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Foreground owner of TimeGate RACE timing. */
class RaceTimingService : Service(), LocationListener {
    companion object {
        const val ACTION_ARM = "com.seungjae.jangsu280battery.RACE_ARM"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.RACE_STOP"
        const val EXTRA_CONFIG = "race_config_json"
        const val EXTRA_COURSE_ID = "race_course_id"
        private const val CHANNEL = "race_timing"
        private const val NOTIFICATION_ID = 8803

        private const val START_SEED_MAX_AGE_MS = 8_000L
        private const val START_RECOVERY_MAX_ROUTE_M = 60.0
        private const val START_RECOVERY_MAX_ACCURACY_M = 35.0
        private const val START_RECOVERY_MIN_SPEED_MPS = 1.0
        private const val START_RECOVERY_MIN_PROGRESS_M = 1.0

        // START is refined while the rider continues riding. FINISH waits one second so fixes from
        // both sides of the physical line are available before the official lap time is committed.
        private const val START_REFINE_DELAY_MS = 1_000L
        private const val FINISH_REFINE_DELAY_MS = 1_000L
        private const val NEXT_LAP_REARM_DELAY_MS = 1_500L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var store: RaceDataStore
    private lateinit var client: RaceServerClient
    private lateinit var fusion: RaceSensorFusion
    private val timingRefiner = RaceTimingRefiner()
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextLapRunnable: Runnable? = null
    private var finishFinalizeRunnable: Runnable? = null

    private var config: RaceEventConfig? = null
    private var course: CourseData? = null
    private var courseId = ""
    private var matcher: RaceRouteMatcher? = null
    private var state = "STOPPED"
    private var runId = ""
    private var runNumber = 0
    private var startAt = 0L
    private var lastGateAt = 0L
    private var nextGateIndex = 0
    private var prev: Location? = null
    private var previousRouteM: Double? = null
    private val sectors = mutableListOf<RaceSectorResult>()
    private val referenceSamples = mutableListOf<RaceReferencePoint>()
    private var lastReferenceM = -1000.0
    private var lastReferenceT = -1000L
    private var maxSpeedKph = 0.0
    private var maxAccuracyM = 0.0
    private var maxOffRouteM = 0.0
    private var jumpCount = 0
    private var weakCrossingCount = 0
    private var routeRecoveryCount = 0
    private var skippedIntermediateGate = false
    private var rejectedCrossingCandidates = 0
    private var lastFusionConfidence = 0.0
    private var lastLiveSendAt = 0L
    private var liveDeltaMs: Long? = null
    private var recoveredStart = false

    private var preliminaryStartAt = 0L
    private var startRefined = false
    private var startRefinementMs = 0L
    private var startUncertaintyMs: Long? = null
    private var pendingFinishGate: RaceGate? = null
    private var preliminaryFinishAt = 0L
    private var pendingFinishRouteM = 0.0
    private var finishRefinementMs = 0L
    private var timingUncertaintyMs: Long? = null
    private var timingRefinementSamples = 0
    private var timingRefinementMethod = "PAIR_INTERPOLATION"
    private var clockOffsetMs: Long? = null
    private var startAudit: JSONObject? = null
    private var timingRecovered = false
    private var lastFix: Location? = null
    private val finishTail = mutableListOf<Location>()
    private var finishDisplay: RaceDataStore.Snapshot? = null
    private var finishDisplayUntil = 0L
    private fun timingNow(): Long = (clockOffsetMs ?: (System.currentTimeMillis()-SystemClock.elapsedRealtime()))+SystemClock.elapsedRealtime()
    private fun stableLocation(raw: Location): Location {
        if (clockOffsetMs == null) clockOffsetMs = raw.time-raw.elapsedRealtimeNanos/1_000_000L
        return Location(raw).apply { time=clockOffsetMs!!+raw.elapsedRealtimeNanos/1_000_000L }
    }


    override fun onCreate() {
        super.onCreate()
        store = RaceDataStore(this)
        client = RaceServerClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        fusion = RaceSensorFusion(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRace()
            ACTION_ARM -> {
                val raw = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                val cid = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty()
                if (raw.isNotBlank() && cid.isNotBlank()) arm(RaceEventConfig.fromJson(JSONObject(raw)), cid)
            }
            else -> recoverIfNeeded()
        }
        return START_STICKY
    }

    private fun acquireTimingWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:RACE_TIMING").also { wakeLock = it }
        if (!wl.isHeld) runCatching { wl.acquire() }
    }

    private fun releaseTimingWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private fun cancelNextLapRearm() {
        nextLapRunnable?.let(mainHandler::removeCallbacks)
        nextLapRunnable = null
    }

    private fun cancelFinishFinalize() {
        finishFinalizeRunnable?.let(mainHandler::removeCallbacks)
        finishFinalizeRunnable = null
        pendingFinishGate = null
    }

    private fun previousLapReference(cid: String): List<RaceReferencePoint> {
        if (cid.isBlank()) return emptyList()
        return store.completed()
            .asSequence()
            .filter { it.courseId == cid && it.status != "INVALID" && it.reference.size >= 2 }
            .maxByOrNull { it.finishedAtMs }
            ?.reference
            ?.sortedBy { it.routeM }
            ?: emptyList()
    }

    private fun arm(input: RaceEventConfig, cid: String, nextLap: Boolean = false) {
        if (pendingFinishGate != null) finalizePendingFinish(rearm = false)
        if (!nextLap) { clockOffsetMs=null; finishDisplay=null }
        startAudit=null; timingRecovered=false; finishTail.clear()
        cancelNextLapRearm()
        cancelFinishFinalize()
        val loaded = runCatching { CourseRepository(this).loadCourse(cid) }
            .getOrElse { writeError("코스를 열 수 없습니다: ${it.message}"); return }
        val normalizedBase = RaceGateMath.normalize(input, loaded)
        if (normalizedBase.gates.size < 2) {
            writeError("START/FINISH 게이트가 없습니다.")
            return
        }

        val normalized = normalizedBase.copy(reference = previousLapReference(cid))
        config = normalized
        course = loaded
        courseId = cid
        matcher = RaceRouteMatcher(loaded)
        state = "ARMED"
        runId = UUID.randomUUID().toString()
        runNumber = store.nextRunNumber(normalized.eventCode)
        startAt = 0L
        lastGateAt = 0L
        nextGateIndex = 0
        prev = null
        previousRouteM = null
        liveDeltaMs = null
        recoveredStart = false
        sectors.clear()
        referenceSamples.clear()
        lastReferenceM = -1000.0
        lastReferenceT = -1000L
        maxSpeedKph = 0.0
        maxAccuracyM = 0.0
        maxOffRouteM = 0.0
        jumpCount = 0
        weakCrossingCount = 0
        routeRecoveryCount = 0
        skippedIntermediateGate = false
        rejectedCrossingCandidates = 0
        lastFusionConfidence = 0.0
        lastLiveSendAt = 0L
        preliminaryStartAt = 0L
        startRefined = false
        startRefinementMs = 0L
        startUncertaintyMs = null
        preliminaryFinishAt = 0L
        pendingFinishRouteM = 0.0
        finishRefinementMs = 0L
        timingUncertaintyMs = null
        timingRefinementSamples = 0
        timingRefinementMethod = "PAIR_INTERPOLATION"
        timingRefiner.reset()

        store.saveActiveConfig(normalized, cid, normalized.reference)
        fusion.start()
        seedPreviousGps(normalized.gates.first())
        val waitStatus = when {
            nextLap && normalized.eventCode == "PRACTICE" -> "다음 LAP · START 자동대기 · 화면 꺼져도 계측"
            nextLap -> "다음 LAP · START 대기 · GPS+GNSS+IMU 센서융합"
            normalized.eventCode == "PRACTICE" -> "연습 · START 자동대기 · 화면 꺼져도 계측"
            else -> "START 대기 · GPS+GNSS+IMU 센서융합"
        }
        writeSnapshot(
            previousRouteM ?: 0.0,
            prev?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble() ?: 0.0,
            null,
            waitStatus
        )
        acquireTimingWakeLock()
        startForeground(
            NOTIFICATION_ID,
            notification(if (nextLap) "다음 LAP · START 게이트 대기" else "START 게이트 대기 · 화면 꺼져도 자동 계측")
        )
        if (!nextLap) requestGps()
        Thread { runCatching { client.flushPending() } }.start()
    }

    private fun seedPreviousGps(startGate: RaceGate) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val seedRaw = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return
        val seed = stableLocation(seedRaw)
        val ageMs = abs(SystemClock.elapsedRealtime()-seed.elapsedRealtimeNanos/1_000_000L)
        if (ageMs > START_SEED_MAX_AGE_MS) return
        val gateDistance = Geo.distanceMeters(seed.latitude, seed.longitude, startGate.lat, startGate.lon)
        if (gateDistance > max(140.0, startGate.widthM * 6.0)) return
        val match = matcher?.match(seed) ?: return
        prev = Location(seed)
        previousRouteM = match.routeM
        timingRefiner.add(seed, match.routeM)
    }

    private fun cfgJson(): JSONObject = (config?.toJson() ?: JSONObject()).put("local_course_id",courseId)
    private fun recoverPendingFinish(): Boolean {
        val saved=RaceFairTiming.readPending(this) ?: return false
        val c=saved.optJSONObject("config") ?: return false
        val snap=RaceDataStore.Snapshot.fromJson(saved.getJSONObject("snapshot"))
        val cid=c.optString("local_course_id")
        val loaded=runCatching { CourseRepository(this).loadCourse(cid) }.getOrNull() ?: return false
        config=RaceGateMath.normalize(RaceEventConfig.fromJson(c),loaded);course=loaded;courseId=cid
        state="FINISHED";runId=snap.runId;runNumber=snap.runNumber;startAt=snap.startedAtMs;lastGateAt=snap.lastGateAtMs
        preliminaryStartAt=saved.optLong("original_start",startAt);preliminaryFinishAt=saved.optLong("original_finish",startAt+snap.elapsedMs)
        clockOffsetMs=saved.optLong("clock_offset");startAudit=saved.optJSONObject("start_audit");startRefined=true
        sectors.clear();sectors.addAll(snap.sectors);maxAccuracyM=snap.maxGpsAccuracyM;maxOffRouteM=snap.maxOffRouteM
        maxSpeedKph=snap.maxSpeedKph;jumpCount=snap.jumpCount;timingRecovered=true
        pendingFinishGate=config!!.gates.last();pendingFinishRouteM=snap.totalM
        finalizePendingFinish(rearm=false)
        return true
    }
    private fun recoverIfNeeded() {
        if (recoverPendingFinish()) return
        val snap = store.snapshot()
        if (snap.state != "ARMED" && snap.state != "RUNNING") return
        val active = store.activeConfig() ?: return
        val loaded = runCatching { CourseRepository(this).loadCourse(active.second) }.getOrNull() ?: return
        config = RaceGateMath.normalize(active.first, loaded)
        course = loaded
        courseId = active.second
        matcher = RaceRouteMatcher(loaded).apply { reset(snap.routeM) }
        state = snap.state
        runId = snap.runId
        runNumber = snap.runNumber
        startAt = snap.startedAtMs
        if (snap.startedElapsedNs > 0L) clockOffsetMs=startAt-snap.startedElapsedNs/1_000_000L
        preliminaryStartAt = startAt
        timingRecovered = true
        startRefined = true
        lastGateAt = snap.lastGateAtMs
        nextGateIndex = snap.nextGateIndex
        sectors.clear()
        sectors.addAll(snap.sectors)
        maxSpeedKph = snap.maxSpeedKph
        maxAccuracyM = snap.maxGpsAccuracyM
        maxOffRouteM = snap.maxOffRouteM
        jumpCount = snap.jumpCount
        prev = null
        previousRouteM = snap.routeM
        liveDeltaMs = snap.deltaMs
        weakCrossingCount = 0
        routeRecoveryCount = 0
        skippedIntermediateGate = false
        rejectedCrossingCandidates = 0
        lastFusionConfidence = 0.0
        recoveredStart = false
        timingRefiner.reset()
        fusion.start()
        if (state == "ARMED") config?.gates?.firstOrNull()?.let(::seedPreviousGps)
        acquireTimingWakeLock()
        startForeground(
            NOTIFICATION_ID,
            notification(if (state == "RUNNING") "RACE 계측 복구 · 기록 중" else "START 게이트 대기 복구")
        )
        requestGps()
    }

    private fun requestGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            writeError("정확한 위치 권한이 필요합니다.")
            return
        }
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this) }
            .onFailure { writeError("GPS 시작 실패: ${it.message}") }
    }

    override fun onLocationChanged(rawLocation: Location) {
        if (rawLocation.elapsedRealtimeNanos <= 0L) return
        val location = stableLocation(rawLocation)
        if (lastFix != null && location.elapsedRealtimeNanos <= lastFix!!.elapsedRealtimeNanos) return
        lastFix = Location(location)
        val cfg = config ?: return
        val m = matcher?.match(location) ?: return
        timingRefiner.add(location, m.routeM)
        if (state == "FINISHED" && pendingFinishGate != null) {
            finishTail.add(Location(location))
            store.appendRaw(runId, location, m.routeM, m.distanceM)
            return
        }
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0
        maxAccuracyM = max(maxAccuracyM, accuracy)
        maxOffRouteM = max(maxOffRouteM, m.distanceM)
        if (location.hasSpeed()) maxSpeedKph = max(maxSpeedKph, location.speed * 3.6)

        val p = prev
        val priorRouteM = previousRouteM
        if (p != null) {
            val dt = (location.time - p.time).coerceAtLeast(1L)
            val jumpM = Geo.distanceMeters(p.latitude, p.longitude, location.latitude, location.longitude)
            if (dt < 2500L && jumpM > 140.0) jumpCount++
        }
        store.appendRaw(runId, location, m.routeM, m.distanceM)

        if (state == "ARMED") {
            val startGate = cfg.gates.first()
            var crossAt: Long? = null
            if (p != null) {
                val decision = fusion.evaluateCrossing(p, location, startGate, priorRouteM, m.routeM)
                accountFusionDecision(decision)
                crossAt = decision.crossingTimeMs
            }
            if (crossAt == null && shouldRecoverMissedStart(location, m, startGate, priorRouteM)) {
                crossAt = estimateRecoveredStartTime(location, m, startGate, priorRouteM)
                recoveredStart = true
                weakCrossingCount++
                lastFusionConfidence = max(lastFusionConfidence, 0.55)
            }
            if (crossAt != null) beginRun(startGate, crossAt)
        }

        if (state == "RUNNING") {
            maybeRefineStart(location.time)
            val elapsed = (location.time - startAt).coerceAtLeast(0L)
            liveDeltaMs = referenceDeltaAt(m.routeM, elapsed)
            if (m.routeM - lastReferenceM >= 10.0 || elapsed - lastReferenceT >= 1000L) {
                referenceSamples += RaceReferencePoint(m.routeM, elapsed)
                lastReferenceM = m.routeM
                lastReferenceT = elapsed
            }

            if (nextGateIndex in cfg.gates.indices && p != null) {
                val gate = cfg.gates[nextGateIndex]
                val decision = fusion.evaluateCrossing(p, location, gate, priorRouteM, m.routeM)
                accountFusionDecision(decision)
                var cross = decision.crossingTimeMs
                var routeRecovered = false
                if (cross == null) {
                    cross = routeProgressCrossingTime(p, location, gate, priorRouteM, m.routeM)
                    routeRecovered = cross != null
                }
                if (cross != null) {
                    if (routeRecovered) {
                        routeRecoveryCount++
                        lastFusionConfidence = max(lastFusionConfidence, 0.55)
                    }
                    if (gate.type == "FINISH" || nextGateIndex == cfg.gates.lastIndex) finishRun(gate, cross, m.routeM)
                    else recordSector(gate, cross)
                }
            }

            if (state == "RUNNING" && p != null) {
                val finishIndex = cfg.gates.indexOfLast { it.type.equals("FINISH", ignoreCase = true) }
                    .let { if (it >= 0) it else cfg.gates.lastIndex }
                val finishGate = cfg.gates.getOrNull(finishIndex)
                if (finishGate != null && nextGateIndex < finishIndex && m.routeM >= finishGate.routeM - 45.0) {
                    val finishDecision = fusion.evaluateCrossing(p, location, finishGate, priorRouteM, m.routeM)
                    accountFusionDecision(finishDecision)
                    var finishCross = finishDecision.crossingTimeMs
                    var routeRecovered = false
                    if (finishCross == null) {
                        finishCross = routeProgressCrossingTime(p, location, finishGate, priorRouteM, m.routeM)
                        routeRecovered = finishCross != null
                    }
                    if (finishCross != null) {
                        if (routeRecovered) {
                            routeRecoveryCount++
                            lastFusionConfidence = max(lastFusionConfidence, 0.55)
                        }
                        skippedIntermediateGate = true
                        finishRun(finishGate, finishCross, m.routeM)
                    }
                }
            }

            if (state == "RUNNING") sendLiveIfDue(location, m.routeM, liveDeltaMs)
        }

        prev = Location(location)
        previousRouteM = m.routeM
        if (state == "ARMED" || state == "RUNNING") {
            writeSnapshot(
                m.routeM,
                accuracy,
                liveDeltaMs,
                if (recoveredStart && state == "RUNNING") "START 교차 샘플 복구 · 계측 중" else null
            )
        }
    }

    private fun beginRun(startGate: RaceGate, crossAt: Long) {
        val cfg = config ?: return
        state = "RUNNING"
        startAt = crossAt.coerceAtMost(timingNow())
        preliminaryStartAt = startAt
        startRefined = false
        lastGateAt = startAt
        nextGateIndex = 1
        liveDeltaMs = referenceDeltaAt(startGate.routeM, 0L)
        referenceSamples.clear()
        referenceSamples += RaceReferencePoint(startGate.routeM, 0L)
        writeSnapshot(
            startGate.routeM,
            prev?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble() ?: 0.0,
            liveDeltaMs,
            if (recoveredStart) "START 복구 · 계측 중" else "계측 중 · START 정밀보정 대기"
        )
        updateNotification(if (recoveredStart) "RUNNING · START 복구 · ${cfg.name}" else "RUNNING · ${cfg.name}")
        if (cfg.eventCode != "PRACTICE") {
            store.enqueue("START",cfg.eventCode,JSONObject().apply {
                put("event_code",cfg.eventCode);put("run_id",runId);put("run_number",runNumber)
                put("started_at_ms",startAt);put("timestamp_ms",startAt);put("elapsed_ms",0)
                put("state","RUNNING");put("route_m",startGate.routeM);put("fair_policy",runCatching{JSONObject(cfg.fairPolicyJson)}.getOrDefault(JSONObject()))
            },client.baseUrl())
        }
    }

    private fun maybeRefineStart(nowEpochMs: Long) {
        if (startRefined || preliminaryStartAt <= 0L || nowEpochMs < preliminaryStartAt + START_REFINE_DELAY_MS) return
        val gate = config?.gates?.firstOrNull() ?: return
        val result = timingRefiner.refine(gate, preliminaryStartAt)
        startRefined = true
        if (result == null) return
        startAudit = result.audit
        val oldStart = startAt
        val refined = result.epochMs.coerceAtMost(nowEpochMs)
        val delta = refined - oldStart
        if (abs(delta) > 1_500L) return
        startAt = refined
        startRefinementMs = delta
        startUncertaintyMs = result.uncertaintyMs

        if (sectors.isEmpty()) {
            lastGateAt = refined
        } else {
            val adjusted = sectors.mapIndexed { i, s ->
                s.copy(
                    sectorMs = if (i == 0) (s.sectorMs - delta).coerceAtLeast(0L) else s.sectorMs,
                    splitMs = (s.splitMs - delta).coerceAtLeast(0L)
                )
            }
            sectors.clear(); sectors.addAll(adjusted)
        }
        if (referenceSamples.isNotEmpty()) {
            val adjusted = referenceSamples.mapIndexed { i, p ->
                if (i == 0) RaceReferencePoint(p.routeM, 0L)
                else RaceReferencePoint(p.routeM, (p.elapsedMs - delta).coerceAtLeast(0L))
            }
            referenceSamples.clear(); referenceSamples.addAll(adjusted)
        }
        if (lastReferenceT >= 0L) lastReferenceT = (lastReferenceT - delta).coerceAtLeast(0L)
    }

    private fun shouldRecoverMissedStart(
        location: Location,
        match: RaceRouteMatcher.Match,
        gate: RaceGate,
        priorRouteM: Double?
    ): Boolean {
        val prior = priorRouteM ?: return false
        val afterGateM = match.routeM - gate.routeM
        if (afterGateM < 0.0 || afterGateM > START_RECOVERY_MAX_ROUTE_M) return false
        val allowedOffRoute = max(15.0, gate.widthM / 2.0 + 5.0)
        if (match.distanceM > allowedOffRoute) return false
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0
        if (accuracy > START_RECOVERY_MAX_ACCURACY_M) return false
        if (!location.hasSpeed() || location.speed < START_RECOVERY_MIN_SPEED_MPS) return false
        if (match.routeM - prior < START_RECOVERY_MIN_PROGRESS_M) return false
        if (location.hasBearing() && location.speed >= 1.5f && bearingDelta(location.bearing.toDouble(), gate.bearingDeg) > 90.0) return false
        return true
    }

    private fun estimateRecoveredStartTime(
        location: Location,
        match: RaceRouteMatcher.Match,
        gate: RaceGate,
        priorRouteM: Double?
    ): Long {
        val prior = priorRouteM
        val current = match.routeM
        val p = prev
        if (p != null && prior != null && current > prior && prior <= gate.routeM) {
            val f = ((gate.routeM - prior) / (current - prior)).coerceIn(0.0, 1.0)
            return p.time + ((location.time - p.time) * f).toLong()
        }
        val speed = if (location.hasSpeed()) location.speed.toDouble().coerceAtLeast(START_RECOVERY_MIN_SPEED_MPS) else START_RECOVERY_MIN_SPEED_MPS
        val backMs = (((current - gate.routeM).coerceAtLeast(0.0) / speed) * 1000.0).toLong().coerceAtMost(8_000L)
        return (location.time - backMs).coerceAtMost(location.time)
    }

    private fun routeProgressCrossingTime(
        previous: Location,
        current: Location,
        gate: RaceGate,
        priorRouteM: Double?,
        currentRouteM: Double
    ): Long? {
        val prior = priorRouteM ?: return null
        val progress = currentRouteM - prior
        if (progress <= 0.25) return null
        if (prior > gate.routeM || currentRouteM < gate.routeM) return null

        val previousDistance = Geo.distanceMeters(previous.latitude, previous.longitude, gate.lat, gate.lon)
        val currentDistance = Geo.distanceMeters(current.latitude, current.longitude, gate.lat, gate.lon)
        val worstAccuracy = max(
            if (previous.hasAccuracy()) previous.accuracy.toDouble() else 99.0,
            if (current.hasAccuracy()) current.accuracy.toDouble() else 99.0
        )
        if (worstAccuracy > 70.0) return null
        val allowedDistance = max(18.0, gate.widthM / 2.0 + worstAccuracy + 5.0).coerceAtMost(45.0)
        if (minOf(previousDistance, currentDistance) > allowedDistance) return null
        if (current.hasBearing() && current.hasSpeed() && current.speed >= 1.5f &&
            bearingDelta(current.bearing.toDouble(), gate.bearingDeg) > 100.0
        ) return null

        val f = ((gate.routeM - prior) / progress).coerceIn(0.0, 1.0)
        return previous.time + ((current.time - previous.time) * f).toLong()
    }

    private fun bearingDelta(a: Double, b: Double): Double =
        abs((((a - b) + 540.0) % 360.0) - 180.0).coerceIn(0.0, 180.0)

    private fun accountFusionDecision(decision: RaceSensorFusion.Decision) {
        if (!decision.candidate) return
        if (!decision.accepted) rejectedCrossingCandidates++
        else {
            lastFusionConfidence = decision.confidence
            if (decision.weak) weakCrossingCount++
        }
    }

    private fun referenceDeltaAt(routeM: Double, elapsedMs: Long): Long? {
        val cfg = config ?: return null
        val ref = RaceGateMath.interpolateReference(cfg.reference, routeM) ?: return null
        return elapsedMs - ref
    }

    private fun recordSector(gate: RaceGate, crossAt: Long) {
        val cfg = config ?: return
        val idx = sectors.size + 1
        val split = (crossAt - startAt).coerceAtLeast(0L)
        val result = RaceSectorResult(idx, gate.name.ifBlank { "CP$idx" }, (crossAt - lastGateAt).coerceAtLeast(0L), split)
        sectors += result
        lastGateAt = crossAt
        nextGateIndex++
        liveDeltaMs = referenceDeltaAt(gate.routeM, split)
        if (cfg.eventCode != "PRACTICE") {
            val payload = JSONObject().apply {
                put("event_code", cfg.eventCode)
                put("run_id", runId)
                put("run_number", runNumber)
                put("started_at_ms", startAt)
                put("sector_index", idx)
                put("sector_name", result.name)
                put("sector_ms", result.sectorMs)
                put("split_ms", result.splitMs)
                put("crossed_at_ms", crossAt)
                put("fusion_confidence", lastFusionConfidence)
                liveDeltaMs?.let { put("previous_delta_ms", it) }
            }
            store.enqueue("SECTOR", cfg.eventCode, payload, client.baseUrl())
            Thread { runCatching { client.flushPending() } }.start()
        }
    }

    /**
     * FINISH detection is provisional. Keep GPS running for one second and show "랩타임 확인중".
     * The final record is written only after multi-fix route/speed refinement has completed.
     */
    private fun finishRun(gate: RaceGate, crossAt: Long, routeM: Double) {
        if (state != "RUNNING") return
        maybeRefineStart(crossAt)
        val preliminaryElapsed = (crossAt - startAt).coerceAtLeast(0L)
        val finalIdx = sectors.size + 1
        sectors += RaceSectorResult(
            finalIdx,
            gate.name.ifBlank { "FINISH" },
            (crossAt - lastGateAt).coerceAtLeast(0L),
            preliminaryElapsed
        )
        state = "FINISHED"
        pendingFinishGate = gate
        preliminaryFinishAt = crossAt
        pendingFinishRouteM = routeM
        finishTail.clear()
        prev?.let { finishTail.add(Location(it)) }
        lastFix?.let { finishTail.add(Location(it)) }
        liveDeltaMs = referenceDeltaAt(gate.routeM, preliminaryElapsed)
        writeSnapshot(
            config?.distanceM ?: routeM,
            maxAccuracyM,
            liveDeltaMs,
            "랩타임 확인중 · 1초 GPS 궤적 정밀보정"
        )
        updateNotification("FINISH · 랩타임 확인중")
        RaceFairTiming.savePending(this, JSONObject().apply {
            put("config", cfgJson());put("snapshot", store.snapshot().toJson())
            put("original_start", preliminaryStartAt);put("original_finish",preliminaryFinishAt)
            put("start_audit",startAudit ?: JSONObject.NULL);put("clock_offset",clockOffsetMs ?: 0L)
        })

        val task = Runnable {
            if (state != "FINISHED" || pendingFinishGate == null) return@Runnable
            finalizePendingFinish()
        }
        finishFinalizeRunnable = task
        mainHandler.postDelayed(task, FINISH_REFINE_DELAY_MS)
    }

    private fun finalizePendingFinish(rearm: Boolean = true) {
        val cfg = config ?: return
        val loaded = course ?: return
        val gate = pendingFinishGate ?: return
        val finishedCourseId = courseId
        maybeRefineStart(preliminaryFinishAt + FINISH_REFINE_DELAY_MS)

        val refined = timingRefiner.refine(gate, preliminaryFinishAt)
        val candidateFinish = refined?.epochMs ?: preliminaryFinishAt
        val finalCrossAt = candidateFinish.coerceAtLeast(startAt + 1L)
        finishRefinementMs = finalCrossAt - preliminaryFinishAt
        timingRefinementSamples = refined?.sampleCount ?: 2
        timingRefinementMethod = refined?.method ?: "PAIR_INTERPOLATION"
        val finishUncertainty = refined?.uncertaintyMs
        timingUncertaintyMs = if (startUncertaintyMs != null && finishUncertainty != null) startUncertaintyMs!! + finishUncertainty else null

        val elapsed = (finalCrossAt - startAt).coerceAtLeast(0L)
        if (sectors.isNotEmpty()) {
            val last = sectors.last()
            sectors[sectors.lastIndex] = last.copy(
                sectorMs = (finalCrossAt - lastGateAt).coerceAtLeast(0L),
                splitMs = elapsed
            )
        }
        liveDeltaMs = referenceDeltaAt(gate.routeM, elapsed)
        referenceSamples += RaceReferencePoint(cfg.distanceM.coerceAtLeast(pendingFinishRouteM), elapsed)

        val validation = validationStatus()
        val reasons = validationReasons()
        val timingAudit = RaceFairTiming.audit(cfg, startAudit, refined?.audit, preliminaryStartAt, preliminaryFinishAt, reasons)
        val summary = RaceRunSummary(
            runId,
            runNumber,
            cfg.eventCode,
            cfg.name,
            finishedCourseId,
            loaded.name,
            startAt,
            finalCrossAt,
            elapsed,
            validation,
            sectors.toList(),
            referenceSamples.distinctBy { it.routeM.toInt() },
            maxSpeedKph,
            maxAccuracyM,
            maxOffRouteM,
            timingJson = timingAudit.toString()
        )
        store.saveCompleted(summary)

        if (cfg.eventCode != "PRACTICE") {
            val profile = RaceProfileStore.profile(this)
            val sensor = fusion.snapshot()
            val payload = summary.toJson().apply {
                put("profile_id", profile.profileId)
                put("name", profile.name)
                put("nickname", profile.nickname)
                put("sensor_fusion", true)
                put("fusion_confidence", lastFusionConfidence)
                put("fusion_weak_crossings", weakCrossingCount)
                put("route_recovery_crossings", routeRecoveryCount)
                put("fusion_rejected_candidates", rejectedCrossingCandidates)
                put("start_recovered", recoveredStart)
                put("skipped_intermediate_gate", skippedIntermediateGate)
                put("validation_reason", reasons.joinToString(","))
                put("validation_reasons", JSONArray().apply { reasons.forEach { put(it) } })
                put("gnss_satellites_used", sensor.satellitesUsed)
                put("gnss_cn0_dbhz", sensor.averageCn0DbHz)
                put("gnss_constellations", sensor.constellationCount)
                put("timing_refined", refined != null || startRefinementMs != 0L)
                put("timing_refinement_method", timingRefinementMethod)
                put("timing_refinement_samples", timingRefinementSamples)
                put("timing_uncertainty_ms", timingUncertaintyMs)
                put("start_refinement_ms", startRefinementMs)
                put("finish_refinement_ms", finishRefinementMs)
                put("preliminary_finish_at_ms", preliminaryFinishAt)
                liveDeltaMs?.let { put("previous_delta_ms", it) }
            }
            store.enqueue("FINISH", cfg.eventCode, payload, client.baseUrl())
            Thread { runCatching { client.flushPending() } }.start()
        }

        val finishStatus = buildString {
            append("✓ 랩타임 확정 · ").append(formatRaceTime(elapsed))
            append(" · 정밀보정 ")
            if (finishRefinementMs == 0L && startRefinementMs == 0L) append("유지")
            else append("적용")
            append(if (timingAudit.optString("quality") == "ACCEPTED") " · 계측기준 충족(시범)" else " · 계측 검토")
            if (timingUncertaintyMs == null) append(" · 여유폭 판단 불가")
            else append(" · 판정여유폭 ±").append(timingUncertaintyMs).append("ms(잠정)")
            if (cfg.eventCode != "PRACTICE") append(" · 서버 동기화")
            append(" · 다음 LAP 자동 준비 중")
        }
        writeSnapshot(cfg.distanceM, maxAccuracyM, liveDeltaMs, finishStatus)

        finishFinalizeRunnable?.let(mainHandler::removeCallbacks)
        finishFinalizeRunnable = null
        pendingFinishGate = null
        RaceFairTiming.clearPending(this)
        updateNotification("FINISH ${formatRaceTime(elapsed)} · 기록 저장")
        if (rearm) {
            val tail = finishTail.map { Location(it) }
            finishDisplay = store.snapshot()
            finishDisplayUntil = SystemClock.elapsedRealtime()+NEXT_LAP_REARM_DELAY_MS
            arm(cfg, finishedCourseId, nextLap = true)
            prev=null;previousRouteM=null;lastFix=null;matcher=RaceRouteMatcher(loaded);timingRefiner.reset()
            val startGate=cfg.gates.first()
            val shared=Geo.distanceMeters(startGate.lat,startGate.lon,gate.lat,gate.lon)<=1.0 && bearingDelta(startGate.bearingDeg,gate.bearingDeg)<=15.0
            if(shared){
                beginRun(startGate,finalCrossAt)
                startRefined=true;startUncertaintyMs=finishUncertainty
                startAudit=refined?.audit?.let { JSONObject(it.toString()).put("gate",startGate.toJson()) }
            }
            tail.filter { !shared || it.time > finalCrossAt }.distinctBy { it.elapsedRealtimeNanos }.sortedBy { it.elapsedRealtimeNanos }.forEach { onLocationChanged(it) }
        }
    }

    private fun sendLiveIfDue(location: Location, routeM: Double, delta: Long?) {
        val cfg = config ?: return
        if (cfg.eventCode == "PRACTICE") return
        val now = System.currentTimeMillis()
        if (now - lastLiveSendAt < 900L) return
        lastLiveSendAt = now
        val joined = store.joined(cfg.eventCode, client.baseUrl()) ?: return
        val profile = RaceProfileStore.profile(this)
        val sensor = fusion.snapshot()
        val payload = JSONObject().apply {
            put("event_code", cfg.eventCode)
            put("run_id", runId)
            put("run_number", runNumber)
            put("state", state)
            put("started_at_ms", startAt)
            put("profile_id", profile.profileId)
            put("name", profile.name)
            put("nickname", profile.nickname)
            put("route_m", routeM)
            put("elapsed_ms", (location.time - startAt).coerceAtLeast(0L))
            put("sector_index", sectors.size + 1)
            put("speed_kph", if (location.hasSpeed()) location.speed * 3.6 else 0.0)
            put("gps_accuracy_m", if (location.hasAccuracy()) location.accuracy else 99f)
            delta?.let { put("previous_delta_ms", it) }
            put("timestamp_ms", location.time)
            put("sensor_fusion", true)
            put("start_recovered", recoveredStart)
            put("gnss_satellites_used", sensor.satellitesUsed)
            put("gnss_cn0_dbhz", sensor.averageCn0DbHz)
            put("imu_available", sensor.imuAvailable)
        }
        Thread { runCatching { client.sendLive(cfg.eventCode, joined.token, payload) } }.start()
    }

    private fun validationReasons(): List<String> = buildList {
        if (timingRecovered) add("TIMING_RECOVERY")
        if (jumpCount > 0) add("GPS_JUMP")
        if (maxOffRouteM > 120.0) add("OFF_ROUTE_SEVERE") else if (maxOffRouteM > 60.0) add("OFF_ROUTE")
        if (maxAccuracyM > 100.0) add("GPS_ACCURACY_SEVERE") else if (maxAccuracyM > 50.0) add("GPS_ACCURACY")
        if (recoveredStart) add("START_RECOVERED")
        if (weakCrossingCount > 0) add("FUSION_WEAK")
        if (skippedIntermediateGate) add("CP_SKIPPED")
    }

    private fun validationStatus(): String = when {
        jumpCount > 0 || maxOffRouteM > 120.0 || maxAccuracyM > 100.0 -> "INVALID"
        recoveredStart || maxOffRouteM > 60.0 || maxAccuracyM > 50.0 || weakCrossingCount > 0 || skippedIntermediateGate -> "REVIEW"
        else -> "VALID"
    }

    private fun writeSnapshot(routeM: Double, accuracy: Double, delta: Long?, serverStatus: String? = null) {
        val cfg = config
        if (state == "ARMED" && finishDisplay != null && SystemClock.elapsedRealtime()<finishDisplayUntil) {
            store.writeSnapshot(finishDisplay!!);return
        }
        val previous = store.snapshot()
        val elapsed = if (state == "RUNNING" && startAt > 0L) {
            (timingNow() - startAt).coerceAtLeast(0L)
        } else previous.elapsedMs
        val currentName = if (cfg != null && nextGateIndex in cfg.gates.indices) cfg.gates[nextGateIndex].name else ""
        val finalElapsed = if (state == "FINISHED" && startAt > 0L && sectors.isNotEmpty()) sectors.last().splitMs else elapsed
        store.writeSnapshot(
            RaceDataStore.Snapshot(
                state = state,
                eventCode = cfg?.eventCode.orEmpty(),
                eventName = cfg?.name.orEmpty(),
                courseId = courseId,
                courseName = course?.name.orEmpty(),
                runId = runId,
                runNumber = runNumber,
                startedAtMs = startAt,
                startedElapsedNs = if (startAt > 0L && clockOffsetMs != null) (startAt-clockOffsetMs!!)*1_000_000L else 0L,
                lastGateAtMs = lastGateAt,
                elapsedMs = finalElapsed,
                routeM = routeM,
                totalM = cfg?.distanceM ?: 0.0,
                deltaMs = delta,
                nextGateIndex = nextGateIndex,
                currentSector = currentName,
                gpsAccuracyM = accuracy,
                maxSpeedKph = maxSpeedKph,
                maxGpsAccuracyM = maxAccuracyM,
                maxOffRouteM = maxOffRouteM,
                jumpCount = jumpCount,
                validation = validationStatus(),
                sectors = sectors.toList(),
                serverStatus = serverStatus ?: previous.serverStatus.takeIf { previous.state == state }.orEmpty()
            )
        )
    }

    private fun writeError(message: String) {
        cancelNextLapRearm()
        cancelFinishFinalize()
        fusion.stop()
        releaseTimingWakeLock()
        store.writeSnapshot(store.snapshot().copy(state = "STOPPED", serverStatus = message))
    }

    private fun stopRace() {
        if (pendingFinishGate != null) finalizePendingFinish(rearm = false)
        cancelNextLapRearm()
        cancelFinishFinalize()
        runCatching { locationManager.removeUpdates(this) }
        fusion.stop()
        releaseTimingWakeLock()
        state = "STOPPED"
        store.writeSnapshot(store.snapshot().copy(state = "STOPPED", serverStatus = "계측 정지"))
        store.clearActiveConfig()
        stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "RACE 계측", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_battery_pilot)
        .setContentTitle("TimeGate · RACE")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                8803,
                Intent(this, RaceActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        if (pendingFinishGate != null) finalizePendingFinish(rearm = false)
        cancelNextLapRearm()
        cancelFinishFinalize()
        runCatching { locationManager.removeUpdates(this) }
        fusion.stop()
        releaseTimingWakeLock()
        super.onDestroy()
    }
}
