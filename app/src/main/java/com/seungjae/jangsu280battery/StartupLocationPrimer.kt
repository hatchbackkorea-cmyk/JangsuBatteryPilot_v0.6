package com.seungjae.jangsu280battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import java.util.Locale

/**
 * Display-only startup location primer.
 *
 * Android keeps a last-known location cache that can have been populated before this app starts
 * by the system or another location-using app. When the rider opens Copilot indoors and a fresh
 * GNSS fix is not immediately available, show that cached position first instead of an empty map.
 * A tiny app-side fallback cache is also kept for cases where Android returns no system cache.
 *
 * This never writes into RideService logs, learning data, distance, or battery prediction.
 * The normal live GPS stream replaces this visual position as soon as it arrives.
 */
object StartupLocationPrimer {
    private const val PREFS = "startup_location_cache"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_TIME = "time"
    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_BEARING = "bearing"
    private const val KEY_HAS_BEARING = "has_bearing"

    fun prime(context: Context, map: RideSmoothMapWebView) {
        if (!hasLocationPermission(context)) return
        val location = newestSystemLastKnown(context) ?: readFallback(context) ?: return
        saveFallback(context, location)
        injectWhenReady(map, location, 0)
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

    private fun isUsable(location: Location): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

    private fun saveFallback(context: Context, location: Location) {
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
        if (!map.isAttachedToWindow || attempt > 20) return
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
