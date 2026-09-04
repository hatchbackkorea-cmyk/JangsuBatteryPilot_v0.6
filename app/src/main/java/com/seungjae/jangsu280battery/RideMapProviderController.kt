package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapOverlay
import com.kakao.vectormap.MapType
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * MTB map-provider switcher.
 *
 * The route itself is always the selected GPX. Kakao is used only as a basemap:
 *  - Kakao map (NORMAL)
 *  - Kakao Skyview + SKYVIEW_HYBRID road/name overlay
 *  - CyclOSM (existing MapLibre WebView)
 *
 * Direction is independent from GPS position. On Kakao maps TYPE_ROTATION_VECTOR provides
 * heading even while GPS is still waiting; once moving fast enough, GPS course is preferred.
 */
object RideMapProviderController {
    private const val PREFS = "mtb_map_ui"
    private const val KEY_MAP_STYLE = "map_style"
    private const val STYLE_KAKAO = "kakao_normal"
    private const val STYLE_KAKAO_SKY = "kakao_sky"
    private const val STYLE_CYCLOSM = "cyclosm"
    private const val TAG_KAKAO = "ride_kakao_map_v03316"

    private val sessions = WeakHashMap<Activity, Session>()

    fun install(activity: Activity) {
        val current = sessions[activity]
        if (current != null) {
            current.resume()
            current.rebindSelector()
            return
        }
        val frame = activity.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
        val session = Session(activity, frame)
        sessions[activity] = session
        session.install()
    }

    fun pause(activity: Activity) {
        sessions[activity]?.pause()
    }

    fun destroy(activity: Activity) {
        sessions.remove(activity)?.destroy()
    }

