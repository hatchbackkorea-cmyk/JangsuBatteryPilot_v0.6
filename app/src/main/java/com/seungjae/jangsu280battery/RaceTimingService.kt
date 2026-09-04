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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

/**
 * Foreground RACE timing owner. Timing continues with the screen off and survives Activity recreation.
 * Raw GPS is journaled locally; FINISH is saved locally before any server upload.
 */
class RaceTimingService : Service(), LocationListener {
    companion object {
        const val ACTION_ARM = "com.seungjae.jangsu280battery.RACE_ARM"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.RACE_STOP"
        const val EXTRA_CONFIG = "race_config_json"
        const val EXTRA_COURSE_ID = "race_course_id"
        private const val CHANNEL = "race_timing"
        private const val NOTIFICATION_ID = 8803
    }

    private lateinit var locationManager: LocationManager
    private lateinit var store: RaceDataStore
    private lateinit var client: RaceServerClient
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
    private val sectors = mutableListOf<RaceSectorResult>()
    private val referenceSamples = mutableListOf<RaceReferencePoint>()
    private var lastReferenceM = -1000.0
    private var lastReferenceT = -1000L
    private var maxSpeedKph = 0.0
    private var maxAccuracyM = 0.0
    private var maxOffRouteM = 0.0
    private var jumpCount = 0
    private var lastLiveSendAt = 0L

