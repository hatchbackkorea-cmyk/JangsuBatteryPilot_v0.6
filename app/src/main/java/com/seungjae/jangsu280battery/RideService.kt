package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import kotlin.math.abs
import kotlin.math.roundToInt

class RideService : Service(), LocationListener {
    companion object {
        const val ACTION_UPDATE = "com.seungjae.jangsu280battery.UPDATE"
        const val ACTION_START = "com.seungjae.jangsu280battery.START"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.STOP"
        const val ACTION_RESET = "com.seungjae.jangsu280battery.RESET"
        const val ACTION_SET_VOICE = "com.seungjae.jangsu280battery.SET_VOICE"
        const val ACTION_SET_VOICE_INTERVALS = "com.seungjae.jangsu280battery.SET_VOICE_INTERVALS"
        const val ACTION_SPEAK_NOW = "com.seungjae.jangsu280battery.SPEAK_NOW"
        const val ACTION_SPEAK_TEXT = "com.seungjae.jangsu280battery.SPEAK_TEXT"

        const val EXTRA_ROUTE_KM = "route_km"
        const val EXTRA_OFF_COURSE_M = "off_course_m"
        const val EXTRA_ACCURACY_M = "accuracy_m"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_COURSE_ELEVATION = "course_elevation"
        const val EXTRA_GPS_ELEVATION = "gps_elevation"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_VOICE_ENABLED = "voice_enabled"
        const val EXTRA_DISTANCE_INTERVAL_KM = "distance_interval_km"
        const val EXTRA_TIME_INTERVAL_MIN = "time_interval_min"
        const val EXTRA_SPEAK_TEXT = "speak_text"

        private const val CHANNEL_ID = "gpx_ride_tracking"
        private const val NOTIFICATION_ID = 280
    }

    private lateinit var locationManager: LocationManager
    private lateinit var courseRepo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var matcher: RouteMatcher
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var plan: AdaptiveBatteryPlan
    private lateinit var announcer: VoiceAnnouncer
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var chargingStore: ChargingStationStore
    private lateinit var logManager: RideLogManager
    private val paceEstimator = PaceEstimator()
    private val passedCheckpointKeys = mutableSetOf<String>()

    private var lastNotificationAt = 0L
    private var lastNotifiedKm = -1
    private var updatesStarted = false
    private var wasOffCourse = false