    private class Session(
        private val activity: Activity,
        private val frame: FrameLayout
    ) {
        private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private var kakaoSurface: KakaoRideMapSurface? = null
        private var selector: TextView? = null
        private var attribution: TextView? = null

        fun install() {
            rebindSelector()
            activity.window.decorView.postDelayed({
                rebindSelector()
                applyStyle(normalizedStoredStyle())
            }, 300L)
        }

        fun rebindSelector() {
            selector = findTextView(frame) { it.contentDescription?.toString() == "지도 종류 선택" }
            attribution = findTextView(frame) { it.text?.toString()?.startsWith("© OpenStreetMap") == true }
            selector?.setOnClickListener { anchor ->
                PopupMenu(activity, anchor).apply {
                    menu.add(0, 301, 0, "카카오 지도")
                    menu.add(0, 302, 1, "카카오 스카이뷰 · 도로/지명")
                    menu.add(0, 303, 2, "CyclOSM · 자전거 지도")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            301 -> applyStyle(STYLE_KAKAO)
                            302 -> applyStyle(STYLE_KAKAO_SKY)
                            else -> applyStyle(STYLE_CYCLOSM)
                        }
                        true
                    }
                    show()
                }
            }
        }

        private fun normalizedStoredStyle(): String {
            val stored = prefs.getString(KEY_MAP_STYLE, null)
            return when (stored) {
                STYLE_KAKAO, STYLE_KAKAO_SKY, STYLE_CYCLOSM -> stored
                else -> if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) STYLE_KAKAO else STYLE_CYCLOSM
            }
        }

        private fun applyStyle(style: String) {
            if ((style == STYLE_KAKAO || style == STYLE_KAKAO_SKY) && BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
                Toast.makeText(activity, "카카오 지도용 네이티브 앱 키 등록이 필요합니다.", Toast.LENGTH_LONG).show()
                showCyclOsm(save = false)
                return
            }
            prefs.edit().putString(KEY_MAP_STYLE, style).apply()
            when (style) {
                STYLE_KAKAO -> showKakao(false)
                STYLE_KAKAO_SKY -> showKakao(true)
                else -> showCyclOsm(save = false)
            }
        }

        private fun showKakao(skyview: Boolean) {
            val smooth = frame.findViewWithTag<View>(RideSmoothMapWebView.TAG_SMOOTH_MAP)
            smooth?.visibility = View.GONE

            val surface = kakaoSurface ?: KakaoRideMapSurface(activity).also { map ->
                map.tag = TAG_KAKAO
                frame.addView(
                    map,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                kakaoSurface = map
            }
            surface.visibility = View.VISIBLE
            surface.setSkyview(skyview)
            surface.resumeMap()
            selector?.bringToFront()
            attribution?.apply {
                text = if (skyview) "© Kakao · Skyview" else "© Kakao"
                bringToFront()
            }
        }

        private fun showCyclOsm(save: Boolean) {
            if (save) prefs.edit().putString(KEY_MAP_STYLE, STYLE_CYCLOSM).apply()
            kakaoSurface?.visibility = View.GONE
            kakaoSurface?.pauseMap()
            val smooth = frame.findViewWithTag<View>(RideSmoothMapWebView.TAG_SMOOTH_MAP) as? RideSmoothMapWebView
            smooth?.visibility = View.VISIBLE
            smooth?.evaluateJavascript(
                """
                (function(){
                  try {
                    var s=map&&map.getSource('osm');
                    if(s&&s.setTiles)s.setTiles(['https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png']);
                    var q=document.getElementById('rc-quarter-arrow');
                    if(!q){
                      q=document.createElement('style');
                      q.id='rc-quarter-arrow';
                      q.textContent='.rider-triangle{width:11.25px!important;height:12.75px!important}';
                      document.head.appendChild(q);
                    }
                  } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
            attribution?.apply {
                text = "© OpenStreetMap · CyclOSM"
                bringToFront()
            }
            selector?.bringToFront()
        }

        fun resume() {
            if (kakaoSurface?.visibility == View.VISIBLE) kakaoSurface?.resumeMap()
        }

        fun pause() {
            kakaoSurface?.pauseMap()
        }

        fun destroy() {
            kakaoSurface?.destroyMap()
            kakaoSurface = null
        }
    }

    private fun findTextView(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }
}

private class KakaoRideMapSurface(context: Context) : FrameLayout(context), LocationListener, SensorEventListener {
    private val mapView = MapView(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val courseRepo = CourseRepository(context)

    private var kakaoMap: KakaoMap? = null
    private var started = false
    private var locationStarted = false
    private var sensorsStarted = false
    private var skyview = false
    private var liveGpsSeen = false
    private var lastGpsElapsed = 0L
    private var actualGpsHz = 0.0
    private var lastLocation: Location? = null
    private var latestSpeedKmh = 0.0
    private var sensorHeading: Double? = null
    private var gpsHeading: Double? = null
    private var lastHeading = 0.0
    private var lastSensorPushElapsed = 0L

    private val gpsText = TextView(context).apply {
        text = if (rotationSensor != null) "GPS 대기 · 방향센서" else "GPS 대기"
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xA6111820.toInt())
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val arrow = FixedRiderArrowView(context)

    init {
        setBackgroundColor(Color.rgb(17, 24, 32))
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(gpsText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        })
        // Previous size was 45 x 51 dp. Keep exactly 25% of that size.
        addView(arrow, LayoutParams(dpf(11.25f), dpf(12.75f), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(52)
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            startMapIfNeeded()
            startSensors()
            startLocation()
        }
    }

    fun setSkyview(enabled: Boolean) {
        skyview = enabled
        applyMapType()
    }

    private fun startMapIfNeeded() {
        if (started || BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) return
        if (!isAttachedToWindow) {
            post { startMapIfNeeded() }
            return
        }
        started = true
        gpsText.text = if (rotationSensor != null) "지도 준비 · GPS 대기 · 방향센서" else "지도 준비 · GPS 대기"
        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    kakaoMap = null
                    started = false
                }

                override fun onMapError(error: Exception) {
                    kakaoMap = null
                    started = false
                    post {
                        gpsText.text = "카카오 지도 연결 실패 · 다시 시도"
                        Toast.makeText(context, "카카오 지도 연결을 다시 시도합니다.", Toast.LENGTH_LONG).show()
                        postDelayed({ if (isAttachedToWindow && visibility == View.VISIBLE) startMapIfNeeded() }, 1200L)
                    }
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    kakaoMap = map
                    gpsText.text = if (liveGpsSeen) "GPS 수신 · 방향융합" else if (rotationSensor != null) "GPS 대기 · 방향센서" else "GPS 대기"
                    map.setPoiLanguage("ko")
                    applyMapType()
                    drawCourse()
                    val first = lastLocation ?: newestLastKnown()
                    if (first != null) {
                        lastLocation = Location(first)
                        updateCamera(first)
                    } else {
                        rotateCameraOnly(lastHeading, animate = false)
                    }
                }

                override fun getPosition(): LatLng {
                    val last = lastLocation ?: newestLastKnown()
                    return if (last != null) LatLng.from(last.latitude, last.longitude)
                    else LatLng.from(36.9920, 127.2700)
                }

                override fun getZoomLevel(): Int = 17
            }
        )
        runCatching { mapView.resume() }
    }

    private fun applyMapType() {
        val map = kakaoMap ?: return
        if (skyview) {
            map.changeMapType(MapType.SKYVIEW)
            map.showOverlay(MapOverlay.SKYVIEW_HYBRID)
        } else {
            map.hideOverlay(MapOverlay.SKYVIEW_HYBRID)
            map.changeMapType(MapType.NORMAL)
        }
    }

    private fun drawCourse() {
        val map = kakaoMap ?: return
        val meta = runCatching { courseRepo.activeMeta() }.getOrNull() ?: return
        val course = runCatching { courseRepo.loadCourse(meta.id) }.getOrNull() ?: return
        if (course.track.size < 2) return
        val step = max(1, course.track.size / 1800)
        val points = ArrayList<LatLng>()
        var i = 0
        while (i < course.track.size) {
            val p = course.track[i]
            points += LatLng.from(p.lat, p.lon)
            i += step
        }
        course.track.lastOrNull()?.let { p ->
            val lastPoint = LatLng.from(p.lat, p.lon)
            if (points.isEmpty() || points.last() != lastPoint) points += lastPoint
        }
        if (points.size < 2) return

        val styles = RouteLineStyles.from(RouteLineStyle.from(12f, Color.rgb(41, 182, 246), 2f, Color.WHITE))
        val stylesSet = RouteLineStylesSet.from("ride-gpx", styles)
        val segment = RouteLineSegment.from(points).setStyles(styles)
        val options = RouteLineOptions.from("ride-gpx", segment).setStylesSet(stylesSet)
        runCatching { map.routeLineManager?.layer?.addRouteLine(options) }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (locationStarted || !hasLocationPermission()) return
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                locationStarted = true
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, this, Looper.getMainLooper())
                locationStarted = true
            }
            runCatching {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1500L, 0f, this, Looper.getMainLooper())
                locationStarted = true
            }
            newestLastKnown()?.let { location ->
                lastLocation = Location(location)
                updateCamera(location)
            }
        }
    }

    private fun stopLocation() {
        if (!locationStarted) return
        runCatching { locationManager.removeUpdates(this) }
        locationStarted = false
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

    private fun newestLastKnown(): Location? {
        if (!hasLocationPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { locationManager.getLastKnownLocation(p) }.getOrNull() }
            .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            .maxByOrNull { it.time }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        val raw = FloatArray(9)
        val adjusted = FloatArray(9)
        val orientation = FloatArray(3)
        runCatching {
            SensorManager.getRotationMatrixFromVector(raw, event.values)
            when (display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjusted)
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjusted)
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjusted)
                else -> System.arraycopy(raw, 0, adjusted, 0, raw.size)
            }
            SensorManager.getOrientation(adjusted, orientation)
        }.getOrElse { return }

        var heading = normalizeHeading(Math.toDegrees(orientation[0].toDouble()))
        val referenceLocation = lastLocation ?: newestLastKnown()
        if (referenceLocation != null) {
            val field = GeomagneticField(
                referenceLocation.latitude.toFloat(),
                referenceLocation.longitude.toFloat(),
                (if (referenceLocation.hasAltitude()) referenceLocation.altitude else 0.0).toFloat(),
                System.currentTimeMillis()
            )
            heading = normalizeHeading(heading + field.declination)
        }
        sensorHeading = heading

        // At low speed or before GPS course exists, the phone sensor owns map direction.
        // Above 5 km/h a valid GPS course is preferred so trail vibration does not swing the map.
        if (latestSpeedKmh >= 5.0 && gpsHeading != null) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorPushElapsed < 90L) return
        lastSensorPushElapsed = now
        lastHeading = smoothHeading(lastHeading, heading)
        rotateCameraOnly(lastHeading, animate = true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (location.provider == LocationManager.GPS_PROVIDER) {
            liveGpsSeen = true
            val now = SystemClock.elapsedRealtime()
            if (lastGpsElapsed > 0 && now > lastGpsElapsed) {
                val hz = 1000.0 / (now - lastGpsElapsed).toDouble()
                if (hz in 0.05..10.0) actualGpsHz = if (actualGpsHz == 0.0) hz else actualGpsHz * 0.75 + hz * 0.25
            }
            lastGpsElapsed = now
            gpsText.text = if (actualGpsHz > 0.0) "GPS %.1fHz · 방향융합".format(actualGpsHz) else "GPS 수신 · 방향융합"
        } else if (liveGpsSeen) {
            return
        } else {
            gpsText.text = if (rotationSensor != null) "보조 위치 · GPS 대기 · 방향센서" else "보조 위치 · GPS 대기"
        }

        lastLocation = Location(location)
        latestSpeedKmh = if (location.hasSpeed()) (location.speed * 3.6).toDouble() else 0.0

        if (location.hasBearing() && latestSpeedKmh >= 5.0) {
            gpsHeading = normalizeHeading(location.bearing.toDouble())
        }

        val candidate = if (latestSpeedKmh >= 5.0) {
            gpsHeading ?: sensorHeading ?: lastHeading
        } else {
            sensorHeading ?: gpsHeading ?: lastHeading
        }
        lastHeading = smoothHeading(lastHeading, candidate)
        updateCamera(location)
    }

    private fun updateCamera(location: Location) {
        val map = kakaoMap ?: return
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val zoom = when {
            speedKmh < 4 -> 18
            speedKmh < 10 -> 18
            speedKmh < 18 -> 17
            speedKmh < 28 -> 17
            speedKmh < 40 -> 16
            else -> 16
        }
        val pos = LatLng.from(location.latitude, location.longitude)
        runCatching {
            map.moveCamera(
                CameraUpdateFactory.newCenterPosition(pos, zoom),
                CameraAnimation.from(450, true, true)
            )
            map.moveCamera(CameraUpdateFactory.rotateTo(Math.toRadians(lastHeading)))
        }
    }

    private fun rotateCameraOnly(heading: Double, animate: Boolean) {
        val map = kakaoMap ?: return
        runCatching {
            if (animate) {
                map.moveCamera(
                    CameraUpdateFactory.rotateTo(Math.toRadians(normalizeHeading(heading))),
                    CameraAnimation.from(180, true, true)
                )
            } else {
                map.moveCamera(CameraUpdateFactory.rotateTo(Math.toRadians(normalizeHeading(heading))))
            }
        }
    }

    private fun smoothHeading(previous: Double, candidate: Double): Double {
        val delta = shortestDelta(previous, candidate)
        if (abs(delta) < 1.5) return normalizeHeading(previous)
        val alpha = when {
            abs(delta) >= 60.0 -> 0.55
            abs(delta) >= 25.0 -> 0.42
            else -> 0.30
        }
        return normalizeHeading(previous + delta * alpha)
    }

    private fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun shortestDelta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    fun resumeMap() {
        if (!isAttachedToWindow) {
            post { resumeMap() }
            return
        }
        startMapIfNeeded()
        runCatching { mapView.resume() }
        startSensors()
        startLocation()
        lastLocation?.let { updateCamera(it) }
    }

    fun pauseMap() {
        stopLocation()
        stopSensors()
        runCatching { mapView.pause() }
    }

    fun destroyMap() {
        stopLocation()
        stopSensors()
        kakaoMap = null
        started = false
        runCatching { mapView.finish() }
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}

private class FixedRiderArrowView(context: Context) : View(context) {
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 75, 43); style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Path().apply {
            moveTo(w * .5f, 0f)
            lineTo(w, h)
            lineTo(w * .5f, h * .78f)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(p, outer)
        val q = Path().apply {
            moveTo(w * .5f, h * .10f)
            lineTo(w * .88f, h * .88f)
            lineTo(w * .5f, h * .70f)
            lineTo(w * .12f, h * .88f)
            close()
        }
        canvas.drawPath(q, inner)
    }
}