    override fun onCreate() {
        super.onCreate()
        store = RaceDataStore(this); client = RaceServerClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
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

    private fun arm(input: RaceEventConfig, cid: String) {
        val loaded = runCatching { CourseRepository(this).loadCourse(cid) }.getOrElse {
            writeError("코스를 열 수 없습니다: ${it.message}"); return
        }
        val normalized = RaceGateMath.normalize(input, loaded)
        if (normalized.gates.size < 2) { writeError("START/FINISH 게이트가 없습니다."); return }
        config = normalized; course = loaded; courseId = cid; matcher = RaceRouteMatcher(loaded)
        state = "ARMED"; runId = UUID.randomUUID().toString(); runNumber = store.nextRunNumber(normalized.eventCode)
        startAt = 0L; lastGateAt = 0L; nextGateIndex = 0; prev = null
        sectors.clear(); referenceSamples.clear(); lastReferenceM = -1000.0; lastReferenceT = -1000L
        maxSpeedKph = 0.0; maxAccuracyM = 0.0; maxOffRouteM = 0.0; jumpCount = 0; lastLiveSendAt = 0L
        store.saveActiveConfig(normalized, cid, normalized.reference)
        writeSnapshot(routeM = 0.0, accuracy = 0.0, delta = null, serverStatus = if (normalized.eventCode == "PRACTICE") "연습 · 로컬 기록" else "LIVE 준비")
        startForeground(NOTIFICATION_ID, notification("RACE 준비 · START 게이트를 통과하면 자동 계측"))
        requestGps()
        Thread { runCatching { client.flushPending() } }.start()
    }

    private fun recoverIfNeeded() {
        val snap = store.snapshot()
        if (snap.state != "ARMED" && snap.state != "RUNNING") return
        val active = store.activeConfig() ?: return
        val loaded = runCatching { CourseRepository(this).loadCourse(active.second) }.getOrNull() ?: return
        config = RaceGateMath.normalize(active.first, loaded); course = loaded; courseId = active.second; matcher = RaceRouteMatcher(loaded).apply { reset(snap.routeM) }
        state = snap.state; runId = snap.runId; runNumber = snap.runNumber; startAt = snap.startedAtMs; lastGateAt = snap.lastGateAtMs
        nextGateIndex = snap.nextGateIndex; sectors.clear(); sectors.addAll(snap.sectors)
        maxSpeedKph = snap.maxSpeedKph; maxAccuracyM = snap.maxGpsAccuracyM; maxOffRouteM = snap.maxOffRouteM; jumpCount = snap.jumpCount
        startForeground(NOTIFICATION_ID, notification(if (state == "RUNNING") "RACE 계측 복구 · 기록 중" else "RACE 준비 복구"))
        requestGps()
    }

    private fun requestGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            writeError("정확한 위치 권한이 필요합니다."); return
        }
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this) }
            .onFailure { writeError("GPS 시작 실패: ${it.message}") }
    }

    override fun onLocationChanged(location: Location) {
        val cfg = config ?: return; val m = matcher?.match(location) ?: return
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0
        maxAccuracyM = max(maxAccuracyM, accuracy); maxOffRouteM = max(maxOffRouteM, m.distanceM)
        if (location.hasSpeed()) maxSpeedKph = max(maxSpeedKph, location.speed * 3.6)
        val p = prev
        if (p != null) {
            val dt = (location.time - p.time).coerceAtLeast(1L)
            val jumpM = Geo.distanceMeters(p.latitude, p.longitude, location.latitude, location.longitude)
            if (dt < 2500L && jumpM > 140.0) jumpCount++
        }
        store.appendRaw(runId, location, m.routeM, m.distanceM)

        if (state == "ARMED") {
            val startGate = cfg.gates.first()
            val cross = if (p != null && accuracy <= 70.0) RaceGateMath.crossingTimeMs(p, location, startGate) else null
            if (cross != null) {
                state = "RUNNING"; startAt = cross; lastGateAt = cross; nextGateIndex = 1
                referenceSamples.clear(); referenceSamples += RaceReferencePoint(0.0, 0L)
                updateNotification("RACE RUNNING · ${cfg.name}")
            }
        }

        var delta: Long? = null
        if (state == "RUNNING") {
            val elapsed = (location.time - startAt).coerceAtLeast(0L)
            val refElapsed = RaceGateMath.interpolateReference(cfg.reference, m.routeM)
            if (refElapsed != null) delta = elapsed - refElapsed
            if (m.routeM - lastReferenceM >= 10.0 || elapsed - lastReferenceT >= 1000L) {
                referenceSamples += RaceReferencePoint(m.routeM, elapsed); lastReferenceM = m.routeM; lastReferenceT = elapsed
            }
            if (nextGateIndex in cfg.gates.indices && p != null && accuracy <= 70.0) {
                val gate = cfg.gates[nextGateIndex]
                val cross = RaceGateMath.crossingTimeMs(p, location, gate)
                if (cross != null) {
                    if (gate.type == "FINISH" || nextGateIndex == cfg.gates.lastIndex) finishRun(cross, m.routeM)
                    else recordSector(gate, cross)
                }
            }
            if (state == "RUNNING") sendLiveIfDue(location, m.routeM, delta)
        }
        prev = Location(location)
        if (state == "ARMED" || state == "RUNNING") writeSnapshot(m.routeM, accuracy, delta)
    }

    private fun recordSector(gate: RaceGate, crossAt: Long) {
        val cfg = config ?: return
        val idx = sectors.size + 1
        val result = RaceSectorResult(idx, gate.name.ifBlank { "S$idx" }, (crossAt - lastGateAt).coerceAtLeast(0L), (crossAt - startAt).coerceAtLeast(0L))
        sectors += result; lastGateAt = crossAt; nextGateIndex++
        if (cfg.eventCode != "PRACTICE") {
            val payload = JSONObject().apply {
                put("event_code", cfg.eventCode); put("run_id", runId); put("run_number", runNumber); put("sector_index", idx)
                put("sector_name", result.name); put("sector_ms", result.sectorMs); put("split_ms", result.splitMs); put("crossed_at_ms", crossAt)
            }
            store.enqueue("SECTOR", cfg.eventCode, payload)
            Thread { runCatching { client.flushPending() } }.start()
        }
    }

    private fun finishRun(crossAt: Long, routeM: Double) {
        val cfg = config ?: return; val loaded = course ?: return
        val finalIdx = sectors.size + 1
        sectors += RaceSectorResult(finalIdx, "S$finalIdx", (crossAt - lastGateAt).coerceAtLeast(0L), (crossAt - startAt).coerceAtLeast(0L))
        val elapsed = (crossAt - startAt).coerceAtLeast(0L)
        referenceSamples += RaceReferencePoint(cfg.distanceM.coerceAtLeast(routeM), elapsed)
        val validation = validationStatus()
        val summary = RaceRunSummary(
            runId, runNumber, cfg.eventCode, cfg.name, courseId, loaded.name, startAt, crossAt, elapsed, validation,
            sectors.toList(), referenceSamples.distinctBy { it.routeM.toInt() }, maxSpeedKph, maxAccuracyM, maxOffRouteM
        )
        // The official local result is durable before attempting any network operation.
        store.saveCompleted(summary)
        if (cfg.eventCode != "PRACTICE") {
            val profile = RaceProfileStore.profile(this)
            val payload = summary.toJson().apply {
                put("profile_id", profile.profileId); put("name", profile.name); put("nickname", profile.nickname)
            }
            store.enqueue("FINISH", cfg.eventCode, payload)
            Thread { runCatching { client.flushPending() } }.start()
        }
        state = "FINISHED"
        writeSnapshot(cfg.distanceM, maxAccuracyM, null, serverStatus = if (cfg.eventCode == "PRACTICE") "✓ 휴대폰 저장 완료" else "✓ 휴대폰 저장 완료 · 서버 동기화 중")
        store.clearActiveConfig()
        runCatching { locationManager.removeUpdates(this) }
        updateNotification("FINISH ${formatRaceTime(elapsed)} · $validation")
        stopForeground(false); stopSelf()
    }

    private fun sendLiveIfDue(location: Location, routeM: Double, delta: Long?) {
        val cfg = config ?: return
        if (cfg.eventCode == "PRACTICE") return
        val now = System.currentTimeMillis(); if (now - lastLiveSendAt < 900L) return; lastLiveSendAt = now
        val joined = store.joined(cfg.eventCode) ?: return
        val profile = RaceProfileStore.profile(this)
        val payload = JSONObject().apply {
            put("event_code", cfg.eventCode); put("run_id", runId); put("run_number", runNumber); put("state", state)
            put("profile_id", profile.profileId); put("name", profile.name); put("nickname", profile.nickname)
            put("route_m", routeM); put("elapsed_ms", (location.time - startAt).coerceAtLeast(0L)); put("sector_index", sectors.size + 1)
            put("speed_kph", if (location.hasSpeed()) location.speed * 3.6 else 0.0); put("gps_accuracy_m", if (location.hasAccuracy()) location.accuracy else 99f)
            delta?.let { put("leader_delta_ms", it) }; put("timestamp_ms", location.time)
        }
        Thread { runCatching { client.sendLive(cfg.eventCode, joined.token, payload) } }.start()
    }

    private fun validationStatus(): String = when {
        jumpCount > 0 || maxOffRouteM > 120.0 || maxAccuracyM > 100.0 -> "INVALID"
        maxOffRouteM > 60.0 || maxAccuracyM > 50.0 -> "REVIEW"
        else -> "VALID"
    }

    private fun writeSnapshot(routeM: Double, accuracy: Double, delta: Long?, serverStatus: String? = null) {
        val cfg = config
        val elapsed = if (state == "RUNNING" && startAt > 0L) (System.currentTimeMillis() - startAt).coerceAtLeast(0L) else store.snapshot().elapsedMs
        val currentName = if (cfg != null && nextGateIndex in cfg.gates.indices) cfg.gates[nextGateIndex].name else ""
        store.writeSnapshot(RaceDataStore.Snapshot(
            state = state, eventCode = cfg?.eventCode.orEmpty(), eventName = cfg?.name.orEmpty(), courseId = courseId, courseName = course?.name.orEmpty(),
            runId = runId, runNumber = runNumber, startedAtMs = startAt, lastGateAtMs = lastGateAt, elapsedMs = elapsed,
            routeM = routeM, totalM = cfg?.distanceM ?: 0.0, deltaMs = delta, nextGateIndex = nextGateIndex, currentSector = currentName,
            gpsAccuracyM = accuracy, maxSpeedKph = maxSpeedKph, maxGpsAccuracyM = maxAccuracyM, maxOffRouteM = maxOffRouteM,
            jumpCount = jumpCount, validation = validationStatus(), sectors = sectors.toList(), serverStatus = serverStatus ?: store.snapshot().serverStatus
        ))
    }

    private fun writeError(message: String) {
        store.writeSnapshot(store.snapshot().copy(state = "STOPPED", serverStatus = message))
    }

    private fun stopRace() {
        runCatching { locationManager.removeUpdates(this) }
        state = "STOPPED"; store.writeSnapshot(store.snapshot().copy(state = "STOPPED", serverStatus = "계측 정지")); store.clearActiveConfig()
        stopForeground(true); stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "RACE 계측", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_battery_pilot).setContentTitle("Ride Copilot · RACE").setContentText(text).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 8803, Intent(this, RaceActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }

    override fun onDestroy() { runCatching { locationManager.removeUpdates(this) }; super.onDestroy() }
}
