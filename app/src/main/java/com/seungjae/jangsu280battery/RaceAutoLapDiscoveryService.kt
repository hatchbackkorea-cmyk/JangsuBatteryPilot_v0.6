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
import kotlin.math.roundToInt

/**
 * START button's background course watcher.
 *
 * It keeps running with the phone in a pocket. Every stored GPX is considered. Once the rider is
 * close enough to a stored course START, the precise RaceTimingService is armed; the timing service
 * still requires an actual directional gate-line crossing before its clock starts.
 *
 * The watcher intentionally stays alive while a local lap is being timed. After FINISH it keeps the
 * final result visible briefly, then resumes scanning so lap 2/3/... can start automatically without
 * touching the phone again. The same GPS samples query the server (at most once/minute) for released
 * courses whose START is within 10 km and raises a user-confirmable download notification.
 */
class RaceAutoLapDiscoveryService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.seungjae.jangsu280battery.AUTO_LAP_START"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.AUTO_LAP_STOP"
        private const val CHANNEL = "timegate_auto_lap"
        private const val NEARBY_CHANNEL = "timegate_nearby_courses"
        private const val NOTIFICATION_ID = 8844
        private const val NEARBY_NOTIFICATION_ID = 8845
        private const val ARM_RADIUS_M = 600.0
        private const val SERVER_RADIUS_KM = 10.0
        private const val FINISH_HOLD_MS = 3_000L
    }

    private data class Candidate(val meta: CourseMeta, val config: RaceEventConfig, val start: RaceGate)

    private lateinit var locationManager: LocationManager
    private lateinit var repo: CourseRepository
    private lateinit var store: RaceDataStore
    private lateinit var publicClient: PublicCourseClient
    private lateinit var registry: PublicCourseRegistry
    private var wakeLock: PowerManager.WakeLock? = null
    private var candidates: List<Candidate> = emptyList()
    private var lastCandidateRefreshMs = 0L
    private var lastServerQueryMs = 0L
    private var finishHoldUntilMs = 0L
    private var lastFinishedRunId = ""
    @Volatile private var serverQueryRunning = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        repo = CourseRepository(this); store = RaceDataStore(this)
        publicClient = PublicCourseClient(this); registry = PublicCourseRegistry(this)
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWatching()
            ACTION_START, null -> startWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            store.writeSnapshot(store.snapshot().copy(state = "STOPPED", serverStatus = "정확한 위치 권한이 필요합니다."))
            stopSelf(); return
        }
        refreshCandidates(force = true)
        val existing = store.snapshot()
        val timingActive = existing.state == "ARMED" || existing.state == "RUNNING"
        if (!timingActive) {
            store.clearActiveConfig()
            store.writeSnapshot(
                RaceDataStore.Snapshot(
                    state = "WATCHING", eventCode = "PRACTICE", eventName = "자동 랩", courseName = "저장 코스 자동 탐색",
                    serverStatus = if (candidates.isEmpty()) "저장된 GPX 확인 중 · 근처 공개 코스도 탐색합니다." else "저장 코스 ${candidates.size}개 · START 자동 탐색 중"
                )
            )
        }
        acquireWakeLock()
        val notificationText = if (timingActive) "자동 랩 감시 유지 · 현재 계측 중" else "저장 코스 START 자동 탐색 중"
        startForeground(NOTIFICATION_ID, watchNotification(notificationText))
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 1f, this) }
        runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let(::onLocationChanged)
    }

    override fun onLocationChanged(location: Location) {
        val runtime = store.snapshot()
        if (runtime.state == "ARMED" || runtime.state == "RUNNING") {
            val label = if (runtime.state == "RUNNING") "${runtime.courseName.ifBlank { "코스" }} · 랩 계측 중" else "${runtime.courseName.ifBlank { "코스" }} · START 게이트 대기"
            updateWatchNotification(label)
            return
        }

        if (runtime.state == "FINISHED") {
            val now = System.currentTimeMillis()
            if (runtime.runId != lastFinishedRunId) {
                lastFinishedRunId = runtime.runId
                finishHoldUntilMs = now + FINISH_HOLD_MS
            }
            if (now < finishHoldUntilMs) {
                updateWatchNotification("FINISH 기록 저장 완료 · 다음 랩 자동대기 준비")
                return
            }
        }

        refreshCandidates(force = runtime.state == "FINISHED")
        val nearest = candidates.minByOrNull { Geo.distanceMeters(location.latitude, location.longitude, it.start.lat, it.start.lon) }
        val nearestM = nearest?.let { Geo.distanceMeters(location.latitude, location.longitude, it.start.lat, it.start.lon) } ?: Double.POSITIVE_INFINITY
        if (nearest != null && nearestM <= ARM_RADIUS_M) {
            arm(nearest, nearestM)
            return
        }
        val text = when {
            nearest == null -> "저장 GPX 없음 · 공개 코스 탐색 중"
            nearestM < 10_000 -> "${nearest.meta.name} START까지 약 ${formatDistance(nearestM)}"
            else -> "저장 코스 ${candidates.size}개 · START 자동 탐색 중"
        }
        updateWatchNotification(text)
        val old = store.snapshot()
        if (old.state != "WATCHING") {
            store.clearActiveConfig()
            store.writeSnapshot(
                RaceDataStore.Snapshot(
                    state = "WATCHING", eventCode = "PRACTICE", eventName = "자동 랩", courseName = "저장 코스 자동 탐색",
                    gpsAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
                    serverStatus = text
                )
            )
        } else {
            store.writeSnapshot(old.copy(gpsAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0, serverStatus = text))
        }
        queryNearbyServerIfDue(location)
    }

    private fun refreshCandidates(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCandidateRefreshMs < 30_000L) return
        lastCandidateRefreshMs = now
        val runs = store.completed()
        candidates = repo.listCourses().mapNotNull { meta ->
            runCatching {
                val course = repo.loadCourse(meta.id)
                val base = RaceGateMath.practiceConfig(meta.id, course)
                val start = base.gates.firstOrNull { it.type == "START" } ?: return@runCatching null
                if (base.gates.none { it.type == "FINISH" }) return@runCatching null
                val history = runs.filter { it.courseId == meta.id && it.status != "INVALID" }
                val valid = history.filter { it.status == "VALID" }
                val best = (valid.ifEmpty { history }).minByOrNull { it.elapsedMs }
                val reference = best?.reference?.takeIf { it.size >= 2 } ?: emptyList()
                Candidate(meta, base.copy(name = meta.name, courseName = meta.name, reference = reference), start)
            }.getOrNull()
        }
    }

    private fun arm(candidate: Candidate, distanceM: Double) {
        runCatching { repo.setActive(candidate.meta.id) }
        store.saveActiveConfig(candidate.config, candidate.meta.id, candidate.config.reference)
        startForegroundService(Intent(this, RaceTimingService::class.java).apply {
            action = RaceTimingService.ACTION_ARM
            putExtra(RaceTimingService.EXTRA_CONFIG, candidate.config.toJson().toString())
            putExtra(RaceTimingService.EXTRA_COURSE_ID, candidate.meta.id)
        })
        updateWatchNotification("${candidate.meta.name} · START ${formatDistance(distanceM)} · 게이트 통과 대기")
        // Do not stop this watcher. It idles while RaceTimingService is ARMED/RUNNING and
        // automatically resumes course discovery after FINISH for the next lap.
    }

    private fun queryNearbyServerIfDue(location: Location) {
        val now = System.currentTimeMillis()
        if (!publicClient.available() || serverQueryRunning || now - lastServerQueryMs < 60_000L) return
        lastServerQueryMs = now; serverQueryRunning = true
        Thread {
            try {
                val found = publicClient.nearby(location.latitude, location.longitude, SERVER_RADIUS_KM)
                val unresolved = found.filter { registry.resolveExisting(it, repo) == null }
                registry.cacheNearby(unresolved)
                if (unresolved.isNotEmpty()) {
                    val sig = unresolved.joinToString("|") { "${it.serverCourseId}:${it.sha256}" }
                    if (registry.shouldNotify(sig)) {
                        postNearbyNotification(unresolved)
                        registry.markNotified(sig)
                    }
                }
            } catch (_: Exception) {
                // Discovery is optional; local timing must never fail because the server is offline.
            } finally { serverQueryRunning = false }
        }.start()
    }

    private fun postNearbyNotification(items: List<PublicCourseClient.NearbyCourse>) {
        val intent = Intent(this, NearbyCoursePromptActivity::class.java)
        val pi = PendingIntent.getActivity(this, 8845, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val names = items.take(2).joinToString(", ") { it.name } + if (items.size > 2) " 외 ${items.size - 2}개" else ""
        val n = NotificationCompat.Builder(this, NEARBY_CHANNEL)
            .setSmallIcon(R.drawable.ic_battery_pilot)
            .setContentTitle("근처 공개 코스 ${items.size}개")
            .setContentText("$names · 모두 다운로드할까요?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("START 반경 10km에 공개된 코스가 있습니다. $names · 눌러서 다운로드할 코스를 확인하세요."))
            .setAutoCancel(true).setContentIntent(pi).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NEARBY_NOTIFICATION_ID, n)
    }

    private fun watchNotification(text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(this, 8844, Intent(this, RaceActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery_pilot).setContentTitle("TimeGate · 자동 랩")
            .setContentText(text).setOngoing(true).setContentIntent(pi).build()
    }

    private fun updateWatchNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, watchNotification(text))
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "자동 랩 계측", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(NEARBY_CHANNEL, "근처 공개 코스", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AUTO_LAP_DISCOVERY").also { wakeLock = it }
        if (!wl.isHeld) runCatching { wl.acquire() }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) runCatching { it.release() } } }

    private fun stopWatching() {
        runCatching { locationManager.removeUpdates(this) }; releaseWakeLock()
        val s = store.snapshot(); if (s.state == "WATCHING") store.writeSnapshot(s.copy(state = "STOPPED", serverStatus = "자동 랩 탐색 정지"))
        stopForeground(true); stopSelf()
    }

    override fun onDestroy() { runCatching { locationManager.removeUpdates(this) }; releaseWakeLock(); super.onDestroy() }
    private fun formatDistance(m: Double): String = if (m < 1000.0) "${m.roundToInt()}m" else "${"%.1f".format(m / 1000.0)}km"
}
