package com.seungjae.jangsu280battery

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * v0.33.3 MTB Smart GPS.
 *
 * This view deliberately separates navigation display smoothing from the trusted RideService
 * logging pipeline. Raw ride/learning data is never rewritten here.
 *
 * Display-only improvements:
 * - direct phone GPS (5 Hz request / 1 Hz selectable)
 * - GPX snap when the fix is plausibly close to the selected route
 * - rotation-vector sensor + GPS course + GPX heading fusion
 * - heading dead-band / hysteresis so small forest-GPS noise does not spin the map
 * - implausible GPS jump rejection for the visible navigation marker
 * - rider triangle is always fixed toward the exact top-centre of the screen while the map rotates
 */
@SuppressLint("SetJavaScriptEnabled")
class RideSmartMapWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs), LocationListener, SensorEventListener {

    private val courseRepo = CourseRepository(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var pageReady = false
    private var mapVerified = false
    private var receiverRegistered = false
    private var directGpsStarted = false
    private var sensorsStarted = false
    private var activeCourseId: String? = null
    private var activeCourse: CourseData? = null

    private var latestRouteKm = 0.0
    private var latestSpeedKmh = 0.0
    private var latestDisplayLat = Double.NaN
    private var latestDisplayLon = Double.NaN
    private var latestAccuracyM = -1f
    private var lastAcceptedLocation: Location? = null
    private var lastSnapKm: Double? = null
    private var isSnapped = false
    private var rejectedJumpCount = 0

    private var sensorTrueHeading: Double? = null
    private var latestGpsHeading: Double? = null
    private var fusedHeading: Double? = null
    private var lastSensorPushMs = 0L

    private var gpsRateHz = if (prefs.getInt(KEY_GPS_RATE_HZ, 5) == 1) 1 else 5
    private var lastFixElapsedMs = 0L
    private var actualHz = 0.0

    private val gpsBridge = object {
        @JavascriptInterface
        fun toggleGpsRate() {
            post {
                gpsRateHz = if (gpsRateHz == 5) 1 else 5
                prefs.edit().putInt(KEY_GPS_RATE_HZ, gpsRateHz).apply()
                restartDirectGps()
                pushGpsRateToPage()
                updateStatusText()
            }
        }
    }

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE) return
            latestRouteKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestRouteKm)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)

            // RideService remains the trusted route-progress source. Direct GPS is only the visual
            // high-rate layer. If direct GPS cannot run, fall back to the service coordinates.
            if (!directGpsStarted && intent.hasExtra(RideService.EXTRA_LAT) && intent.hasExtra(RideService.EXTRA_LON)) {
                val lat = intent.getDoubleExtra(RideService.EXTRA_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(RideService.EXTRA_LON, Double.NaN)
                if (lat.isFinite() && lon.isFinite()) {
                    latestDisplayLat = lat
                    latestDisplayLon = lon
                    val heading = routeHeadingAt(latestRouteKm) ?: fusedHeading ?: 0.0
                    fusedHeading = stabilizeHeading(heading, latestSpeedKmh)
                    pushSmartLocation(lat, lon, latestRouteKm, latestSpeedKmh, fusedHeading ?: 0.0, latestAccuracyM, false)
                }
            }
        }
    }

    init {
        tag = TAG_SMART_MAP
        alpha = 0f
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(gpsBridge, "RiderGps")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                mapVerified = false
                refreshCourse(force = true)
                pushGpsRateToPage()
                verifyInteractiveMap(0)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageReady = false
                    mapVerified = false
                    alpha = 0f
                    updateStatus("스마트 GPS 지도 연결 실패 · 기존 지도 사용")
                }
            }
        }

        loadDataWithBaseURL("https://ride-copilot.local/", HTML, "text/html", "UTF-8", null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerRideReceiver()
        startSensors()
        startDirectGps()
        post { refreshCourse(false) }
    }

    override fun onDetachedFromWindow() {
        stopDirectGps()
        stopSensors()
        unregisterRideReceiver()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            post {
                refreshCourse(false)
                if (!sensorsStarted) startSensors()
                if (!directGpsStarted) startDirectGps()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startDirectGps() {
        if (directGpsStarted || !isAttachedToWindow || !hasLocationPermission()) return
        try {
            val intervalMs = if (gpsRateHz == 5) 200L else 1000L
            when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this, Looper.getMainLooper())
                    directGpsStarted = true
                    primeLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                    directGpsStarted = true
                    primeLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }
        } catch (_: Exception) {
            directGpsStarted = false
        }
        updateStatusText()
    }

    private fun stopDirectGps() {
        if (!directGpsStarted) return
        runCatching { locationManager.removeUpdates(this) }
        directGpsStarted = false
    }

    private fun restartDirectGps() {
        stopDirectGps()
        lastFixElapsedMs = 0L
        actualHz = 0.0
        startDirectGps()
    }

    private fun primeLastKnownLocation(provider: String) {
        try {
            val last = locationManager.getLastKnownLocation(provider) ?: return
            val ageMs = System.currentTimeMillis() - last.time
            if (ageMs in 0..120_000L) onLocationChanged(last)
        } catch (_: SecurityException) {
        }
    }

    private fun startSensors() {
        if (sensorsStarted || rotationSensor == null || !isAttachedToWindow) return
        sensorsStarted = sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSensors() {
        if (!sensorsStarted) return
        sensorManager.unregisterListener(this)
        sensorsStarted = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        runCatching {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
        }.getOrElse { return }

        var heading = Math.toDegrees(orientation[0].toDouble())
        heading = normalizeAngle(heading)

        val accepted = lastAcceptedLocation
        if (accepted != null) {
            val field = GeomagneticField(
                accepted.latitude.toFloat(),
                accepted.longitude.toFloat(),
                (if (accepted.hasAltitude()) accepted.altitude else 0.0).toFloat(),
                System.currentTimeMillis()
            )
            heading = normalizeAngle(heading + field.declination)
        }
        sensorTrueHeading = heading

        // Sensor updates are intentionally throttled. They fill the visual gap when the phone
        // only supplies ~1 Hz GPS, but never spin the map for tiny handlebar vibrations.
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorPushMs < 120L || !latestDisplayLat.isFinite() || !latestDisplayLon.isFinite()) return
        lastSensorPushMs = now

        val candidate = fusedHeadingCandidate(latestSpeedKmh)
        if (candidate != null) {
            val stable = stabilizeHeading(candidate, latestSpeedKmh)
            fusedHeading = stable
            pushHeading(stable)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (location.hasAccuracy() && location.accuracy > 100f) return
        val ageMs = System.currentTimeMillis() - location.time
        if (location.time > 0L && ageMs > 20_000L) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastFixElapsedMs > 0L && nowElapsed > lastFixElapsedMs) {
            val hz = 1000.0 / (nowElapsed - lastFixElapsedMs).toDouble()
            if (hz in 0.05..20.0) actualHz = if (actualHz <= 0.0) hz else actualHz * 0.80 + hz * 0.20
        }
        lastFixElapsedMs = nowElapsed

        if (isImplausibleJump(location)) {
            rejectedJumpCount++
            updateStatusText()
            return
        }

        val previousAccepted = lastAcceptedLocation
        lastAcceptedLocation = Location(location)
        latestAccuracyM = if (location.hasAccuracy()) location.accuracy else -1f
        if (location.hasSpeed()) latestSpeedKmh = (location.speed * 3.6).coerceIn(0.0, 120.0)

        latestGpsHeading = when {
            location.hasBearing() && latestSpeedKmh >= 5.0 -> normalizeAngle(location.bearing.toDouble())
            previousAccepted != null && previousAccepted.distanceTo(location) >= 3.0f -> normalizeAngle(previousAccepted.bearingTo(location).toDouble())
            else -> latestGpsHeading
        }

        var displayLat = location.latitude
        var displayLon = location.longitude
        var snapped = false
        val course = activeCourse
        if (course != null) {
            val match = runCatching { course.nearestRouteLocation(location.latitude, location.longitude) }.getOrNull()
            if (match != null) {
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 20.0
                val snapLimit = (accuracy * 1.6 + 8.0).coerceIn(16.0, 55.0)
                val priorSnap = lastSnapKm
                val routeJumpOk = priorSnap == null || abs(match.routeKm - priorSnap) <= routeContinuityLimitKm(previousAccepted, location)
                if (match.distanceM <= snapLimit && routeJumpOk) {
                    latestRouteKm = match.routeKm
                    lastSnapKm = match.routeKm
                    displayLat = match.trackLat
                    displayLon = match.trackLon
                    snapped = true
                } else if (match.distanceM <= 250.0 && priorSnap == null) {
                    latestRouteKm = match.routeKm
                }
            }
        }
        isSnapped = snapped
        latestDisplayLat = displayLat
        latestDisplayLon = displayLon

        val candidate = fusedHeadingCandidate(latestSpeedKmh)
            ?: routeHeadingAt(latestRouteKm)
            ?: latestGpsHeading
            ?: sensorTrueHeading
            ?: fusedHeading
            ?: 0.0
        val stableHeading = stabilizeHeading(candidate, latestSpeedKmh)
        fusedHeading = stableHeading

        pushSmartLocation(displayLat, displayLon, latestRouteKm, latestSpeedKmh, stableHeading, latestAccuracyM, snapped)
        pushGpsRateToPage()
        updateStatusText()
    }

    private fun isImplausibleJump(location: Location): Boolean {
        val prev = lastAcceptedLocation ?: return false
        val dtSec = when {
            location.elapsedRealtimeNanos > 0L && prev.elapsedRealtimeNanos > 0L && location.elapsedRealtimeNanos > prev.elapsedRealtimeNanos ->
                (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            location.time > prev.time -> (location.time - prev.time) / 1000.0
            else -> 0.0
        }
        if (dtSec <= 0.0 || dtSec > 15.0) return false
        val distance = prev.distanceTo(location).toDouble()
        val currentAcc = if (location.hasAccuracy()) location.accuracy.toDouble() else 25.0
        val prevAcc = if (prev.hasAccuracy()) prev.accuracy.toDouble() else 25.0
        val allowed = max(70.0, dtSec * 25.0 + currentAcc + prevAcc + 25.0)
        return distance > allowed
    }

    private fun routeContinuityLimitKm(prev: Location?, current: Location): Double {
        if (prev == null) return 999.0
        val dtSec = when {
            current.elapsedRealtimeNanos > prev.elapsedRealtimeNanos && prev.elapsedRealtimeNanos > 0L ->
                (current.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            current.time > prev.time -> (current.time - prev.time) / 1000.0
            else -> 1.0
        }.coerceIn(0.2, 10.0)
        val physicalKm = max(8.0, latestSpeedKmh) / 3600.0 * dtSec
        return (physicalKm + 0.45).coerceIn(0.55, 1.8)
    }

    private fun routeHeadingAt(km: Double): Double? {
        val course = activeCourse ?: return null
        if (course.track.size < 2) return null
        val look = when {
            latestSpeedKmh >= 30 -> 0.12
            latestSpeedKmh >= 15 -> 0.08
            else -> 0.05
        }
        val a = course.pointAtKm((km - look * 0.25).coerceAtLeast(0.0))
        val b = course.pointAtKm((km + look).coerceAtMost(course.totalKm))
        if (Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon) < 4.0) return null
        return bearing(a.lat, a.lon, b.lat, b.lon)
    }

    private fun fusedHeadingCandidate(speedKmh: Double): Double? {
        val route = routeHeadingAt(latestRouteKm)
        val gps = latestGpsHeading
        val sensor = sensorTrueHeading
        val weighted = mutableListOf<Pair<Double, Double>>()
        when {
            speedKmh >= 12.0 -> {
                gps?.let { weighted += it to 0.52 }
                route?.let { weighted += it to 0.35 }
                sensor?.let { weighted += it to 0.13 }
            }
            speedKmh >= 5.0 -> {
                gps?.let { weighted += it to 0.38 }
                route?.let { weighted += it to 0.42 }
                sensor?.let { weighted += it to 0.20 }
            }
            else -> {
                route?.let { weighted += it to 0.52 }
                sensor?.let { weighted += it to 0.48 }
            }
        }
        return circularAverage(weighted)
    }

    private fun stabilizeHeading(candidate: Double, speedKmh: Double): Double {
        val previous = fusedHeading ?: return normalizeAngle(candidate)
        val delta = shortestDelta(previous, candidate)
        val magnitude = abs(delta)
        val deadBand = when {
            speedKmh < 4.0 -> 9.0
            speedKmh < 12.0 -> 6.0
            else -> 4.0
        }
        if (magnitude <= deadBand) return previous

        val alpha = when {
            magnitude >= 70.0 -> 0.48
            magnitude >= 35.0 -> 0.34
            magnitude >= 18.0 -> 0.23
            speedKmh < 5.0 -> 0.12
            else -> 0.18
        }
        return normalizeAngle(previous + delta * alpha)
    }

    private fun circularAverage(values: List<Pair<Double, Double>>): Double? {
        if (values.isEmpty()) return null
        var sx = 0.0
        var sy = 0.0
        var total = 0.0
        values.forEach { (angle, weight) ->
            val rad = Math.toRadians(angle)
            sx += cos(rad) * weight
            sy += sin(rad) * weight
            total += weight
        }
        if (total <= 0.0 || (abs(sx) < 0.000001 && abs(sy) < 0.000001)) return null
        return normalizeAngle(Math.toDegrees(atan2(sy, sx)))
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = Location("a").apply { latitude = lat1; longitude = lon1 }
        val b = Location("b").apply { latitude = lat2; longitude = lon2 }
        return normalizeAngle(a.bearingTo(b).toDouble())
    }

    private fun normalizeAngle(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun shortestDelta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun registerRideReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(RideService.ACTION_UPDATE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(rideReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(rideReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    private fun unregisterRideReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(rideReceiver) }
        receiverRegistered = false
    }

    private fun verifyInteractiveMap(attempt: Int) {
        if (!pageReady || !isAttachedToWindow) return
        evaluateJavascript("window.rcMapReady===true") { raw ->
            if (raw == "true") {
                if (!mapVerified) {
                    mapVerified = true
                    animate().alpha(1f).setDuration(160L).start()
                    updateStatusText()
                }
            } else if (attempt < 12) {
                postDelayed({ verifyInteractiveMap(attempt + 1) }, 500L)
            } else {
                mapVerified = false
                alpha = 0f
                updateStatus("스마트 GPS 지도 로딩 실패 · 기존 지도 사용")
            }
        }
    }

    private fun refreshCourse(force: Boolean) {
        if (!pageReady) return
        val meta = runCatching { courseRepo.activeMeta() }.getOrNull() ?: return
        if (!force && activeCourseId == meta.id) return
        val course = runCatching { courseRepo.loadCourse(meta.id) }.getOrNull() ?: return
        activeCourseId = meta.id
        activeCourse = course
        lastSnapKm = null

        val points = downsample(course.track, MAX_ROUTE_POINTS)
        val routeJson = JSONArray()
        points.forEach { p ->
            routeJson.put(JSONArray().apply {
                put(p.lat)
                put(p.lon)
                put(p.routeKm)
            })
        }
        val nameJson = org.json.JSONObject.quote(meta.name)
        evaluateJavascript("window.rcSetRoute && window.rcSetRoute(${routeJson},$nameJson);", null)
        updateStatusText()
    }

    private fun pushSmartLocation(
        lat: Double,
        lon: Double,
        km: Double,
        speedKmh: Double,
        heading: Double,
        accuracyM: Float,
        snapped: Boolean
    ) {
        if (!pageReady || !lat.isFinite() || !lon.isFinite()) return
        val js = String.format(
            Locale.US,
            "window.rcSetSmartLocation && window.rcSetSmartLocation(%.7f,%.7f,%.3f,%.2f,%.2f,%.1f,%s);",
            lat,
            lon,
            km.coerceAtLeast(0.0),
            speedKmh.coerceIn(0.0, 120.0),
            normalizeAngle(heading),
            accuracyM.toDouble(),
            if (snapped) "true" else "false"
        )
        evaluateJavascript(js, null)
    }

    private fun pushHeading(heading: Double) {
        if (!pageReady || !mapVerified) return
        evaluateJavascript(String.format(Locale.US, "window.rcSetHeading && window.rcSetHeading(%.2f);", normalizeAngle(heading)), null)
    }

    private fun pushGpsRateToPage() {
        if (!pageReady) return
        val hz = if (actualHz > 0.0) actualHz.coerceAtMost(20.0) else 0.0
        evaluateJavascript(String.format(Locale.US, "window.rcSetGpsRate && window.rcSetGpsRate(%d,%.2f);", gpsRateHz, hz), null)
    }

    private fun updateStatusText() {
        val rate = if (actualHz > 0.0) "${String.format(Locale.KOREA, "%.1f", actualHz)}Hz" else "대기"
        val gps = if (latestDisplayLat.isFinite()) "현재위치" else "위치잡는중"
        val snap = if (isSnapped) "GPX 보정" else "원 GPS"
        val sensor = if (rotationSensor != null) "센서융합" else "GPS방향"
        val jump = if (rejectedJumpCount > 0) " · 튐차단 $rejectedJumpCount" else ""
        updateStatus("스마트GPS · $gps · $snap · $sensor · 실제 $rate$jump")
    }

    private fun updateStatus(text: String) {
        post { rootView.findViewById<TextView?>(R.id.tvRideMapPreviewStatus)?.text = text }
    }

    private fun downsample(input: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
        if (input.size <= maxPoints) return input
        val out = ArrayList<TrackPoint>(maxPoints + 1)
        val step = (input.size - 1).toDouble() / (maxPoints - 1).toDouble()
        for (i in 0 until maxPoints) out += input[(i * step).toInt().coerceIn(input.indices)]
        if (out.lastOrNull() !== input.lastOrNull()) out += input.last()
        return out
    }

    companion object {
        const val TAG_SMART_MAP = "ride_smart_map_v0333"
        private const val PREFS = "ride_live_map_gps"
        private const val KEY_GPS_RATE_HZ = "gps_rate_hz"
        private const val MAX_ROUTE_POINTS = 900

        private val HTML = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
              <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" />
              <style>
                html,body{width:100%;height:100%;margin:0;padding:0;background:#111820;overflow:hidden}
                #map{position:absolute;inset:0;opacity:0;transition:opacity .16s ease;background:#111820}
                #map.gps-ready{opacity:1}
                .maplibregl-ctrl-attrib{font-size:8px!important;opacity:.70}
                #waiting{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#dfe8ef;background:#111820;font:700 14px sans-serif;z-index:4;text-align:center}
                .rider-triangle{width:30px;height:34px;position:relative;filter:drop-shadow(0 2px 4px rgba(0,0,0,.65))}
                .rider-triangle:before{content:'';position:absolute;inset:0;background:#fff;clip-path:polygon(50% 0,100% 100%,50% 78%,0 100%)}
                .rider-triangle:after{content:'';position:absolute;left:4px;right:4px;top:5px;bottom:5px;background:#ff4b2b;clip-path:polygon(50% 0,100% 100%,50% 78%,0 100%)}
                .nav-chip{position:absolute;left:8px;top:8px;z-index:7;background:rgba(17,24,32,.84);color:#fff;border-radius:12px;padding:4px 7px;font:700 10px sans-serif;pointer-events:none}
                #gpsRate{position:absolute;right:8px;top:8px;z-index:8;background:rgba(17,24,32,.90);color:#fff;border:1px solid rgba(255,255,255,.35);border-radius:12px;padding:5px 9px;font:700 11px sans-serif;-webkit-tap-highlight-color:transparent}
              </style>
            </head>
            <body>
              <div id="map"></div>
              <div id="waiting">MTB 스마트 GPS<br/>현재 위치 잡는 중…</div>
              <div id="chip" class="nav-chip">▲ 진행방향 고정 · 자동줌</div>
              <button id="gpsRate" onclick="RiderGps.toggleGpsRate()">GPS 5Hz</button>
              <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
              <script>
                window.rcMapReady=false;
                let map=null, routePts=[], rider=null, latestKm=0, latestSpeed=0, latestHeading=0, firstLocation=true;
                let requestedGpsHz=5;
                const style={version:8,sources:{osm:{type:'raster',tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap contributors'}},layers:[{id:'osm',type:'raster',source:'osm'}]};
                function lineFeature(coords){return {type:'FeatureCollection',features:coords.length>1?[{type:'Feature',properties:{},geometry:{type:'LineString',coordinates:coords}}]:[]};}
                function ensureRouteLayer(){if(!map||!map.isStyleLoaded())return;if(!map.getSource('routeRemaining')){map.addSource('routeRemaining',{type:'geojson',data:lineFeature([])});map.addLayer({id:'routeRemaining',type:'line',source:'routeRemaining',paint:{'line-color':'#29b6f6','line-width':6,'line-opacity':.97},layout:{'line-cap':'round','line-join':'round'}});}}
                function renderRoute(km){if(!map||!map.isStyleLoaded()||!routePts.length)return;ensureRouteLayer();const remain=[];let split=0;while(split<routePts.length&&routePts[split][2]<=km+.02)split++;for(let i=Math.max(0,split-1);i<routePts.length;i++)remain.push([routePts[i][1],routePts[i][0]]);const r=map.getSource('routeRemaining');if(r)r.setData(lineFeature(remain));}
                function zoomForSpeed(s){if(s<4)return 18.2;if(s<10)return 17.8;if(s<18)return 17.3;if(s<28)return 16.8;if(s<40)return 16.3;return 15.8;}
                function updateCamera(lat,lon,speed,heading){if(!map)return;map.easeTo({center:[lon,lat],zoom:zoomForSpeed(speed),bearing:heading,pitch:0,duration:firstLocation?0:260,offset:[0,68],essential:true});firstLocation=false;}
                window.rcSetRoute=function(points,name){routePts=Array.isArray(points)?points:[];if(map&&map.isStyleLoaded())renderRoute(latestKm);};
                window.rcSetGpsRate=function(requested,actual){requestedGpsHz=(requested===1)?1:5;document.getElementById('gpsRate').textContent='GPS '+requestedGpsHz+'Hz';};
                window.rcSetHeading=function(heading){if(!map||firstLocation||!isFinite(heading))return;latestHeading=heading;map.easeTo({bearing:heading,duration:220,essential:true});};
                window.rcSetSmartLocation=function(lat,lon,km,speed,heading,accuracy,snapped){
                  latestKm=isFinite(km)?Math.max(0,km):0;latestSpeed=isFinite(speed)?Math.max(0,speed):0;latestHeading=isFinite(heading)?heading:latestHeading;
                  const pos=[lon,lat];if(!rider){const el=document.createElement('div');el.className='rider-triangle';rider=new maplibregl.Marker({element:el,anchor:'center',rotationAlignment:'viewport'}).setLngLat(pos).addTo(map);}else rider.setLngLat(pos);
                  renderRoute(latestKm);document.getElementById('map').classList.add('gps-ready');document.getElementById('waiting').style.display='none';
                  document.getElementById('chip').textContent=Math.round(latestSpeed)+' km/h · '+(snapped?'GPX 보정':'GPS')+' · ▲ 진행방향';
                  updateCamera(lat,lon,latestSpeed,latestHeading);
                };
                try{map=new maplibregl.Map({container:'map',style:style,center:[127.0,36.0],zoom:16,attributionControl:true,dragRotate:false,pitchWithRotate:false,touchPitch:false,cooperativeGestures:false});map.touchZoomRotate.disableRotation();map.on('load',()=>{ensureRouteLayer();window.rcMapReady=true;if(routePts.length)window.rcSetRoute(routePts,'');});}catch(e){window.rcMapReady=false;}
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
