package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Glanceable live GPS-quality indicator for RACE course creation.
 *
 * Android location providers expose estimated horizontal accuracy in metres rather than a raw
 * "GPS signal strength" value. We convert that accuracy into five practical field levels and
 * show both the level and the actual +/- metre estimate. The recorder already broadcasts the
 * saved point accuracy, so this overlay does not start a second location listener or alter the
 * recorded track.
 */
object RaceTrackGpsQualityOverlay {
    private const val TAG = "race_track_gps_quality_overlay_v1"
    private const val STALE_AFTER_MS = 5_000L

    private var appContext: Context? = null
    private var receiverRegistered = false
    private var activeActivity = WeakReference<RaceTrackBuilderActivity>(null)
    private var latestAccuracyM: Double? = null
    private var latestFixElapsedMs = 0L
    private var latestState = RaceTrackDraftStore.STATE_STOPPED

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RaceTrackRecorderService.ACTION_UPDATE) return

            val state = intent.getStringExtra(RaceTrackRecorderService.EXTRA_STATE)
            if (!state.isNullOrBlank()) {
                if (state == RaceTrackDraftStore.STATE_RECORDING && latestState != RaceTrackDraftStore.STATE_RECORDING && !intent.hasExtra(RaceTrackRecorderService.EXTRA_ACC)) {
                    latestAccuracyM = null
                    latestFixElapsedMs = 0L
                }
                latestState = state
                if (state != RaceTrackDraftStore.STATE_RECORDING) {
                    latestAccuracyM = null
                    latestFixElapsedMs = 0L
                }
            }

            if (intent.hasExtra(RaceTrackRecorderService.EXTRA_ACC)) {
                latestAccuracyM = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_ACC, Double.NaN)
                    .takeIf { it.isFinite() && it > 0.0 }
                latestFixElapsedMs = if (latestAccuracyM != null) SystemClock.elapsedRealtime() else 0L
            }

            activeActivity.get()?.let { activity ->
                activity.runOnUiThread { render(activity) }
            }
        }
    }

    fun install(activity: RaceTrackBuilderActivity) {
        activeActivity = WeakReference(activity)
        ensureReceiver(activity.application)
        ensureView(activity)
        render(activity)
        startTicker()
    }

    fun pause(activity: RaceTrackBuilderActivity) {
        if (activeActivity.get() === activity) activeActivity.clear()
    }

    fun destroy(activity: RaceTrackBuilderActivity) {
        val root = activity.findViewById<ViewGroup?>(android.R.id.content)
        root?.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        if (activeActivity.get() === activity) activeActivity.clear()
    }

    private fun ensureReceiver(application: Application) {
        if (receiverRegistered) return
        appContext = application.applicationContext
        val filter = IntentFilter(RaceTrackRecorderService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            application.registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(updateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun ensureView(activity: Activity): TextView? {
        val root = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return null
        root.findViewWithTag<TextView>(TAG)?.let { return it }

        val badge = TextView(activity).apply {
            tag = TAG
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7))
            elevation = dp(activity, 12).toFloat()
            maxLines = 2
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 58),
            Gravity.TOP
        ).apply {
            leftMargin = dp(activity, 12)
            rightMargin = dp(activity, 12)
            topMargin = dp(activity, 64)
        }
        root.addView(badge, lp)
        return badge
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.post(object : Runnable {
            override fun run() {
                val activity = activeActivity.get()
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    render(activity)
                    mainHandler.postDelayed(this, 1_000L)
                } else {
                    tickerRunning = false
                }
            }
        })
    }

    private fun render(activity: RaceTrackBuilderActivity) {
        val badge = ensureView(activity) ?: return

        if (latestState != RaceTrackDraftStore.STATE_RECORDING) {
            badge.text = "GPS 대기 · 새 기록을 시작하면 실시간 감도 표시"
            applyBackground(activity, badge, Color.rgb(63, 70, 78))
            return
        }

        val accuracy = latestAccuracyM
        val ageMs = if (latestFixElapsedMs > 0L) SystemClock.elapsedRealtime() - latestFixElapsedMs else Long.MAX_VALUE
        if (accuracy == null || latestFixElapsedMs == 0L) {
            badge.text = "GPS 찾는 중… · 좌표 수신 대기"
            applyBackground(activity, badge, Color.rgb(55, 71, 79))
            return
        }
        if (ageMs > STALE_AFTER_MS) {
            val seconds = (ageMs / 1000L).coerceAtLeast(5L)
            badge.text = "GPS 1/5 · 수신 끊김 · 최근 좌표 ${seconds}초 전\n트랩 저장 비추천"
            applyBackground(activity, badge, Color.rgb(183, 28, 28))
            return
        }

        val quality = when {
            accuracy <= 5.0 -> Quality(5, "최상", "트랩 저장 적합", Color.rgb(27, 94, 32))
            accuracy <= 10.0 -> Quality(4, "좋음", "트랩 저장 가능", Color.rgb(46, 125, 50))
            accuracy <= 20.0 -> Quality(3, "보통", "잠시 정지 후 확인 권장", Color.rgb(130, 105, 0))
            accuracy <= 50.0 -> Quality(2, "약함", "좌표 오차 큼", Color.rgb(230, 81, 0))
            else -> Quality(1, "매우 약함", "트랩 저장 비추천", Color.rgb(183, 28, 28))
        }

        val metres = if (accuracy < 10.0) {
            String.format(Locale.KOREA, "%.1f", accuracy)
        } else {
            String.format(Locale.KOREA, "%.0f", accuracy)
        }
        badge.text = "GPS ${quality.level}/5 · ${quality.label} · 오차 ±${metres}m\n${quality.hint}"
        applyBackground(activity, badge, quality.color)
    }

    private fun applyBackground(activity: Activity, view: TextView, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, 12).toFloat()
            setColor(color)
            setStroke(dp(activity, 1), Color.argb(150, 255, 255, 255))
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private data class Quality(
        val level: Int,
        val label: String,
        val hint: String,
        val color: Int
    )
}
