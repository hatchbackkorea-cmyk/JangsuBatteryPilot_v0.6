package com.seungjae.jangsu280battery

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

/** Foreground GPS recorder for RaceChrono-style phone course creation. */
class RaceTrackRecorderService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.seungjae.jangsu280battery.TRACK_START"
        const val ACTION_PAUSE = "com.seungjae.jangsu280battery.TRACK_PAUSE"
        const val ACTION_RESUME = "com.seungjae.jangsu280battery.TRACK_RESUME"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.TRACK_STOP"
        const val ACTION_UPDATE = "com.seungjae.jangsu280battery.TRACK_UPDATE"
        const val EXTRA_DRAFT_ID = "draft_id"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ELE = "ele"
        const val EXTRA_TIME = "time"
        const val EXTRA_ACC = "acc"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_ROUTE_M = "route_m"
        const val EXTRA_STATE = "state"
        private const val CHANNEL = "race_track_builder"
        private const val NOTIF = 8812
    }

    private lateinit var lm: LocationManager
    private lateinit var store: RaceTrackDraftStore
    private var draftId = ""
    private var state = RaceTrackDraftStore.STATE_STOPPED
    private var last: Location? = null
    private var routeM = 0.0

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        store = RaceTrackDraftStore(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "RACE 코스 기록", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                draftId = intent.getStringExtra(EXTRA_DRAFT_ID).orEmpty()
                val d = store.active()?.takeIf { it.id == draftId } ?: return START_NOT_STICKY
                state = RaceTrackDraftStore.STATE_RECORDING
                routeM = d.distanceM
                last = store.lastPoint(draftId)?.let { p -> Location("draft").apply { latitude = p.lat; longitude = p.lon; time = p.timeMs } }
                startForeground(NOTIF, notification("코스 GPS 기록 중"))
                requestGps()
                broadcastState()
            }
            ACTION_RESUME -> {
                val d = store.active() ?: return START_NOT_STICKY
                draftId = d.id
                state = RaceTrackDraftStore.STATE_RECORDING
                store.setState(draftId, state)
                routeM = d.distanceM
                last = store.lastPoint(draftId)?.let { p -> Location("draft").apply { latitude = p.lat; longitude = p.lon; time = p.timeMs } }
                startForeground(NOTIF, notification("코스 GPS 기록 재개"))
                requestGps()
                broadcastState()
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_STOP -> stopRecording()
            else -> recover()
        }
        return START_STICKY
    }

    private fun recover() {
        val d = store.active() ?: return
        draftId = d.id
        state = d.state
        routeM = d.distanceM
        if (state == RaceTrackDraftStore.STATE_RECORDING) {
            startForeground(NOTIF, notification("코스 GPS 기록 복구"))
            requestGps()
        }
    }

    private fun requestGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching { lm.removeUpdates(this) }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this) }
    }

    override fun onLocationChanged(location: Location) {
        if (state != RaceTrackDraftStore.STATE_RECORDING || draftId.isBlank()) return
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (location.hasAccuracy() && location.accuracy > 60f) return
        val p = last
        if (p != null) {
            val d = Geo.distanceMeters(p.latitude, p.longitude, location.latitude, location.longitude)
            val dt = (location.time - p.time).coerceAtLeast(0L)
            if (d < 0.8 && dt < 900L) return
            if (d < 120.0 || dt >= 3000L) routeM += d
        }
        val saved = store.append(draftId, location, routeM)
        last = Location(location)
        sendBroadcast(Intent(ACTION_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_DRAFT_ID, draftId); putExtra(EXTRA_LAT, saved.lat); putExtra(EXTRA_LON, saved.lon)
            putExtra(EXTRA_ELE, saved.ele); putExtra(EXTRA_TIME, saved.timeMs); putExtra(EXTRA_ACC, saved.accuracyM)
            putExtra(EXTRA_BEARING, saved.bearingDeg); putExtra(EXTRA_ROUTE_M, saved.routeM); putExtra(EXTRA_STATE, state)
        })
        updateNotification("기록 중 · %.2f km · GPS ±%dm".format(routeM / 1000.0, saved.accuracyM.toInt()))
    }

    private fun pauseRecording() {
        if (draftId.isBlank()) draftId = store.active()?.id.orEmpty()
        if (draftId.isBlank()) return
        state = RaceTrackDraftStore.STATE_PAUSED
        store.setState(draftId, state)
        runCatching { lm.removeUpdates(this) }
        updateNotification("코스 기록 일시정지")
        broadcastState()
    }

    private fun stopRecording() {
        if (draftId.isBlank()) draftId = store.active()?.id.orEmpty()
        if (draftId.isNotBlank()) store.setState(draftId, RaceTrackDraftStore.STATE_STOPPED)
        state = RaceTrackDraftStore.STATE_STOPPED
        runCatching { lm.removeUpdates(this) }
        broadcastState()
        stopForeground(true)
        stopSelf()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_UPDATE).apply {
            setPackage(packageName); putExtra(EXTRA_DRAFT_ID, draftId); putExtra(EXTRA_STATE, state); putExtra(EXTRA_ROUTE_M, routeM)
        })
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_battery_pilot)
        .setContentTitle("Ride Copilot · RACE 코스 만들기")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 8812, Intent(this, RaceTrackBuilderActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF, notification(text))
    }
}
