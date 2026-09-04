package com.seungjae.jangsu280battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapOverlay
import com.kakao.vectormap.MapType
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraPosition
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import java.util.Locale
import kotlin.math.abs

/**
 * Native Kakao ride map used by the MTB/ROAD HUD selector.
 *
 * Kakao is only the basemap. GPX remains the source of route truth; no Kakao routing API is used.
 * NORMAL and SKYVIEW + SKYVIEW_HYBRID are supported. GPS remains preferred, while network/passive
 * location can bootstrap the view indoors. This display path does not write to learning/log data.
 */
class KakaoRideMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), LocationListener {

    enum class Mode { NORMAL, SKYVIEW }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val courseRepo = CourseRepository(context)
    private val mapView = MapView(context)
    private val gpsChip = TextView(context)

    private var kakaoMap: KakaoMap? = null
    private var started = false
    private var active = false
    private var locationStarted = false
    private var mode = Mode.NORMAL
    private var activeCourseId: String? = null
    private var course: CourseData? = null
    private var routeStyles: RouteLineStylesSet? = null
    private var routeLine: RouteLine? = null
    private var riderLabel: Label? = null
    private var lastRouteKm = 0.0
    private var lastDrawnRouteKm = -999.0
    private var lastGpsElapsed = 0L
    private var lastFixElapsed = 0L
    private var actualGpsHz = 0.0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastHeading = 0.0
    private var lastSpeedKmh = 0.0

