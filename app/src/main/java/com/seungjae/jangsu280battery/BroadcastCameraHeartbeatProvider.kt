package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Keeps an auto-registered broadcast camera's course position fresh while Camera Gate is open. */
class BroadcastCameraHeartbeatProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val jobs = WeakHashMap<Activity, Job>()

    private data class Job(
        val manager: LocationManager,
        val listener: LocationListener,
        val tick: Runnable,
        @Volatile var latest: Location? = null,
    )

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        val assignment = TimingOperatorStore.current(activity) ?: return
        if (!TimingOperatorStore.isBroadcastRole(assignment.role)) return
        start(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) stop(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stop(activity)
    }

    private fun start(activity: CameraGateHighSpeedActivity) {
        stop(activity)
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val holder = arrayOfNulls<Job>(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.latitude != 0.0 || location.longitude != 0.0) holder[0]?.latest = location
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val tick = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    stop(activity)
                    return
                }
                val assignment = TimingOperatorStore.current(activity)
                if (assignment == null || !TimingOperatorStore.isBroadcastRole(assignment.role)) {
                    stop(activity)
                    return
                }
                val job = jobs[activity] ?: return
                val location = job.latest ?: bestLastKnown(activity, manager)
                if (location != null) {
                    job.latest = location
                    executor.execute {
                        runCatching { BroadcastCameraClient.heartbeat(activity.applicationContext, assignment, location) }
                    }
                }
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        val job = Job(manager, listener, tick, bestLastKnown(activity, manager))
        holder[0] = job
        jobs[activity] = job
        runCatching {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            }
        }
        main.post(tick)
    }

    private fun stop(activity: Activity) {
        val job = jobs.remove(activity) ?: return
        main.removeCallbacks(job.tick)
        runCatching { job.manager.removeUpdates(job.listener) }
    }

    private fun bestLastKnown(activity: Activity, manager: LocationManager): Location? {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .maxByOrNull { it.time }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val HEARTBEAT_MS = 3_500L
    }
}
