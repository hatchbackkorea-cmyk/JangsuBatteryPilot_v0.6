package com.seungjae.jangsu280battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Display-only startup location primer.
 *
 * Startup order:
 * 1) Android system last-known location
 * 2) Copilot's own last startup/location cache
 * 3) fresh NETWORK/PASSIVE location (cell tower + Wi-Fi assisted)
 *
 * RideSmoothMapWebView keeps requesting real GPS in parallel. As soon as a GPS fix arrives,
 * the normal live navigation path takes over. Bootstrap locations never enter RideService logs,
 * learning data, distance, battery prediction, or measured GPS Hz.
 */
object StartupLocationPrimer {
    private const val PREFS = "startup_location_cache"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_TIME = "time"
    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_BEARING = "bearing"
    private const val KEY_HAS_BEARING = "has_bearing"
    private const val BOOTSTRAP_LISTEN_MS = 20_000L
    private const val MAX_NETWORK_ACCURACY_M = 2_500f

    fun prime(context: Context, map: RideSmoothMapWebView) {
        if (!hasLocationPermission(context)) return

        // Show something immediately if Android or Copilot already knows a location.
        val cached = newestSystemLastKnown(context) ?: readFallback(context)
        if (cached != null) {
            remember(context, cached)
            injectWhenReady(map, cached, 0)
        }

        // GPS is requested by RideSmoothMapWebView. In parallel, ask Android's network/passive
        // providers for a short startup window so an indoor launch can still get a usable position.
        requestIndoorBootstrap(context, map)
    }

    private fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun newestSystemLastKnown(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            runCatching { addAll(manager.getProviders(true)) }
            runCatching { addAll(manager.getProviders(false)) }
        }.distinct()

        val candidates = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter(::isUsable)

        return candidates.maxWithOrNull(
            compareBy<Location> { if (it.time > 0L) it.time else Long.MIN_VALUE }
                .thenBy { if (it.hasAccuracy()) -it.accuracy.toDouble() else -10_000.0 }
        )
    }

    private fun requestIndoorBootstrap(context: Context, map: RideSmoothMapWebView) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (!hasLocationPermission(context)) return

        var firstFreshInjected = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isUsable(location)) return
                if (location.hasAccuracy() && location.accuracy > MAX_NETWORK_ACCURACY_M) return

                // Persist every usable startup fix. This gives us our own fallback next launch,
                // even if Android happens to return an empty last-known cache later.
                remember(context, location)

                // Only the first fresh assisted fix is injected. Real GPS continues independently
                // in RideSmoothMapWebView and will overwrite this visual anchor as soon as it arrives.
                if (!firstFreshInjected) {
                    firstFreshInjected = true
                    injectWhenReady(map, location, 0)
                }
            }
        }

        var registered = false
        try {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                registered = true
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        // Passive updates can contain a location already being produced by Android/system apps,
        // and add no extra GNSS demand of their own.
        try {
            manager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                1000L,
                0f,
                listener,
                Looper.getMainLooper()
            )
            registered = true
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        if (registered) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { manager.removeUpdates(listener) }
            }, BOOTSTRAP_LISTEN_MS)
        }
    }

    private fun isUsable(location: Location): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

    fun remember(context: Context, location: Location) {
        if (!isUsable(location)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(location.longitude))
            .putLong(KEY_TIME, location.time)
            .putFloat(KEY_ACCURACY, if (location.hasAccuracy()) location.accuracy else -1f)
            .putBoolean(KEY_HAS_BEARING, location.hasBearing())
            .putFloat(KEY_BEARING, if (location.hasBearing()) location.bearing else 0f)
            .apply()
    }

    private fun readFallback(context: Context): Location? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LON, 0L))
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        return Location("copilot-startup-cache").apply {
            latitude = lat
            longitude = lon
            time = prefs.getLong(KEY_TIME, 0L)
            val accuracy = prefs.getFloat(KEY_ACCURACY, -1f)
            if (accuracy >= 0f) this.accuracy = accuracy
            if (prefs.getBoolean(KEY_HAS_BEARING, false)) {
                bearing = prefs.getFloat(KEY_BEARING, 0f)
            }
        }
    }

    private fun injectWhenReady(map: RideSmoothMapWebView, location: Location, attempt: Int) {
        if (!map.isAttachedToWindow || attempt > 24) return
        map.evaluateJavascript("typeof window.rcSetFix === 'function'") { raw ->
            if (raw == "true") {
                val heading = if (location.hasBearing()) location.bearing.toDouble() else 0.0
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0
                val js = String.format(
                    Locale.US,
                    "window.rcSetFix && window.rcSetFix(%.7f,%.7f,0.0,0.0,%.2f,%.1f,false);",
                    location.latitude,
                    location.longitude,
                    heading,
                    accuracy
                )
                map.evaluateJavascript(js, null)
            } else {
                map.postDelayed({ injectWhenReady(map, location, attempt + 1) }, 250L)
            }
        }
    }
}
