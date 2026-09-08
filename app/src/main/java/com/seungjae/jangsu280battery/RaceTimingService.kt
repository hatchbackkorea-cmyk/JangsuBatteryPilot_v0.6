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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground RACE timing owner. Timing continues when the Activity is closed or the screen is off.
 * Swiping the app away still stops the service through AndroidManifest stopWithTask=true.
 * Raw GNSS is journaled locally; FINISH is saved locally before any server upload.
 */
class RaceTimingService : Service(), LocationListener {
    companion object {
        const val ACTION_ARM = "com.seungjae.jangsu280battery.RACE_ARM"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.RACE_STOP"
        const val EXTRA_CONFIG = "race_config_json"
        const val EXTRA_COURSE_ID = "race_course_id"
        private const val CHANNEL = "race_timing"
        private const val NOTIFICATION_ID = 8803

        // START must not be missed just because the timing service received its first fix
        // immediately after the physical gate. Normal line crossing remains authoritative;
        // this window is used only as a guarded fallback.
        private const val START_SEED_MAX_AGE_MS = 8_000L
        private const val START_RECOVERY_MAX_ROUTE_M = 60.0
        private const val START_RECOVERY_MAX_ACCURACY_M = 35.0
        private const val START_RECOVERY_MIN_SPEED_MPS = 1.0
        private const val START_RECOVERY_MIN_PROGRESS_M = 1.0
    }

    private lateinit var locationManager: LocationManager
    private lateinit var store: RaceDataStore
    private lateinit var client: RaceServerClient
    private lateinit var fusion: RaceSensorFusion
    private var wakeLock: PowerManager.WakeLock? = null
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
    private var rejectedCrossingCandidates = 0
    private var lastFusionConfidence = 0.0
    private var lastLiveSendAt = 0L
    private var liveDeltaMs: Long? = null
    private var recoveredStart = false

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

    private fun arm(input: RaceEventConfig, cid: String) {
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
        rejectedCrossingCandidates = 0
        lastFusionConfidence = 0.0
        lastLiveSendAt = 0L

        store.saveActiveConfig(normalized, cid, normalized.reference)
        fusion.start()
        seedPreviousGps(normalized.gates.first())
        writeSnapshot(
            previousRouteM ?: 0.0,
            prev?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble() ?: 0.0,
            null,
            if (normalized.eventCode == "PRACTICE") "연습 · START 자동대기 · 화면 꺼져도 계측" else "START 대기 · GPS+GNSS+IMU 센서융합"
        )
        acquireTimingWakeLock()
        startForeground(NOTIFICATION_ID, notification("START 게이트 대기 · 화면 꺼져도 자동 계측"))
        requestGps()
        Thread { runCatching { client.flushPending() } }.start()
    }