    init {
        tag = TAG_KAKAO_MAP
        setBackgroundColor(Color.rgb(17, 24, 32))
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        gpsChip.apply {
            text = "GPS 대기"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 17, 24, 32))
                setStroke(dp(1f), Color.argb(100, 255, 255, 255))
                cornerRadius = dp(12f).toFloat()
            }
            elevation = dp(4f).toFloat()
        }
        addView(
            gpsChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(30f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(7f)
                marginEnd = dp(7f)
            }
        )
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        applyMapMode()
    }

    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        if (enabled) {
            visibility = View.VISIBLE
            startMapIfNeeded()
            startLocations()
            runCatching { if (started) mapView.resume() }
        } else {
            stopLocations()
            runCatching { if (started) mapView.pause() }
            visibility = View.GONE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) {
            startMapIfNeeded()
            startLocations()
        }
    }

    override fun onDetachedFromWindow() {
        stopLocations()
        runCatching { if (started) mapView.pause() }
        super.onDetachedFromWindow()
    }

    private fun startMapIfNeeded() {
        if (started || !KakaoMapSdkGate.ensureInitialized(context)) return
        started = true
        refreshCourse(force = true)
        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    kakaoMap = null
                    riderLabel = null
                    routeLine = null
                }

                override fun onMapError(error: Exception) {
                    gpsChip.post { gpsChip.text = "카카오 지도 인증 확인" }
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    kakaoMap = map
                    map.setPoiLanguage("ko")
                    map.compass?.hide()
                    routeStyles = RouteLineStylesSet.from(
                        "copilot-gpx",
                        RouteLineStyles.from(
                            RouteLineStyle.from(
                                dp(6f).toFloat(),
                                Color.rgb(41, 182, 246),
                                dp(1.5f).toFloat(),
                                Color.WHITE
                            )
                        )
                    )
                    applyMapMode()
                    refreshCourse(force = true)
                    drawRemainingRoute(force = true)
                    if (lastLat.isFinite() && lastLon.isFinite()) {
                        renderPosition(lastLat, lastLon, lastRouteKm, lastSpeedKmh, lastHeading, false)
                    }
                }
            }
        )
        runCatching { mapView.resume() }
    }

    private fun applyMapMode() {
        val map = kakaoMap ?: return
        if (mode == Mode.SKYVIEW) {
            map.changeMapType(MapType.SKYVIEW)
            map.showOverlay(MapOverlay.SKYVIEW_HYBRID)
        } else {
            map.hideOverlay(MapOverlay.SKYVIEW_HYBRID)
            map.changeMapType(MapType.NORMAL)
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocations() {
        if (locationStarted || !active || !isAttachedToWindow || !hasLocationPermission()) return
        locationStarted = true
        primeLastKnown()
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2500L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    2500L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            locationStarted = false
        } catch (_: Exception) {
            locationStarted = false
        }
    }

    private fun stopLocations() {
        if (!locationStarted) return
        runCatching { locationManager.removeUpdates(this) }
        locationStarted = false
    }

    private fun primeLastKnown() {
        if (!hasLocationPermission()) return
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val best = providers.mapNotNull { p ->
            runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()
        }.filter { valid(it) }.maxByOrNull { it.time }
        if (best != null) acceptDisplayLocation(best, bootstrap = true)
    }

    override fun onLocationChanged(location: Location) {
        if (!valid(location)) return
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        val now = SystemClock.elapsedRealtime()

        if (isGps) {
            if (lastFixElapsed > 0L && now > lastFixElapsed) {
                val hz = 1000.0 / (now - lastFixElapsed).toDouble()
                if (hz in 0.05..10.0) {
                    actualGpsHz = if (actualGpsHz <= 0.0) hz else actualGpsHz * 0.78 + hz * 0.22
                }
            }
            lastFixElapsed = now
            lastGpsElapsed = now
            gpsChip.text = "GPS ${String.format(Locale.KOREA, "%.1f", actualGpsHz.coerceAtLeast(1.0))}Hz"
        } else {
            if (lastGpsElapsed > 0L && now - lastGpsElapsed < 10_000L) return
            if (location.hasAccuracy() && location.accuracy > 250f) return
        }

        acceptDisplayLocation(location, bootstrap = !isGps)
    }

    private fun acceptDisplayLocation(location: Location, bootstrap: Boolean) {
        refreshCourse(force = false)
        var lat = location.latitude
        var lon = location.longitude
        var routeKm = lastRouteKm
        val c = course
        if (c != null) {
            val match = runCatching { c.nearestRouteLocation(lat, lon) }.getOrNull()
            if (match != null && match.distanceM <= 250.0) {
                routeKm = match.routeKm
                if (!bootstrap && match.distanceM <= 55.0) {
                    lat = match.trackLat
                    lon = match.trackLon
                }
            }
        }

        val speed = if (location.hasSpeed()) (location.speed * 3.6).coerceIn(0.0, 120.0) else 0.0
        val heading = when {
            location.hasBearing() && speed >= 3.0 -> normalize(location.bearing.toDouble())
            c != null -> routeHeading(c, routeKm) ?: lastHeading
            else -> lastHeading
        }

        lastLat = lat
        lastLon = lon
        lastRouteKm = routeKm
        lastSpeedKmh = speed
        lastHeading = heading
        StartupLocationPrimer.remember(context, location)
        renderPosition(lat, lon, routeKm, speed, heading, bootstrap)
    }

    private fun renderPosition(
        lat: Double,
        lon: Double,
        routeKm: Double,
        speedKmh: Double,
        heading: Double,
        bootstrap: Boolean
    ) {
        val map = kakaoMap ?: return
        val pos = LatLng.from(lat, lon)
        if (riderLabel == null) {
            riderLabel = map.labelManager?.layer?.addLabel(
                LabelOptions.from("copilot-rider", pos).setStyles(riderBitmap())
            )
        } else {
            riderLabel?.moveTo(pos, if (bootstrap) 0 else 650)
        }

        drawRemainingRoute(force = abs(routeKm - lastDrawnRouteKm) >= 0.04)

        val zoom = zoomForSpeed(speedKmh)
        val camera = CameraPosition.from(
            CameraPosition.Builder()
                .setPosition(pos)
                .setZoomLevel(zoom)
                .setTiltAngle(0.0)
                .setRotationAngle(Math.toRadians(heading))
        )
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (bootstrap) map.moveCamera(update)
        else map.moveCamera(update, CameraAnimation.from(760, false, false))
    }

    private fun refreshCourse(force: Boolean) {
        val meta = runCatching { courseRepo.activeMeta() }.getOrNull() ?: return
        if (!force && activeCourseId == meta.id) return
        val loaded = runCatching { courseRepo.loadCourse(meta.id) }.getOrNull() ?: return
        activeCourseId = meta.id
        course = loaded
        lastDrawnRouteKm = -999.0
        drawRemainingRoute(force = true)
    }

    private fun drawRemainingRoute(force: Boolean) {
        if (!force) return
        val map = kakaoMap ?: return
        val c = course ?: return
        if (c.track.size < 2) return
        val styles = routeStyles ?: return

        val startIndex = c.indexAtKm(lastRouteKm.coerceIn(0.0, c.totalKm)).coerceIn(0, c.track.lastIndex)
        val tail = c.track.subList((startIndex - 1).coerceAtLeast(0), c.track.size)
        val stride = (tail.size / MAX_ROUTE_POINTS).coerceAtLeast(1)
        val points = ArrayList<LatLng>(MAX_ROUTE_POINTS + 2)
        var i = 0
        while (i < tail.size) {
            val p = tail[i]
            points += LatLng.from(p.lat, p.lon)
            i += stride
        }
        tail.lastOrNull()?.let { last ->
            if (points.isEmpty() || points.last().latitude != last.lat || points.last().longitude != last.lon) {
                points += LatLng.from(last.lat, last.lon)
            }
        }
        if (points.size < 2) return

        val segment = RouteLineSegment.from(points).setStyles(styles.getStyles(0))
        if (routeLine == null) {
            routeLine = map.routeLineManager?.layer?.addRouteLine(
                RouteLineOptions.from("copilot-gpx-remaining", segment).setStylesSet(styles)
            )
        } else {
            routeLine?.changeSegments(segment)
        }
        lastDrawnRouteKm = lastRouteKm
    }

    private fun routeHeading(c: CourseData, km: Double): Double? {
        if (c.track.size < 2) return null
        val look = when {
            lastSpeedKmh >= 28.0 -> 0.060
            lastSpeedKmh >= 14.0 -> 0.040
            else -> 0.025
        }
        val a = c.pointAtKm((km - look * 0.2).coerceAtLeast(0.0))
        val b = c.pointAtKm((km + look).coerceAtMost(c.totalKm))
        if (Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon) < 3.0) return null
        val la = Location("a").apply { latitude = a.lat; longitude = a.lon }
        val lb = Location("b").apply { latitude = b.lat; longitude = b.lon }
        return normalize(la.bearingTo(lb).toDouble())
    }

    private fun zoomForSpeed(speedKmh: Double): Int = when {
        speedKmh < 4.0 -> 19
        speedKmh < 10.0 -> 18
        speedKmh < 20.0 -> 17
        speedKmh < 32.0 -> 16
        else -> 15
    }

    private fun riderBitmap(): Bitmap {
        val width = dp(42f).coerceAtLeast(28)
        val height = dp(48f).coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outer = Path().apply {
            moveTo(width * 0.5f, 1f)
            lineTo(width - 1f, height - 1f)
            lineTo(width * 0.5f, height * 0.78f)
            lineTo(1f, height - 1f)
            close()
        }
        val inner = Path().apply {
            moveTo(width * 0.5f, dp(5f).toFloat())
            lineTo((width - dp(5f)).toFloat(), height - dp(6f).toFloat())
            lineTo(width * 0.5f, height * 0.76f)
            lineTo(dp(5f).toFloat(), height - dp(6f).toFloat())
            close()
        }
        canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 75, 43) })
        return bitmap
    }

    private fun valid(location: Location): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

    private fun normalize(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    companion object {
        const val TAG_KAKAO_MAP = "ride_kakao_map_v03312"
        private const val MAX_ROUTE_POINTS = 2200
    }
}
