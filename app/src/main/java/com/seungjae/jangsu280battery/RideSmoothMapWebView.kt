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
 * MTB navigation display.
 * Raw GPS / trusted RideService logging remains untouched. This view is display-only.
 * v0.33.9 also bootstraps the map from Android's system last-known location so that
 * opening the app indoors can still show the place where the phone was last located.
 */
@SuppressLint("SetJavaScriptEnabled")
class RideSmoothMapWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs), LocationListener, SensorEventListener {

    private val courseRepo = CourseRepository(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var pageReady = false
    private var mapVerified = false
    private var receiverRegistered = false
    private var gpsStarted = false
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

    private var lastFixElapsedMs = 0L
    private var actualHz = 0.0

    // System location cache can exist even when this app was not running.
    // Keep it separate from the live GPS stream so it never affects logging or measured GPS Hz.
    private var bootstrapLocation: Location? = null
    private var bootstrapPushed = false

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE) return
            latestRouteKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestRouteKm)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)

            if (!gpsStarted && intent.hasExtra(RideService.EXTRA_LAT) && intent.hasExtra(RideService.EXTRA_LON)) {
                val lat = intent.getDoubleExtra(RideService.EXTRA_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(RideService.EXTRA_LON, Double.NaN)
                if (lat.isFinite() && lon.isFinite()) {
                    latestDisplayLat = lat
                    latestDisplayLon = lon
                    val heading = responsiveHeadingCandidate(latestSpeedKmh)
                        ?: routeHeadingAt(latestRouteKm)
                        ?: fusedHeading
                        ?: 0.0
                    fusedHeading = stabilizeHeading(heading, latestSpeedKmh)
                    pushFix(lat, lon, latestRouteKm, latestSpeedKmh, fusedHeading ?: 0.0, latestAccuracyM, false)
                }
            }
        }
    }

    init {
        tag = TAG_SMOOTH_MAP
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

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                mapVerified = false
                refreshCourse(force = true)
                pushBootstrapLocationIfAvailable()
                pushGpsRateToPage()
                verifyInteractiveMap(0)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageReady = false
                    mapVerified = false
                    alpha = 0f
                    updateStatus("24FPS 내비 지도 연결 실패 · 기존 지도 사용")
                }
            }
        }

        loadDataWithBaseURL("https://ride-copilot.local/", HTML, "text/html", "UTF-8", null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerRideReceiver()
        startSensors()
        primeSystemLastKnownLocation()
        startGps()
        post { refreshCourse(false) }
    }

    override fun onDetachedFromWindow() {
        stopGps()
        stopSensors()
        unregisterRideReceiver()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            post {
                refreshCourse(false)
                if (!latestDisplayLat.isFinite() || !latestDisplayLon.isFinite()) primeSystemLastKnownLocation()
                if (!sensorsStarted) startSensors()
                if (!gpsStarted) startGps()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Reads Android's own last-known location cache. This may have been populated by the OS or
     * another location-using app before Ride Copilot was launched. No age label is shown; the
     * point is only a startup visual anchor and is replaced immediately by the first live fix.
     */
    private fun primeSystemLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val providers = LinkedHashSet<String>().apply {
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
                addAll(locationManager.getProviders(true))
            }
            val candidates = providers.mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }

            val best = candidates.maxWithOrNull(
                compareBy<Location> { it.time }
                    .thenBy { if (it.hasAccuracy()) -it.accuracy.toDouble() else -9999.0 }
            ) ?: return

            bootstrapLocation = Location(best)
            if (!latestDisplayLat.isFinite() || !latestDisplayLon.isFinite()) {
                applyBootstrapLocation(best)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun applyBootstrapLocation(location: Location) {
        var displayLat = location.latitude
        var displayLon = location.longitude
        var routeKm = latestRouteKm
        var snapped = false

        activeCourse?.let { course ->
            val match = runCatching { course.nearestRouteLocation(location.latitude, location.longitude) }.getOrNull()
            if (match != null && match.distanceM <= 250.0) {
                routeKm = match.routeKm
                displayLat = match.trackLat
                displayLon = match.trackLon
                snapped = match.distanceM <= 80.0
            }
        }

        latestRouteKm = routeKm
        latestDisplayLat = displayLat
        latestDisplayLon = displayLon
        latestAccuracyM = if (location.hasAccuracy()) location.accuracy else -1f
        lastAcceptedLocation = Location(location)
        val heading = when {
            location.hasBearing() -> normalizeAngle(location.bearing.toDouble())
            else -> routeHeadingAt(routeKm) ?: sensorTrueHeading ?: fusedHeading ?: 0.0
        }
        fusedHeading = normalizeAngle(heading)
        isSnapped = snapped
        pushBootstrapLocationIfAvailable()
    }

    private fun pushBootstrapLocationIfAvailable() {
        if (!pageReady || bootstrapPushed) return
        val last = bootstrapLocation ?: return
        if (!latestDisplayLat.isFinite() || !latestDisplayLon.isFinite()) return
        bootstrapPushed = true
        pushFix(
            latestDisplayLat,
            latestDisplayLon,
            latestRouteKm,
            0.0,
            fusedHeading ?: routeHeadingAt(latestRouteKm) ?: 0.0,
            if (last.hasAccuracy()) last.accuracy else -1f,
            isSnapped
        )
    }

    private fun startGps() {
        if (gpsStarted || !isAttachedToWindow || !hasLocationPermission()) return
        try {
            when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        this,
                        Looper.getMainLooper()
                    )
                    gpsStarted = true
                }
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0f,
                        this,
                        Looper.getMainLooper()
                    )
                    gpsStarted = true
                }
            }
        } catch (_: Exception) {
            gpsStarted = false
        }
        updateStatusText()
    }

    private fun stopGps() {
        if (!gpsStarted) return
        runCatching { locationManager.removeUpdates(this) }
        gpsStarted = false
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

        var heading = normalizeAngle(Math.toDegrees(orientation[0].toDouble()))
        lastAcceptedLocation?.let { accepted ->
            val field = GeomagneticField(
                accepted.latitude.toFloat(),
                accepted.longitude.toFloat(),
                (if (accepted.hasAltitude()) accepted.altitude else 0.0).toFloat(),
                System.currentTimeMillis()
            )
            heading = normalizeAngle(heading + field.declination)
        }
        sensorTrueHeading = heading

        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorPushMs < 80L || !latestDisplayLat.isFinite() || !latestDisplayLon.isFinite()) return
        lastSensorPushMs = now

        responsiveHeadingCandidate(latestSpeedKmh)?.let { candidate ->
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
        if (location.time > 0L && ageMs > 15_000L) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastFixElapsedMs > 0L && nowElapsed > lastFixElapsedMs) {
            val hz = 1000.0 / (nowElapsed - lastFixElapsedMs).toDouble()
            if (hz in 0.05..10.0) actualHz = if (actualHz <= 0.0) hz else actualHz * 0.78 + hz * 0.22
        }
        lastFixElapsedMs = nowElapsed

        if (isImplausibleJump(location)) {
            rejectedJumpCount++
            updateStatusText()
            return
        }

        // A live fix takes ownership immediately; bootstrap was display-only.
        bootstrapLocation = null
        bootstrapPushed = true

        val previous = lastAcceptedLocation
        lastAcceptedLocation = Location(location)
        latestAccuracyM = if (location.hasAccuracy()) location.accuracy else -1f
        if (location.hasSpeed()) latestSpeedKmh = (location.speed * 3.6).coerceIn(0.0, 120.0)

        latestGpsHeading = when {
            location.hasBearing() && latestSpeedKmh >= 3.5 -> normalizeAngle(location.bearing.toDouble())
            previous != null && previous.distanceTo(location) >= 2.0f -> normalizeAngle(previous.bearingTo(location).toDouble())
            else -> latestGpsHeading
        }

        var displayLat = location.latitude
        var displayLon = location.longitude
        var snapped = false
        activeCourse?.let { course ->
            val match = runCatching { course.nearestRouteLocation(location.latitude, location.longitude) }.getOrNull()
            if (match != null) {
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 20.0
                val snapLimit = (accuracy * 1.45 + 7.0).coerceIn(15.0, 50.0)
                val priorSnap = lastSnapKm
                val continuityOk = priorSnap == null || abs(match.routeKm - priorSnap) <= routeContinuityLimitKm(previous, location)
                if (match.distanceM <= snapLimit && continuityOk) {
                    latestRouteKm = match.routeKm
                    lastSnapKm = match.routeKm
                    displayLat = match.trackLat
                    displayLon = match.trackLon
                    snapped = true
                } else if (match.distanceM <= 220.0 && priorSnap == null) {
                    latestRouteKm = match.routeKm
                }
            }
        }

        isSnapped = snapped
        latestDisplayLat = displayLat
        latestDisplayLon = displayLon

        val candidate = responsiveHeadingCandidate(latestSpeedKmh)
            ?: routeHeadingAt(latestRouteKm)
            ?: latestGpsHeading
            ?: sensorTrueHeading
            ?: fusedHeading
            ?: 0.0
        val stable = stabilizeHeading(candidate, latestSpeedKmh)
        fusedHeading = stable

        pushFix(displayLat, displayLon, latestRouteKm, latestSpeedKmh, stable, latestAccuracyM, snapped)
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
        val allowed = max(65.0, dtSec * 24.0 + currentAcc + prevAcc + 22.0)
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
        return (physicalKm + 0.35).coerceIn(0.45, 1.4)
    }

    private fun routeHeadingAt(km: Double): Double? {
        val course = activeCourse ?: return null
        if (course.track.size < 2) return null
        val look = when {
            latestSpeedKmh >= 28.0 -> 0.060
            latestSpeedKmh >= 14.0 -> 0.040
            else -> 0.025
        }
        val a = course.pointAtKm((km - look * 0.20).coerceAtLeast(0.0))
        val b = course.pointAtKm((km + look).coerceAtMost(course.totalKm))
        if (Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon) < 3.0) return null
        return bearing(a.lat, a.lon, b.lat, b.lon)
    }

    private fun responsiveHeadingCandidate(speedKmh: Double): Double? {
        val route = routeHeadingAt(latestRouteKm)
        val gps = latestGpsHeading
        val sensor = sensorTrueHeading
        val weighted = mutableListOf<Pair<Double, Double>>()
        when {
            speedKmh >= 8.0 -> {
                gps?.let { weighted += it to 0.66 }
                route?.let { weighted += it to 0.24 }
                sensor?.let { weighted += it to 0.10 }
            }
            speedKmh >= 3.5 -> {
                gps?.let { weighted += it to 0.52 }
                route?.let { weighted += it to 0.32 }
                sensor?.let { weighted += it to 0.16 }
            }
            else -> {
                route?.let { weighted += it to 0.58 }
                sensor?.let { weighted += it to 0.42 }
            }
        }
        return circularAverage(weighted)
    }

    private fun stabilizeHeading(candidate: Double, speedKmh: Double): Double {
        val previous = fusedHeading ?: return normalizeAngle(candidate)
        val delta = shortestDelta(previous, candidate)
        val magnitude = abs(delta)
        val deadBand = when {
            speedKmh < 3.0 -> 3.0
            speedKmh < 10.0 -> 2.0
            else -> 1.4
        }
        if (magnitude <= deadBand) return previous

        val alpha = when {
            magnitude >= 55.0 -> 0.88
            magnitude >= 30.0 -> 0.76
            magnitude >= 15.0 -> 0.62
            magnitude >= 6.0 -> 0.48
            speedKmh < 3.0 -> 0.28
            else -> 0.38
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
                    animate().alpha(1f).setDuration(140L).start()
                    pushBootstrapLocationIfAvailable()
                    updateStatusText()
                }
            } else if (attempt < 12) {
                postDelayed({ verifyInteractiveMap(attempt + 1) }, 500L)
            } else {
                mapVerified = false
                alpha = 0f
                updateStatus("24FPS 내비 지도 로딩 실패 · 기존 지도 사용")
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

        // If the bootstrap point was obtained before the GPX was loaded, re-evaluate it now.
        if (bootstrapLocation != null && lastFixElapsedMs == 0L) {
            bootstrapPushed = false
            applyBootstrapLocation(bootstrapLocation!!)
        }

        val points = downsample(course.track, MAX_ROUTE_POINTS)
        val routeJson = JSONArray()
        points.forEach { p ->
            routeJson.put(JSONArray().apply {
                put(p.lat)
                put(p.lon)
                put(p.routeKm)
            })
        }
        evaluateJavascript("window.rcSetRoute && window.rcSetRoute($routeJson);", null)
        updateStatusText()
    }

    private fun pushFix(
        lat: Double,
        lon: Double,
        km: Double,
        speedKmh: Double,
        heading: Double,
        accuracyM: Float,
        snapped: Boolean
    ) {
        if (!pageReady || !lat.isFinite() || !lon.isFinite()) return
        evaluateJavascript(
            String.format(
                Locale.US,
                "window.rcSetFix && window.rcSetFix(%.7f,%.7f,%.4f,%.2f,%.2f,%.1f,%s);",
                lat,
                lon,
                km.coerceAtLeast(0.0),
                speedKmh.coerceIn(0.0, 120.0),
                normalizeAngle(heading),
                accuracyM.toDouble(),
                if (snapped) "true" else "false"
            ),
            null
        )
    }

    private fun pushHeading(heading: Double) {
        if (!pageReady || !mapVerified) return
        evaluateJavascript(
            String.format(Locale.US, "window.rcSetHeading && window.rcSetHeading(%.2f);", normalizeAngle(heading)),
            null
        )
    }

    private fun pushGpsRateToPage() {
        if (!pageReady) return
        val hz = if (actualHz > 0.0) actualHz.coerceAtMost(10.0) else 0.0
        evaluateJavascript(
            String.format(Locale.US, "window.rcSetGpsRate && window.rcSetGpsRate(%.2f);", hz),
            null
        )
    }

    private fun updateStatusText() {
        val rate = if (actualHz > 0.0) "${String.format(Locale.KOREA, "%.1f", actualHz)}Hz" else "대기"
        val gps = if (latestDisplayLat.isFinite()) "현재위치" else "위치잡는중"
        val snap = if (isSnapped) "GPX 보정" else "원 GPS"
        val sensor = if (rotationSensor != null) "방향융합" else "GPS방향"
        val jump = if (rejectedJumpCount > 0) " · 튐차단 $rejectedJumpCount" else ""
        updateStatus("내비 · $gps · $snap · $sensor · GPS $rate · 화면 24fps$jump")
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
        const val TAG_SMOOTH_MAP = "ride_smooth_map_v0335"
        private const val MAX_ROUTE_POINTS = 4000

        private val HTML = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
              <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" />
              <style>
                html,body{width:100%;height:100%;margin:0;padding:0;background:#111820;overflow:hidden}
                #map{position:absolute;inset:0;opacity:0;transition:opacity .12s ease;background:#111820}
                #map.gps-ready{opacity:1}
                .maplibregl-ctrl-attrib{font-size:8px!important;opacity:.68}
                #waiting{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#dfe8ef;background:#111820;font:700 14px sans-serif;z-index:4;text-align:center}
                .rider-triangle{width:30px;height:34px;position:relative;filter:drop-shadow(0 2px 4px rgba(0,0,0,.65))}
                .rider-triangle:before{content:'';position:absolute;inset:0;background:#fff;clip-path:polygon(50% 0,100% 100%,50% 78%,0 100%)}
                .rider-triangle:after{content:'';position:absolute;left:4px;right:4px;top:5px;bottom:5px;background:#ff4b2b;clip-path:polygon(50% 0,100% 100%,50% 78%,0 100%)}
                .nav-chip{position:absolute;left:8px;top:8px;z-index:7;background:rgba(17,24,32,.84);color:#fff;border-radius:12px;padding:4px 7px;font:700 10px sans-serif;pointer-events:none}
                #fps{position:absolute;right:8px;top:8px;z-index:8;background:rgba(17,24,32,.90);color:#fff;border:1px solid rgba(255,255,255,.35);border-radius:12px;padding:5px 9px;font:700 11px sans-serif;pointer-events:none}
              </style>
            </head>
            <body>
              <div id="map"></div>
              <div id="waiting">GPS 1Hz + 화면 24fps<br/>현재 위치 잡는 중…</div>
              <div id="chip" class="nav-chip">▲ 진행방향 · 24fps 보간</div>
              <div id="fps">GPS 1Hz · 24fps</div>
              <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
              <script>
                window.rcMapReady=false;
                const FRAME_MS=1000/24;
                const MAX_PREDICT_MS=1150;
                let map=null,routePts=[],rider=null;
                let latestKm=0,latestSpeed=0,latestHeading=0,latestSnapped=false;
                let fixLat=null,fixLon=null,fixAt=0;
                let renderLat=null,renderLon=null,renderHeading=0,renderZoom=17.5;
                let lastFrame=0;

                const style={version:8,sources:{osm:{type:'raster',tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap contributors'}},layers:[{id:'osm',type:'raster',source:'osm'}]};
                function lineFeature(coords){return {type:'FeatureCollection',features:coords.length>1?[{type:'Feature',properties:{},geometry:{type:'LineString',coordinates:coords}}]:[]};}
                function ensureRouteLayer(){if(!map||!map.isStyleLoaded())return;if(!map.getSource('routeRemaining')){map.addSource('routeRemaining',{type:'geojson',data:lineFeature([])});map.addLayer({id:'routeRemaining',type:'line',source:'routeRemaining',paint:{'line-color':'#29b6f6','line-width':6,'line-opacity':.97},layout:{'line-cap':'round','line-join':'round'}});}}
                function renderRoute(km){if(!map||!map.isStyleLoaded()||routePts.length<2)return;ensureRouteLayer();let lo=0,hi=routePts.length-1;while(lo<hi){const mid=(lo+hi)>>1;if(routePts[mid][2]<km-.02)lo=mid+1;else hi=mid;}const remain=[];for(let i=Math.max(0,lo-1);i<routePts.length;i++)remain.push([routePts[i][1],routePts[i][0]]);const s=map.getSource('routeRemaining');if(s)s.setData(lineFeature(remain));}
                function zoomForSpeed(s){if(s<4)return 18.2;if(s<10)return 17.8;if(s<18)return 17.3;if(s<28)return 16.8;if(s<40)return 16.3;return 15.8;}
                function shortestDelta(a,b){return ((b-a+540)%360)-180;}
                function lerp(a,b,t){return a+(b-a)*t;}

                function routePointAtKm(km){
                  if(routePts.length<2)return null;
                  const target=Math.max(routePts[0][2],Math.min(km,routePts[routePts.length-1][2]));
                  let lo=0,hi=routePts.length-1;
                  while(lo<hi){const mid=(lo+hi)>>1;if(routePts[mid][2]<target)lo=mid+1;else hi=mid;}
                  if(lo===0)return [routePts[0][0],routePts[0][1]];
                  const b=routePts[lo],a=routePts[lo-1],span=b[2]-a[2];
                  const f=span>1e-6?Math.max(0,Math.min(1,(target-a[2])/span)):1;
                  return [lerp(a[0],b[0],f),lerp(a[1],b[1],f)];
                }

                function advance(lat,lon,heading,distanceM){
                  if(distanceM<=0)return [lat,lon];
                  const R=6371000,br=heading*Math.PI/180,p1=lat*Math.PI/180,l1=lon*Math.PI/180,d=distanceM/R;
                  const p2=Math.asin(Math.sin(p1)*Math.cos(d)+Math.cos(p1)*Math.sin(d)*Math.cos(br));
                  const l2=l1+Math.atan2(Math.sin(br)*Math.sin(d)*Math.cos(p1),Math.cos(d)-Math.sin(p1)*Math.sin(p2));
                  return [p2*180/Math.PI,l2*180/Math.PI];
                }

                function visualTarget(now){
                  if(fixLat==null||fixLon==null)return null;
                  const elapsed=Math.max(0,Math.min(MAX_PREDICT_MS,now-fixAt));
                  if(latestSpeed<2.5)return [fixLat,fixLon];
                  const distanceKm=(latestSpeed/3600)*(elapsed/1000);
                  if(latestSnapped){
                    const p=routePointAtKm(latestKm+distanceKm);
                    if(p)return p;
                  }
                  return advance(fixLat,fixLon,latestHeading,distanceKm*1000);
                }

                function ensureRider(lat,lon){
                  const pos=[lon,lat];
                  if(!rider){const el=document.createElement('div');el.className='rider-triangle';rider=new maplibregl.Marker({element:el,anchor:'center',rotationAlignment:'viewport'}).setLngLat(pos).addTo(map);}
                  else rider.setLngLat(pos);
                }

                function frame(now){
                  requestAnimationFrame(frame);
                  if(!map||fixLat==null||now-lastFrame<FRAME_MS)return;
                  lastFrame=now;
                  const target=visualTarget(now);if(!target)return;
                  if(renderLat==null){renderLat=target[0];renderLon=target[1];renderHeading=latestHeading;renderZoom=zoomForSpeed(latestSpeed);}
                  const posGain=0.34;
                  renderLat=lerp(renderLat,target[0],posGain);
                  renderLon=lerp(renderLon,target[1],posGain);
                  const hd=Math.abs(shortestDelta(renderHeading,latestHeading));
                  const headGain=hd>=45?.55:hd>=18?.38:hd>=6?.24:.16;
                  renderHeading=(renderHeading+shortestDelta(renderHeading,latestHeading)*headGain+360)%360;
                  renderZoom=lerp(renderZoom,zoomForSpeed(latestSpeed),.10);
                  ensureRider(renderLat,renderLon);
                  map.easeTo({center:[renderLon,renderLat],zoom:renderZoom,bearing:renderHeading,pitch:0,duration:0,offset:[0,68],essential:true});
                }

                window.rcSetRoute=function(points){routePts=Array.isArray(points)?points:[];if(map&&map.isStyleLoaded())renderRoute(latestKm);};
                window.rcSetGpsRate=function(actual){const t=actual>0?actual.toFixed(1)+'Hz':'대기';document.getElementById('fps').textContent='GPS '+t+' · 24fps';};
                window.rcSetHeading=function(heading){if(isFinite(heading))latestHeading=((heading%360)+360)%360;};
                window.rcSetFix=function(lat,lon,km,speed,heading,accuracy,snapped){
                  latestKm=isFinite(km)?Math.max(0,km):0;latestSpeed=isFinite(speed)?Math.max(0,speed):0;latestHeading=isFinite(heading)?((heading%360)+360)%360:latestHeading;latestSnapped=!!snapped;
                  fixLat=lat;fixLon=lon;fixAt=performance.now();
                  if(renderLat==null){renderLat=lat;renderLon=lon;renderHeading=latestHeading;renderZoom=zoomForSpeed(latestSpeed);}
                  renderRoute(latestKm);document.getElementById('map').classList.add('gps-ready');document.getElementById('waiting').style.display='none';
                  document.getElementById('chip').textContent=Math.round(latestSpeed)+' km/h · '+(snapped?'GPX 보정':'GPS')+' · ▲ 진행방향 · 24fps';
                };
                try{
                  map=new maplibregl.Map({container:'map',style:style,center:[127.0,36.0],zoom:16,attributionControl:true,dragRotate:false,pitchWithRotate:false,touchPitch:false,cooperativeGestures:false});
                  map.touchZoomRotate.disableRotation();
                  map.on('load',()=>{ensureRouteLayer();window.rcMapReady=true;if(routePts.length)renderRoute(latestKm);requestAnimationFrame(frame);});
                }catch(e){window.rcMapReady=false;}
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