    /**
     * Carry a very recent GPS fix into the timing service. Before this fix, arm() always threw the
     * previous point away, so a rider could physically cross START while the service was switching
     * from the activity/discovery owner to RaceTimingService and the first timing fix would already
     * be inside the course.
     */
    private fun seedPreviousGps(startGate: RaceGate) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val seed = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return
        val ageMs = abs(System.currentTimeMillis() - seed.time)
        if (ageMs > START_SEED_MAX_AGE_MS) return
        val gateDistance = Geo.distanceMeters(seed.latitude, seed.longitude, startGate.lat, startGate.lon)
        if (gateDistance > max(140.0, startGate.widthM * 6.0)) return
        val match = matcher?.match(seed) ?: return
        prev = Location(seed)
        previousRouteM = match.routeM
    }

    private fun recoverIfNeeded() {
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
        rejectedCrossingCandidates = 0
        lastFusionConfidence = 0.0
        recoveredStart = false
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

    override fun onLocationChanged(location: Location) {
        val cfg = config ?: return
        val m = matcher?.match(location) ?: return
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
                val cross = decision.crossingTimeMs
                if (cross != null) {
                    if (gate.type == "FINISH" || nextGateIndex == cfg.gates.lastIndex) finishRun(gate, cross, m.routeM)
                    else recordSector(gate, cross)
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
        startAt = crossAt.coerceAtMost(System.currentTimeMillis())
        lastGateAt = startAt
        nextGateIndex = 1
        liveDeltaMs = referenceDeltaAt(startGate.routeM, 0L)
        referenceSamples.clear()
        referenceSamples += RaceReferencePoint(startGate.routeM, 0L)
        updateNotification(if (recoveredStart) "RUNNING · START 복구 · ${cfg.name}" else "RUNNING · ${cfg.name}")
    }

    /**
     * Guarded recovery for the exact field failure where the first service-owned GPS sample is
     * already just beyond START. It does not widen the gate itself. The rider must be on the course,
     * close to START by route progress, moving forward, and have a usable GPS fix.
     */
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

    private fun finishRun(gate: RaceGate, crossAt: Long, routeM: Double) {
        val cfg = config ?: return
        val loaded = course ?: return
        val finalIdx = sectors.size + 1
        val elapsed = (crossAt - startAt).coerceAtLeast(0L)
        sectors += RaceSectorResult(finalIdx, gate.name.ifBlank { "FINISH" }, (crossAt - lastGateAt).coerceAtLeast(0L), elapsed)
        liveDeltaMs = referenceDeltaAt(gate.routeM, elapsed)
        referenceSamples += RaceReferencePoint(cfg.distanceM.coerceAtLeast(routeM), elapsed)
        val validation = validationStatus()
        val summary = RaceRunSummary(
            runId,
            runNumber,
            cfg.eventCode,
            cfg.name,
            courseId,
            loaded.name,
            startAt,
            crossAt,
            elapsed,
            validation,
            sectors.toList(),
            referenceSamples.distinctBy { it.routeM.toInt() },
            maxSpeedKph,
            maxAccuracyM,
            maxOffRouteM
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
                put("fusion_rejected_candidates", rejectedCrossingCandidates)
                put("start_recovered", recoveredStart)
                put("gnss_satellites_used", sensor.satellitesUsed)
                put("gnss_cn0_dbhz", sensor.averageCn0DbHz)
                put("gnss_constellations", sensor.constellationCount)
                liveDeltaMs?.let { put("previous_delta_ms", it) }
            }
            store.enqueue("FINISH", cfg.eventCode, payload, client.baseUrl())
            Thread { runCatching { client.flushPending() } }.start()
        }
        state = "FINISHED"
        writeSnapshot(
            cfg.distanceM,
            maxAccuracyM,
            liveDeltaMs,
            if (cfg.eventCode == "PRACTICE") "✓ 자동 랩 계측 · 휴대폰 저장 완료"
            else if (recoveredStart) "✓ START 복구 계측 · 휴대폰 저장 완료 · 서버 동기화 중"
            else "✓ 센서융합 계측 · 휴대폰 저장 완료 · 서버 분류/동기화 중"
        )
        store.clearActiveConfig()
        runCatching { locationManager.removeUpdates(this) }
        fusion.stop()
        releaseTimingWakeLock()
        updateNotification("FINISH ${formatRaceTime(elapsed)} · $validation")
        stopForeground(false)
        stopSelf()
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

    private fun validationStatus(): String = when {
        jumpCount > 0 || maxOffRouteM > 120.0 || maxAccuracyM > 100.0 -> "INVALID"
        recoveredStart || maxOffRouteM > 60.0 || maxAccuracyM > 50.0 || weakCrossingCount > 0 -> "REVIEW"
        else -> "VALID"
    }

    private fun writeSnapshot(routeM: Double, accuracy: Double, delta: Long?, serverStatus: String? = null) {
        val cfg = config
        val previous = store.snapshot()
        val elapsed = if (state == "RUNNING" && startAt > 0L) {
            (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
        } else if (state == "FINISHED") previous.elapsedMs else previous.elapsedMs
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
                serverStatus = serverStatus ?: previous.serverStatus
            )
        )
    }

    private fun writeError(message: String) {
        fusion.stop()
        releaseTimingWakeLock()
        store.writeSnapshot(store.snapshot().copy(state = "STOPPED", serverStatus = message))
    }

    private fun stopRace() {
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
        runCatching { locationManager.removeUpdates(this) }
        fusion.stop()
        releaseTimingWakeLock()
        super.onDestroy()
    }
}