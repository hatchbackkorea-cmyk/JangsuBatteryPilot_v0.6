package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
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
import kotlin.math.max

/**
 * v0.33.12 map-provider switcher.
 *
 * The route itself is always the selected GPX. Kakao is used only as a basemap:
 *  - Kakao map (NORMAL)
 *  - Kakao Skyview + SKYVIEW_HYBRID road/name overlay
 *  - CyclOSM (existing MapLibre WebView)
 */
object RideMapProviderController {
    private const val PREFS = "mtb_map_ui"
    private const val KEY_MAP_STYLE = "map_style"
    private const val STYLE_KAKAO = "kakao_normal"
    private const val STYLE_KAKAO_SKY = "kakao_sky"
    private const val STYLE_CYCLOSM = "cyclosm"
    private const val TAG_KAKAO = "ride_kakao_map_v03312"

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
                "(function(){try{var s=map&&map.getSource('osm');if(s&&s.setTiles)s.setTiles(['https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png']);}catch(e){}})();",
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

private class KakaoRideMapSurface(context: Context) : FrameLayout(context), LocationListener {
    private val mapView = MapView(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val courseRepo = CourseRepository(context)
    private var kakaoMap: KakaoMap? = null
    private var started = false
    private var skyview = false
    private var liveGpsSeen = false
    private var lastGpsElapsed = 0L
    private var actualGpsHz = 0.0
    private var lastLocation: Location? = null
    private var lastHeading = 0.0

    private val gpsText = TextView(context).apply {
        text = "GPS 대기"
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
        addView(arrow, LayoutParams(dp(45), dp(51), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(52)
        })
        startMapIfNeeded()
    }

    fun setSkyview(enabled: Boolean) {
        skyview = enabled
        applyMapType()
    }

    private fun startMapIfNeeded() {
        if (started || BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) return
        started = true
        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() = Unit
                override fun onMapError(error: Exception) {
                    post {
                        gpsText.text = "카카오 지도 연결 실패"
                        Toast.makeText(context, "카카오 지도 인증을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    kakaoMap = map
                    map.setPoiLanguage("ko")
                    applyMapType()
                    drawCourse()
                    lastLocation?.let { updateCamera(it) }
                }

                override fun getPosition(): LatLng {
                    val last = newestLastKnown()
                    return if (last != null) LatLng.from(last.latitude, last.longitude)
                    else LatLng.from(36.9920, 127.2700)
                }

                override fun getZoomLevel(): Int = 17
            }
        )
        mapView.resume()
        startLocation()
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
            if (points.isEmpty() || points.last() != LatLng.from(p.lat, p.lon)) points += LatLng.from(p.lat, p.lon)
        }
        if (points.size < 2) return

        val styles = RouteLineStyles.from(RouteLineStyle.from(12f, Color.rgb(41, 182, 246), 2f, Color.WHITE))
        val stylesSet = RouteLineStylesSet.from("ride-gpx", styles)
        val segment = RouteLineSegment.from(points).setStyles(styles)
        val options = RouteLineOptions.from("ride-gpx", segment).setStylesSet(stylesSet)
        runCatching { map.routeLineManager.layer.addRouteLine(options) }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocationPermission()) return
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 0f, this, Looper.getMainLooper())
            }
            newestLastKnown()?.let { location ->
                lastLocation = Location(location)
                updateCamera(location)
            }
        }
    }

    private fun newestLastKnown(): Location? {
        if (!hasLocationPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { locationManager.getLastKnownLocation(p) }.getOrNull() }
            .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            .maxByOrNull { it.time }
    }

    override fun onLocationChanged(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (location.provider == LocationManager.GPS_PROVIDER) {
            liveGpsSeen = true
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastGpsElapsed > 0 && now > lastGpsElapsed) {
                val hz = 1000.0 / (now - lastGpsElapsed).toDouble()
                if (hz in 0.05..10.0) actualGpsHz = if (actualGpsHz == 0.0) hz else actualGpsHz * 0.75 + hz * 0.25
            }
            lastGpsElapsed = now
            gpsText.text = if (actualGpsHz > 0.0) "GPS %.1fHz".format(actualGpsHz) else "GPS 수신"
        } else if (liveGpsSeen) {
            return
        }
        lastLocation = Location(location)
        if (location.hasBearing() && location.speed >= 1.0f) lastHeading = location.bearing.toDouble()
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
                CameraAnimation.from(650, true, true)
            )
            map.moveCamera(CameraUpdateFactory.rotateTo(Math.toRadians(lastHeading)))
        }
    }

    fun resumeMap() {
        startMapIfNeeded()
        runCatching { mapView.resume() }
        startLocation()
    }

    fun pauseMap() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { mapView.pause() }
    }

    fun destroyMap() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { mapView.finish() }
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
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
