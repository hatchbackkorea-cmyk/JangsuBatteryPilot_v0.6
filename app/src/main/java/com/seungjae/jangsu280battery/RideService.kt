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
import kotlin.math.max
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
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_OFF_COURSE_M = "off_course_m"
        const val EXTRA_ACCURACY_M = "accuracy_m"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_COURSE_ELEVATION = "course_elevation"
        const val EXTRA_GPS_ELEVATION = "gps_elevation"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_FREE_ASCENT_M = "free_ascent_m"
        const val EXTRA_VOICE_ENABLED = "voice_enabled"
        const val EXTRA_DISTANCE_INTERVAL_KM = "distance_interval_km"
        const val EXTRA_TIME_INTERVAL_MIN = "time_interval_min"
        const val EXTRA_SPEAK_TEXT = "speak_text"
        const val EXTRA_BLE_SOC = "ble_soc"
        const val EXTRA_BLE_STATE = "ble_state"
        const val EXTRA_BLE_UPDATED_MS = "ble_updated_ms"
        const val EXTRA_ASSIST_PRIMARY = "assist_primary"
        const val EXTRA_ASSIST_ALTERNATE = "assist_alternate"
        const val EXTRA_ASSIST_CONFIDENCE = "assist_confidence"
        const val EXTRA_ASSIST_RAW_CODE = "assist_raw_code"
        const val EXTRA_ASSIST_ACTIVE_MODE = "assist_active_mode"
        const val EXTRA_ASSIST_ACTIVE_CONFIDENCE = "assist_active_confidence"
        const val EXTRA_ASSIST_UPDATED_MS = "assist_updated_ms"

        private const val CHANNEL_ID = "gpx_ride_tracking"
        private const val CHARGE_CHANNEL_ID = "charge_target_alerts"
        private const val NOTIFICATION_ID = 280
        private const val CHARGE_NOTIFICATION_ID = 281

        // v0.29.0 rainy/bad GPS protection. A bad fix is safer to hold than to corrupt
        // route progress and downstream learning data.
        private const val MAX_PLANNED_GPS_ACCURACY_M = 60f
        private const val MAX_REASONABLE_GPS_SPEED_MPS = 25.0 // 90 km/h
        private const val GPS_REJECT_EVENT_INTERVAL_MS = 60_000L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var courseRepo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var matcher: RouteMatcher
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var plan: AdaptiveBatteryPlan
    private lateinit var pacingAdvisor: EnergyPacingAdvisor
    private lateinit var announcer: VoiceAnnouncer
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var chargingStore: ChargingStationStore
    private lateinit var logManager: RideLogManager
    private lateinit var chargingSessionStore: ChargingSessionStore
    private lateinit var replanStore: RideReplanStore
    private lateinit var bleStateStore: AvinoxBleStateStore
    private lateinit var bleClient: AvinoxBleSocClient
    private lateinit var assistProfileStore: AvinoxAssistProfileStore
    private val paceEstimator = PaceEstimator()
    private val passedCheckpointKeys = mutableSetOf<String>()

    private var lastNotificationAt = 0L
    private var lastNotifiedKm = -1
    private var updatesStarted = false
    private var wasOffCourse = false
    private var freeDistanceKm = 0.0
    private var freeAscentM = 0.0
    private var freeLastLocation: Location? = null
    private lateinit var freeAscentEstimator: GpsAscentEstimator
    private var lastBleRecordedSoc: Int? = null
    private var latestBleState: String = "BLE 대기"
    private var latestAssistDetection: AvinoxAssistDetection? = null
    private var latestAssistUpdatedMs: Long = 0L
    private var lastServiceAssistMode: AvinoxAssistMode? = null
    private var lastTrustedPlannedLocation: Location? = null
    private var lastGpsRejectEventAt: Long = 0L
    private var lastGpsGuardNotificationAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        courseRepo = CourseRepository(this)
        courseMeta = courseRepo.activeMeta()
        course = courseRepo.loadCourse(courseMeta.id)
        learningStore = BatteryLearningStore(this)
        chargingStore = ChargingStationStore(this)
        logManager = RideLogManager(this)
        chargingSessionStore = ChargingSessionStore(this)
        replanStore = RideReplanStore(this)
        val prefs = AppSettings.prefs(this)
        val lastKm = prefs.getFloat(AppSettings.KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
        matcher = RouteMatcher(course, lastKm)
        basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
        actualStore = BatteryActualStore(this)
        bleStateStore = AvinoxBleStateStore(this)
        assistProfileStore = AvinoxAssistProfileStore(this)
        lastBleRecordedSoc = actualStore.latest()?.percent?.roundToInt()
        bleClient = AvinoxBleSocClient(this, object : AvinoxBleSocClient.Listener {
            override fun onBleState(state: String, address: String?) {
                latestBleState = state
                if (state.contains("SOC 수신 대기")) logManager.restartAssistProbeWindow()
                broadcastBleState()
            }

            override fun onSoc(soc: Int, timestampMs: Long, address: String?) {
                handleBleSoc(soc, timestampMs, address)
            }

            override fun onRawNotification(timestampMs: Long, bytes: ByteArray, address: String?) {
                logManager.recordRawBleNotification(timestampMs, bytes)
                handleAssistDetection(timestampMs, bytes)
            }
        })
        plan = AdaptiveBatteryPlan(basePlan, actualStore)
        pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
        announcer = VoiceAnnouncer(this).also {
            it.enabled = AppSettings.voiceEnabled(this)
            it.configure(AppSettings.distanceIntervalKm(this), AppSettings.timeIntervalMin(this), lastKm)
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        if (logManager.isFreeRide()) {
            freeDistanceKm = logManager.activeDistanceKm()
            freeAscentM = logManager.activeAscentM()
        }
        freeAscentEstimator = GpsAscentEstimator(freeAscentM)
        val startLabel = if (logManager.isFreeRide()) "임의주행 · GPX 독립 GPS 준비 중" else "GPS 준비 중 · ${courseMeta.name}"
        startForeground(NOTIFICATION_ID, buildNotification(startLabel))
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
                lastTrustedPlannedLocation = null
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
                if (logManager.isFreeRide()) {
                    val actual = actualStore.latest()?.percent
                    val text = if (actual != null) "임의주행 ${String.format(java.util.Locale.US, "%.1f", freeDistanceKm)}킬로미터. 현재 배터리 ${actual.roundToInt()}퍼센트." else "임의주행 ${String.format(java.util.Locale.US, "%.1f", freeDistanceKm)}킬로미터. Avinox BLE 배터리 연결을 기다리는 중입니다."
                    announcer.speakNow(text)
                    return START_STICKY
                }
                val km = matcher.currentKm()
                val battery = plan.estimate(km)
                val cp = plan.currentOrNextCheckpoint(km)
                val stats = course.elevationAhead(km, 10.0)
                val finishTarget = AppSettings.finishTarget(this)
                val reserve = plan.reserveStatus(km, finishTarget)
                val pacing = pacingAdvisor.advice(km, 0.0, reserve)
                announcer.speakNow(announcer.summaryText(km, battery, cp, stats, reserve, pacing))
            }
            ACTION_SPEAK_TEXT -> {
                val text = intent.getStringExtra(EXTRA_SPEAK_TEXT).orEmpty()
                if (text.isNotBlank()) announcer.speakNow(text)
            }
        }
        if (logManager.isActive()) {
            startLocationUpdates()
            bleClient.start()
        }
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (updatesStarted || !hasLocationPermission()) return
        try {
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this, Looper.getMainLooper())
            }
            // v0.29.0: do not mix coarse NETWORK fixes into an outdoor GPS ride.
            // NETWORK is only a fallback when GPS itself is disabled.
            if (!gpsEnabled && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 8f, this, Looper.getMainLooper())
            }
            updatesStarted = true
        } catch (_: SecurityException) { }
    }

    override fun onLocationChanged(location: Location) {
        if (!logManager.isActive()) return
        if (logManager.isFreeRide()) {
            handleFreeLocation(location)
            return
        }
        val rejectReason = plannedGpsRejectReason(location)
        if (rejectReason != null) {
            handleRejectedPlannedLocation(location, rejectReason)
            return
        }
        lastTrustedPlannedLocation = Location(location)
        val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
        val rawMatch = matcher.match(location.latitude, location.longitude, accuracy)
        var emergency = replanStore.active(courseMeta.id)

        // RouteMatcher may intentionally hold progress while it waits for repeated confirmation
        // of a large relocation. Do not write those uncertain points into ride/learning logs.
        if (rawMatch.gpsHeld && emergency == null) {
            handleHeldRouteMatch(location, rawMatch)
            return
        }
        rawMatch.recoveredFromKm?.let { fromKm ->
            logManager.recordEvent(
                "GPS_ROUTE_RECOVERED",
                "GPS 안정 위치 연속 확인 · ${String.format(java.util.Locale.US, "%.1f", fromKm)}km → ${String.format(java.util.Locale.US, "%.1f", rawMatch.routeKm)}km",
                rawMatch.routeKm,
                actualStore.latest()?.percent
            )
        }
        if (emergency != null) {
            replanStore.appendBreadcrumb(courseMeta.id, location.latitude, location.longitude, location.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            if (emergency.phase == EmergencyPhase.RETURN) {
                val anchorDistance = Geo.distanceMeters(location.latitude, location.longitude, emergency.anchorLat, emergency.anchorLon)
                if (anchorDistance <= 50.0) {
                    logManager.recordEvent("EMERGENCY_RETURN_COMPLETE", "원래 이탈점 복귀 완료 · ${anchorDistance.roundToInt()}m", emergency.anchorRouteKm, actualStore.latest()?.percent)
                    matcher.seekToKm(emergency.anchorRouteKm)
                    // 이 GPS 틱은 저장했던 앵커 km로 유지한다. 다음 위치 업데이트부터 정상 RouteMatcher를 재개한다.
                    replanStore.cancelEmergency(courseMeta.id)
                }
            }
        }
        val match = if (emergency != null) {
            val anchor = course.pointAtKm(emergency.anchorRouteKm)
            rawMatch.copy(routeKm = emergency.anchorRouteKm, courseElevationM = anchor.ele)
        } else rawMatch
        val speedKmh = paceEstimator.update(location, match.routeKm)
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
        val pacing = pacingAdvisor.advice(match.routeKm, speedKmh, reserve)
        announcer.handle(match.routeKm, battery, cp, poi, stats10, match.offCourseMeters, reserve, climb, pacing)

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
            putExtra(EXTRA_LAT, location.latitude)
            putExtra(EXTRA_LON, location.longitude)
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

    /**
     * Reject obviously poor or physically impossible planned-ride fixes before RouteMatcher.
     * BLE/SOC collection keeps running because only this location tick is discarded.
     */
    private fun plannedGpsRejectReason(location: Location): String? {
        if (location.hasAccuracy() && location.accuracy > MAX_PLANNED_GPS_ACCURACY_M) {
            return "정확도 ${location.accuracy.roundToInt()}m"
        }

        val fixTime = location.time
        if (fixTime > 0L) {
            val ageMs = System.currentTimeMillis() - fixTime
            if (ageMs > 30_000L) return "오래된 위치 ${ageMs / 1000}s"
        }

        val previous = lastTrustedPlannedLocation ?: return null
        val deltaM = previous.distanceTo(location).toDouble()
        val prevTime = previous.time.takeIf { it > 0L }
        val nowTime = fixTime.takeIf { it > 0L }
        val dtSec = if (prevTime != null && nowTime != null && nowTime > prevTime) {
            (nowTime - prevTime) / 1000.0
        } else 0.0

        val currentAcc = if (location.hasAccuracy()) location.accuracy.toDouble() else 30.0
        val previousAcc = if (previous.hasAccuracy()) previous.accuracy.toDouble() else 30.0
        if (dtSec > 0.0) {
            val rawSpeed = deltaM / dtSec
            val allowedDistance = max(180.0, dtSec * MAX_REASONABLE_GPS_SPEED_MPS + currentAcc + previousAcc + 60.0)
            if (deltaM > allowedDistance && rawSpeed > MAX_REASONABLE_GPS_SPEED_MPS) {
                return "순간이동 ${deltaM.roundToInt()}m/${String.format(java.util.Locale.US, "%.1f", dtSec)}s"
            }
        } else if (deltaM > 250.0) {
            return "시간역전/순간이동 ${deltaM.roundToInt()}m"
        }
        return null
    }

    private fun handleRejectedPlannedLocation(location: Location, reason: String) {
        val now = System.currentTimeMillis()
        val km = matcher.currentKm().coerceIn(0.0, course.totalKm)
        if (now - lastGpsRejectEventAt >= GPS_REJECT_EVENT_INTERVAL_MS) {
            logManager.recordEvent("GPS_FIX_REJECTED", "$reason · 진행도 고정", km, actualStore.latest()?.percent)
            lastGpsRejectEventAt = now
        }
        broadcastGpsGuard(location, km, course.pointAtKm(km).ele, reason)
    }

    private fun handleHeldRouteMatch(location: Location, match: MatchResult) {
        val now = System.currentTimeMillis()
        if (now - lastGpsRejectEventAt >= GPS_REJECT_EVENT_INTERVAL_MS) {
            logManager.recordEvent(
                "GPS_ROUTE_HELD",
                "코스 위치 재확인 중 · 진행도 ${String.format(java.util.Locale.US, "%.1f", match.routeKm)}km 고정",
                match.routeKm,
                actualStore.latest()?.percent
            )
            lastGpsRejectEventAt = now
        }
        broadcastGpsGuard(location, match.routeKm, match.courseElevationM, "코스 위치 재확인")
    }

    private fun broadcastGpsGuard(location: Location, routeKm: Double, courseElevationM: Double, reason: String) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
        sendBroadcast(Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ROUTE_KM, routeKm)
            putExtra(EXTRA_OFF_COURSE_M, 0.0)
            putExtra(EXTRA_ACCURACY_M, accuracy)
            putExtra(EXTRA_SPEED_KMH, 0.0)
            putExtra(EXTRA_COURSE_ELEVATION, courseElevationM)
            putExtra(EXTRA_GPS_ELEVATION, if (location.hasAltitude()) location.altitude else Double.NaN)
            putExtra(EXTRA_PROVIDER, location.provider ?: "GPS")
            // Deliberately omit LAT/LON: UI keeps the last trusted map coordinate.
        })
        val now = System.currentTimeMillis()
        if (now - lastGpsGuardNotificationAt >= 10_000L) {
            val text = "GPS 보호 · $reason · ${String.format(java.util.Locale.US, "%.1f", routeKm)}km 고정"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
            lastGpsGuardNotificationAt = now
        }
    }

    private fun handleFreeLocation(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
        val gpsElevation = if (location.hasAltitude()) location.altitude else Double.NaN
        val previous = freeLastLocation
        if (previous != null) {
            val deltaM = previous.distanceTo(location).toDouble()
            val dtSec = ((location.time.takeIf { it > 0 } ?: System.currentTimeMillis()) - (previous.time.takeIf { it > 0 } ?: System.currentTimeMillis())) / 1000.0
            val plausible = deltaM in 0.5..250.0 && (dtSec <= 0.0 || deltaM / dtSec <= 25.0) && (accuracy < 0f || accuracy <= 60f)
            if (plausible) freeDistanceKm += deltaM / 1000.0
        }
        if (::freeAscentEstimator.isInitialized) {
            freeAscentM = freeAscentEstimator.update(location)
        }
        freeLastLocation = Location(location)
        logManager.updateFreeRideStats(freeDistanceKm, freeAscentM)
        val speedKmh = paceEstimator.update(location, freeDistanceKm)
        val actual = actualStore.latest()?.percent
        logManager.recordLocation(
            timestampMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            gpsElevationM = gpsElevation.takeIf { it.isFinite() },
            speedKmh = speedKmh,
            routeKm = freeDistanceKm,
            offCourseM = 0.0,
            courseElevationM = gpsElevation.takeIf { it.isFinite() } ?: 0.0,
            estimatedBatteryPct = Double.NaN,
            actualBatteryPct = actual
        )
        sendBroadcast(Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ROUTE_KM, freeDistanceKm)
            putExtra(EXTRA_LAT, location.latitude)
            putExtra(EXTRA_LON, location.longitude)
            putExtra(EXTRA_OFF_COURSE_M, 0.0)
            putExtra(EXTRA_ACCURACY_M, accuracy)
            putExtra(EXTRA_SPEED_KMH, speedKmh)
            putExtra(EXTRA_COURSE_ELEVATION, gpsElevation.takeIf { it.isFinite() } ?: 0.0)
            putExtra(EXTRA_GPS_ELEVATION, gpsElevation)
            putExtra(EXTRA_FREE_ASCENT_M, freeAscentM)
            putExtra(EXTRA_PROVIDER, location.provider ?: "GPS")
        })
        val now = System.currentTimeMillis()
        val kmInt = freeDistanceKm.toInt()
        if (kmInt != lastNotifiedKm || now - lastNotificationAt > 15000L) {
            val batText = actual?.let { " · 🔋${it.roundToInt()}%" }.orEmpty()
            val text = "임의주행 ${String.format(java.util.Locale.US, "%.1f", freeDistanceKm)}km · ▲${freeAscentM.roundToInt()}m$batText"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
            lastNotificationAt = now
            lastNotifiedKm = kmInt
        }
    }


    private fun handleAssistDetection(timestampMs: Long, bytes: ByteArray) {
        val detection = AvinoxAssistModeDetector.detect(bytes) ?: return
        latestAssistDetection = detection
        latestAssistUpdatedMs = timestampMs

        val km = if (logManager.isFreeRide()) freeDistanceKm.coerceAtLeast(0.0) else matcher.currentKm().coerceIn(0.0, course.totalKm)

        // 2026-08-27 repeated stationary switching verified byte[68] as the selected mode:
        // 1=ECO, 2=TRAIL, 3=TURBO, 4=AUTO. Never keep AUTO sticky when 2/3 arrives.
        logManager.setDetectedAssistMode(
            assistProfileStore.get(detection.primary), km, actualStore.latest()?.percent, detection.confidence, detection.rawCode
        )
        refreshServiceEnergyModeIfVerified()
        logManager.recordAutoModeDetection(timestampMs, detection, bytes)
        broadcastBleState()
    }

    private fun refreshServiceEnergyModeIfVerified() {
        val mode = logManager.activeAssistMode() ?: return
        val confidence = logManager.activeAssistConfidence()
        if (confidence !in setOf("HIGH", "CONFIRMED") || mode == lastServiceAssistMode) return
        assistProfileStore.setPreferredMode(mode)
        basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
        plan = AdaptiveBatteryPlan(basePlan, actualStore)
        pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
        lastServiceAssistMode = mode
    }

    private fun handleBleSoc(soc: Int, timestampMs: Long, address: String?) {
        if (!logManager.isActive()) return
        bleStateStore.setSoc(soc, "BLE 자동 · 연결됨", address, timestampMs)
        latestBleState = "BLE 자동 · 연결됨"

        // During an explicitly marked charging session, SOC is still displayed live but is not
        // inserted as a RIDING observation. ARRIVAL/POST_CHARGE remain clean paired events.
        val charging = chargingSessionStore.active()
        if (charging == null && soc != lastBleRecordedSoc) {
            val previous = lastBleRecordedSoc
            val km = if (logManager.isFreeRide()) freeDistanceKm.coerceAtLeast(0.0) else matcher.currentKm().coerceIn(0.0, course.totalKm)
            // Unexpected SOC rise while not in an explicit charging session is never accepted as RIDING data.
            // This protects learning from an unmarked charger connection or protocol glitch.
            if (previous != null && soc > previous) {
                latestBleState = "BLE $soc% · 충전 증가 감지 (충전 버튼 확인)"
                logManager.recordEvent("BATTERY_BLE_RISE_BLOCKED", "$previous% → $soc% · charging session 없음", km, soc.toDouble())
            } else {
                actualStore.save(soc.toDouble(), km, ActualEntryKind.RIDING, timestampMs, ActualEntrySource.BLE_AVINOX)
                lastBleRecordedSoc = soc
                logManager.recordEvent("BATTERY_BLE", "$soc% · AVINOX_SOC", km, soc.toDouble())
            }
        } else if (charging != null) {
            // Track the live charging SOC so the first packet after POST_CHARGE is not duplicated as RIDING.
            lastBleRecordedSoc = soc
            maybeAlertChargingTarget(soc, charging)
        }
        broadcastBleState(soc, timestampMs)
    }

    private fun maybeAlertChargingTarget(soc: Int, session: ActiveChargeSession) {
        if (!AppSettings.chargeAlertEnabled(this)) return

        // v0.22.1: "계획"과 "권장"을 분리한다.
        // 계획주행 알림은 사용자가 충전소에 직접 설정한 충전 계획 %에서 울린다.
        // 앱 권장 %는 다음 충전소(없으면 종점)에 설정 잔량을 남기기 위한 최소 권장치이며 알림 목표를 바꾸지 않는다.
        val planned = !logManager.isFreeRide() && session.targetPct != null
        val rawTarget = session.targetPct ?: AppSettings.chargeAlertTarget(this)
        val target = if (planned) rawTarget.coerceIn(1, 100) else rawTarget.coerceIn(50, 100)
        val latest = chargingSessionStore.active() ?: return

        // BLE 재연결이 목표를 건너뛰어 곧바로 100%를 보고해도 알림을 두 번 연속 울리지 않는다.
        if (soc >= 100) {
            if (!latest.targetAlerted) chargingSessionStore.markTargetAlerted()
            val refreshed = chargingSessionStore.active() ?: return
            if (!refreshed.fullAlerted) {
                chargingSessionStore.markFullAlerted()
                val detail = when {
                    target >= 100 && planned -> "내 충전 계획 100%에 도달했습니다."
                    target >= 100 -> "설정한 충전 목표 100%에 도달했습니다."
                    planned -> "내 충전 계획 ${target}%를 지나 100%에 도달했습니다."
                    else -> "설정 목표 ${target}%를 지나 100%에 도달했습니다."
                }
                showChargeAlert("충전 100% 완료", "$detail 충전기는 앱이 제어하지 않습니다.")
                announcer.speakNow("배터리 충전 100퍼센트입니다.")
                logManager.recordEvent("CHARGE_FULL_ALERT", "충전 100% 도달 · 계획/목표 $target%", session.routeKm, 100.0)
            }
            return
        }

        if (!latest.targetAlerted && soc >= target) {
            chargingSessionStore.markTargetAlerted()
            val title = if (planned) "충전 계획 ${target}% 도달" else "충전 목표 ${target}% 도달"
            val body = if (planned) {
                "내가 설정한 충전 계획에 도달했습니다. 앱 권장량은 별도 안내값이며, 충전은 자동으로 멈추지 않고 계속 진행됩니다."
            } else {
                "설정한 충전 알림 목표에 도달했습니다. 충전은 자동으로 멈추지 않고 계속 진행됩니다."
            }
            showChargeAlert(title, body)
            announcer.speakNow("배터리 ${soc}퍼센트. ${if (planned) "충전 계획" else "충전 목표"} ${target}퍼센트에 도달했습니다. 충전은 계속 진행 중입니다.")
            logManager.recordEvent("CHARGE_TARGET_ALERT", "${if (planned) "충전 계획" else "충전 목표"} $target% 도달 · 현재 $soc% · 계속 충전", session.routeKm, soc.toDouble())
        }
    }

    private fun showChargeAlert(title: String, text: String) {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(this, 9, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHARGE_CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_battery_pilot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(CHARGE_NOTIFICATION_ID, notification)
    }

    private fun broadcastBleState(socOverride: Int? = null, updatedOverride: Long? = null) {
        val snap = bleStateStore.snapshot()
        sendBroadcast(Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            socOverride?.let { putExtra(EXTRA_BLE_SOC, it) } ?: snap.soc?.let { putExtra(EXTRA_BLE_SOC, it) }
            putExtra(EXTRA_BLE_STATE, latestBleState.ifBlank { snap.state })
            putExtra(EXTRA_BLE_UPDATED_MS, updatedOverride ?: snap.updatedMs)
            latestAssistDetection?.let { d ->
                putExtra(EXTRA_ASSIST_PRIMARY, d.primary.name)
                d.alternate?.let { putExtra(EXTRA_ASSIST_ALTERNATE, it.name) }
                putExtra(EXTRA_ASSIST_CONFIDENCE, d.confidence)
                putExtra(EXTRA_ASSIST_RAW_CODE, d.rawCode)
                putExtra(EXTRA_ASSIST_UPDATED_MS, latestAssistUpdatedMs)
            }
            logManager.activeAssistMode()?.let { putExtra(EXTRA_ASSIST_ACTIVE_MODE, it.name) }
            putExtra(EXTRA_ASSIST_ACTIVE_CONFIDENCE, logManager.activeAssistConfidence())
        })
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
            .setContentTitle(if (logManager.isFreeRide()) "배터리 코파일럿 · 임의주행" else "GPX 배터리 코파일럿 · ${courseMeta.name}")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "GPS 일시 중지", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "GPX 라이딩 GPS 안내", NotificationManager.IMPORTANCE_LOW).apply {
                description = "화면이 꺼져도 GPS 위치, 배터리 예측, 음성 안내와 주행 로그를 유지합니다."
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
            val chargeChannel = NotificationChannel(CHARGE_CHANNEL_ID, "충전 목표 도달 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "임의주행 설정 목표 또는 계획주행에서 사용자가 정한 충전 계획 SOC에 도달하면 소리와 진동으로 알려줍니다."
                enableVibration(true)
            }
            manager.createNotificationChannel(chargeChannel)
        }
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        if (::bleClient.isInitialized) bleClient.stop()
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