    override fun onCreate() {
        super.onCreate()
        courseRepo = CourseRepository(this)
        courseMeta = courseRepo.activeMeta()
        course = courseRepo.loadCourse(courseMeta.id)
        learningStore = BatteryLearningStore(this)
        chargingStore = ChargingStationStore(this)
        logManager = RideLogManager(this)
        val prefs = AppSettings.prefs(this)
        val lastKm = prefs.getFloat(AppSettings.KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
        matcher = RouteMatcher(course, lastKm)
        basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
        actualStore = BatteryActualStore(this)
        plan = AdaptiveBatteryPlan(basePlan, actualStore)
        announcer = VoiceAnnouncer(this).also {
            it.enabled = AppSettings.voiceEnabled(this)
            it.configure(AppSettings.distanceIntervalKm(this), AppSettings.timeIntervalMin(this), lastKm)
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("GPS 준비 중 · ${courseMeta.name}"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).apply()
                matcher.seekToKm(0.0)
                paceEstimator.reset()
                announcer.reset()
                passedCheckpointKeys.clear()
            }
            ACTION_SET_VOICE -> {
                val enabled = intent.getBooleanExtra(EXTRA_VOICE_ENABLED, true)
                announcer.enabled = enabled
                AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_VOICE, enabled).apply()
            }
            ACTION_SET_VOICE_INTERVALS -> {
                val distanceKm = intent.getIntExtra(EXTRA_DISTANCE_INTERVAL_KM, AppSettings.distanceIntervalKm(this))
                val timeMin = intent.getIntExtra(EXTRA_TIME_INTERVAL_MIN, AppSettings.timeIntervalMin(this))
                announcer.configure(distanceKm, timeMin, matcher.currentKm())
                AppSettings.prefs(this).edit()
                    .putInt(AppSettings.KEY_ANNOUNCE_DISTANCE_KM, distanceKm.coerceIn(0, 50))
                    .putInt(AppSettings.KEY_ANNOUNCE_TIME_MIN, timeMin.coerceIn(0, 120))
                    .apply()
            }
            ACTION_SPEAK_NOW -> {
                val km = matcher.currentKm()
                val battery = plan.estimate(km)
                val cp = plan.currentOrNextCheckpoint(km)
                val stats = course.elevationAhead(km, 10.0)
                val finishTarget = AppSettings.finishTarget(this)
                val reserve = plan.reserveStatus(km, finishTarget)
                announcer.speakNow(announcer.summaryText(km, battery, cp, stats, reserve))
            }
            ACTION_SPEAK_TEXT -> {
                val text = intent.getStringExtra(EXTRA_SPEAK_TEXT).orEmpty()
                if (text.isNotBlank()) announcer.speakNow(text)
            }
        }
        if (logManager.isActive()) startLocationUpdates()
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (updatesStarted || !hasLocationPermission()) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 8f, this, Looper.getMainLooper())
            }
            updatesStarted = true
        } catch (_: SecurityException) { }
    }

    override fun onLocationChanged(location: Location) {
        if (!logManager.isActive()) return
        val match = matcher.match(location.latitude, location.longitude)
        val speedKmh = paceEstimator.update(location, match.routeKm)
        val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
        val gpsElevation = if (location.hasAltitude()) location.altitude else Double.NaN

        val prefs = AppSettings.prefs(this)
        prefs.edit().putFloat(AppSettings.KEY_LAST_KM, match.routeKm.toFloat()).apply()

        basePlan.checkpoints.forEach { cp ->
            if (abs(cp.km - match.routeKm) <= 0.12) {
                val key = "${cp.name}@${String.format(java.util.Locale.US, "%.1f", cp.km)}"
                if (passedCheckpointKeys.add(key)) logManager.recordEvent("CHECKPOINT", cp.name, cp.km, actualStore.latest()?.percent)
            }
        }

        val battery = plan.estimate(match.routeKm)
        val cp = plan.currentOrNextCheckpoint(match.routeKm)
        val poi = course.nextPoi(match.routeKm)
        val stats10 = course.elevationAhead(match.routeKm, 10.0)
        val finishTarget = AppSettings.finishTarget(this)
        val reserve = plan.reserveStatus(match.routeKm, finishTarget)
        val climb = course.nextMajorClimb(match.routeKm)
        announcer.handle(match.routeKm, battery, cp, poi, stats10, match.offCourseMeters, reserve, climb)

        val nowOff = match.offCourseMeters >= 150.0
        if (nowOff != wasOffCourse) {
            logManager.recordEvent(if (nowOff) "OFF_COURSE" else "BACK_ON_COURSE", if (nowOff) "코스 이탈 ${match.offCourseMeters.roundToInt()}m" else "코스 복귀", match.routeKm, actualStore.latest()?.percent)
            wasOffCourse = nowOff
        }

        logManager.recordLocation(
            timestampMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            gpsElevationM = gpsElevation.takeIf { it.isFinite() },
            speedKmh = speedKmh,
            routeKm = match.routeKm,
            offCourseM = match.offCourseMeters,
            courseElevationM = match.courseElevationM,
            estimatedBatteryPct = battery.percent,
            actualBatteryPct = actualStore.latest()?.percent
        )

        sendBroadcast(Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ROUTE_KM, match.routeKm)
            putExtra(EXTRA_OFF_COURSE_M, match.offCourseMeters)
            putExtra(EXTRA_ACCURACY_M, accuracy)
            putExtra(EXTRA_SPEED_KMH, speedKmh)
            putExtra(EXTRA_COURSE_ELEVATION, match.courseElevationM)
            putExtra(EXTRA_GPS_ELEVATION, gpsElevation)
            putExtra(EXTRA_PROVIDER, location.provider ?: "GPS")
        })

        val now = System.currentTimeMillis()
        val kmInt = match.routeKm.toInt()
        if (kmInt != lastNotifiedKm || now - lastNotificationAt > 15000L) {
            val cpText = cp?.let { " · ${it.name} ${String.format(java.util.Locale.US, "%.1f", (it.km - match.routeKm).coerceAtLeast(0.0))}km" }.orEmpty()
            val risk = when (reserve.label) { "위험" -> " ⚠위험"; "주의" -> " ·주의"; else -> "" }
            val text = "${String.format(java.util.Locale.US, "%.1f", match.routeKm)}km · 🔋${battery.percent.roundToInt()}%$risk$cpText"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
            lastNotificationAt = now
            lastNotifiedKm = kmInt
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, RideService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_battery_pilot)
            .setContentTitle("GPX 배터리 코파일럿 · ${courseMeta.name}")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "GPS 일시 중지", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "GPX 라이딩 GPS 안내", NotificationManager.IMPORTANCE_LOW).apply {
                description = "화면이 꺼져도 GPS 위치, 배터리 예측, 음성 안내와 주행 로그를 유지합니다."
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        announcer.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class PaceEstimator {
    private val speeds = ArrayDeque<Double>()
    private var lastRouteKm: Double? = null
    private var lastAtMs: Long? = null

    fun reset() { speeds.clear(); lastRouteKm = null; lastAtMs = null }

    fun update(location: Location, routeKm: Double): Double {
        val now = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        var candidate = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val prevKm = lastRouteKm
        val prevAt = lastAtMs
        if (candidate < 2.0 && prevKm != null && prevAt != null && now > prevAt) {
            val dtHours = (now - prevAt) / 3_600_000.0
            if (dtHours > 0.0) candidate = (routeKm - prevKm).coerceAtLeast(0.0) / dtHours
        }
        lastRouteKm = routeKm
        lastAtMs = now
        if (candidate in 2.0..60.0) {
            speeds.addLast(candidate)
            while (speeds.size > 12) speeds.removeFirst()
        }
        return if (speeds.isEmpty()) 0.0 else speeds.average()
    }
}
