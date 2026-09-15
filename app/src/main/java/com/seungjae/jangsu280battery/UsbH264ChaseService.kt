package com.seungjae.jangsu280battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Foreground owner for USB H.264 CHASE.
 *
 * The Activity is only UI. This service owns the UVC source plus WebRTC publisher, so locking the
 * phone, turning the screen off, or navigating away does not interrupt CHASE. The external H.264
 * bitstream still goes straight to the existing TimeGate transport with no decode/re-encode.
 */
class UsbH264ChaseService : Service() {
    companion object {
        private const val CHANNEL_ID = "timegate_usb_h264_chase"
        private const val NOTIFICATION_ID = 34153
        const val ACTION_START = "com.seungjae.jangsu280battery.USB_H264_CHASE_START"
        const val ACTION_STOP = "com.seungjae.jangsu280battery.USB_H264_CHASE_STOP"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, UsbH264ChaseService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, UsbH264ChaseService::class.java)
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var source: UsbH264ChaseSource? = null
    private var webRtc: CameraGateWebRtcStreamer? = null
    private var fallback: CameraGateBroadcastStreamer? = null
    private var assignment: TimingOperatorStore.Assignment? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val statusTicker = object : Runnable {
        override fun run() {
            updateNotification(UsbH264ChaseRuntime.status)
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground("USB H.264 CHASE 준비 중")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:usb-h264-chase").apply {
            setReferenceCounted(false)
            acquire()
        }
        main.post(statusTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val current = TimingOperatorStore.current(this)
        val validUsbChase = current != null &&
            current.role.equals("CHASE", ignoreCase = true) &&
            ChaseVideoInputStore.get(this) == ChaseVideoInputMode.USB_H264
        if (!validUsbChase) {
            stopSelf()
            return START_NOT_STICKY
        }

        assignment = current
        CameraGateBroadcastBridge.setExternalSourceRequired(true)
        CameraGateBroadcastBridge.bindPublisherAvailabilityController { available ->
            main.post {
                if (available) ensurePublishers() else closePublishers()
                updateNotification(UsbH264ChaseRuntime.status)
            }
        }
        if (UsbH264ChaseRuntime.streaming) ensurePublishers() else closePublishers()
        if (source == null) source = UsbH264ChaseSource(applicationContext).also { it.start() }
        return START_STICKY
    }

    private fun ensurePublishers() {
        val a = assignment ?: TimingOperatorStore.current(this) ?: return
        if (webRtc == null) {
            webRtc = CameraGateWebRtcStreamer(applicationContext, a) {
                main.post { ensureFallback() }
            }
        }
        CameraGateBroadcastBridge.bindWebRtc(webRtc)
        CameraGateBroadcastBridge.bindFallback(fallback)
    }

    private fun ensureFallback() {
        if (!UsbH264ChaseRuntime.streaming) return
        val a = assignment ?: TimingOperatorStore.current(this) ?: return
        if (fallback == null) fallback = CameraGateBroadcastStreamer(applicationContext, a)
        CameraGateBroadcastBridge.bindFallback(fallback)
    }

    private fun closePublishers() {
        fallback?.close()
        fallback = null
        webRtc?.close()
        webRtc = null
        CameraGateBroadcastBridge.bindFallback(null)
        CameraGateBroadcastBridge.bindWebRtc(null)
    }

    override fun onDestroy() {
        main.removeCallbacks(statusTicker)
        source?.stop()
        source = null
        closePublishers()
        CameraGateBroadcastBridge.bindPublisherAvailabilityController(null)
        CameraGateBroadcastBridge.setExternalAvailable(false)
        // Keep the phone-camera path suppressed while this assignment still says USB_H264.
        val current = TimingOperatorStore.current(this)
        val stillUsbChase = current != null &&
            current.role.equals("CHASE", ignoreCase = true) &&
            ChaseVideoInputStore.get(this) == ChaseVideoInputMode.USB_H264
        if (!stillUsbChase) CameraGateBroadcastBridge.setExternalSourceRequired(false)
        runCatching { wakeLock?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "TimeGate CHASE 중계",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "USB 액션캠 H.264 체이스 중계를 화면이 꺼져도 유지합니다."
                setShowBadge(false)
            }
        )
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            34153,
            Intent(this, BroadcastCameraEnrollmentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            34154,
            Intent(this, UsbH264ChaseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_battery_pilot)
            .setContentTitle("TimeGate · CHASE 송출 중")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "CHASE 종료", stopIntent).build())
            .build()
    }
}
