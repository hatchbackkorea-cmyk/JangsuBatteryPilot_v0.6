package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap

/**
 * Display-only live-location bridge for the MTB map.
 *
 * It deliberately does not write to RideService, FIT logs, battery learning, distance, or RACE data.
 * Its job is only to keep the visible map following a fresh location and to expose enough diagnostics
 * to tell cached/assisted position from a genuinely updating live fix.
 */
object RideLiveLocationBridge {
    private const val KAKAO_TAG = "ride_kakao_map_v03316"
    private const val GPS_FRESH_MS = 4_500L
    private const val ACCEPTED_AGE_MS = 15_000L
    private const val MAX_GPS_ACCURACY_M = 120f
    private const val MAX_ASSISTED_ACCURACY_M = 800f

    private val sessions = WeakHashMap<Activity, Session>()

    fun install(activity: Activity) {
        val existing = sessions[activity]
        if (existing != null) {
            existing.resume()
            return
        }
        val frame = activity.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
        Session(activity, frame).also {
            sessions[activity] = it
            it.resume()
        }
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
    ) : LocationListener {
        private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        private val handler = Handler(Looper.getMainLooper())
        private var registered = false
        private var lastFreshGpsElapsed = 0L
        private var lastAcceptedElapsed = 0L
        private var lastAccepted: Location? = null
        private var lastSource = ""

        private val watchdog = object : Runnable {
            override fun run() {
                updateDiagnosticText()
                if (registered) handler.postDelayed(this, 1_000L)
            }
        }

        fun resume() {
            if (registered || !hasPermission()) return
            var any = false
            try {
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
                    any = true
                }
            } catch (_: Exception) {
            }
            try {
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
                    any = true
                }
            } catch (_: Exception) {
            }
            try {
                manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
                any = true
            } catch (_: Exception) {
            }
            registered = any
            if (registered) {
                handler.removeCallbacks(watchdog)
                handler.post(watchdog)
            } else {
                setStatus("GPS 없음 · 위치 제공자 확인 필요")
            }
        }

        fun pause() {
            if (!registered) return
            runCatching { manager.removeUpdates(this) }
            registered = false
            handler.removeCallbacks(watchdog)
        }

        fun destroy() = pause()

        private fun hasPermission(): Boolean =
            activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        override fun onLocationChanged(location: Location) {
            if (!isFreshUsable(location)) return

            val nowElapsed = SystemClock.elapsedRealtime()
            val isGps = location.provider == LocationManager.GPS_PROVIDER
            if (isGps) {
                if (location.hasAccuracy() && location.accuracy > MAX_GPS_ACCURACY_M) return
                lastFreshGpsElapsed = nowElapsed
            } else {
                if (location.hasAccuracy() && location.accuracy > MAX_ASSISTED_ACCURACY_M) return
                // Once a fresh GPS stream exists, assisted providers must not pull the map backwards.
                if (nowElapsed - lastFreshGpsElapsed <= GPS_FRESH_MS) return
            }

            lastAccepted = Location(location)
            lastAcceptedElapsed = nowElapsed
            lastSource = if (isGps) "GPS" else when (location.provider) {
                LocationManager.NETWORK_PROVIDER -> "보조 위치"
                LocationManager.PASSIVE_PROVIDER -> "PASSIVE"
                else -> location.provider ?: "위치"
            }

            pushToCyclOsm(location)
            pushToVisibleKakao(location, assisted = !isGps)
            updateDiagnosticText()
        }

        private fun isFreshUsable(location: Location): Boolean {
            if (!location.latitude.isFinite() || !location.longitude.isFinite()) return false
            if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
            val age = if (location.time > 0L) System.currentTimeMillis() - location.time else 0L
            return location.time <= 0L || age in 0..ACCEPTED_AGE_MS
        }

        private fun pushToCyclOsm(location: Location) {
            val smooth = frame.findViewWithTag<View>(RideSmoothMapWebView.TAG_SMOOTH_MAP) as? RideSmoothMapWebView ?: return
            if (smooth.visibility != View.VISIBLE) return
            val speed = if (location.hasSpeed()) (location.speed * 3.6).toDouble().coerceIn(0.0, 160.0) else 0.0
            val bearing = if (location.hasBearing()) location.bearing.toDouble() else 0.0
            val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0
            val js = String.format(
                Locale.US,
                "window.rcSetFix&&window.rcSetFix(%.7f,%.7f,(typeof latestKm==='number'?latestKm:0),%.2f,%.2f,%.1f,false);",
                location.latitude,
                location.longitude,
                speed,
                bearing,
                accuracy
            )
            smooth.evaluateJavascript(js, null)
        }

        private fun pushToVisibleKakao(location: Location, assisted: Boolean) {
            val listenerView = findTaggedLocationListener(frame, KAKAO_TAG) ?: return
            if ((listenerView as? View)?.visibility != View.VISIBLE) return

            // Kakao's own surface intentionally ignores NETWORK after it has seen a GPS provider.
            // When GPS has gone stale, forward the fresh assisted fix as display-only GPS so the
            // visible map can recover instead of remaining frozen at an old building.
            val delivered = if (!assisted) Location(location) else cloneAsGps(location)
            runCatching { listenerView.onLocationChanged(delivered) }
        }

        private fun cloneAsGps(source: Location): Location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = source.latitude
            longitude = source.longitude
            time = source.time
            elapsedRealtimeNanos = source.elapsedRealtimeNanos
            if (source.hasAccuracy()) accuracy = source.accuracy
            if (source.hasAltitude()) altitude = source.altitude
            if (source.hasSpeed()) speed = source.speed
            if (source.hasBearing()) bearing = source.bearing
        }

        private fun findTaggedLocationListener(root: View, tag: String): LocationListener? {
            if (root.tag?.toString() == tag && root is LocationListener) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    findTaggedLocationListener(root.getChildAt(i), tag)?.let { return it }
                }
            }
            return null
        }

        private fun updateDiagnosticText() {
            val loc = lastAccepted
            if (loc == null || lastAcceptedElapsed == 0L) {
                setStatus("마지막 위치 표시 중 · 실시간 GPS 대기")
                return
            }
            val ageSec = ((SystemClock.elapsedRealtime() - lastAcceptedElapsed).coerceAtLeast(0L) / 100L) / 10.0
            val acc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()}m" else "정확도 —"
            val speed = if (loc.hasSpeed()) " · ${String.format(Locale.KOREA, "%.0f", loc.speed * 3.6)}km/h" else ""
            val coords = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
            val state = if (ageSec <= 3.0) "수신 중" else "갱신 지연"
            setStatus("$state · $lastSource · $coords · $acc · ${String.format(Locale.KOREA, "%.1f", ageSec)}초 전$speed")
        }

        private fun setStatus(text: String) {
            activity.runOnUiThread {
                activity.findViewById<TextView?>(R.id.tvRideMapPreviewStatus)?.text = text
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
}
