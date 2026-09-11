package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.kakao.vectormap.KakaoMapSdk
import java.io.File

/** Application-level TimeGate UI helpers plus Kakao Maps SDK initialization. */
class RideCopilotApp : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        bootstrapRaceServer()
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        }
        registerActivityLifecycleCallbacks(this)
    }

    private fun bootstrapRaceServer() {
        val published = BuildConfig.DEFAULT_RACE_SERVER_URL.trim().trimEnd('/')
        if (!published.startsWith("https://") && !published.startsWith("http://")) return
        val prefs = getSharedPreferences("race_server_route_v1", MODE_PRIVATE)
        val current = prefs.getString("event_server", "").orEmpty().trim().trimEnd('/')
        if (current.isBlank() || current.equals(OLD_RACE_SERVER_URL, ignoreCase = true)) {
            prefs.edit().putString("event_server", published).apply()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        applyTimeGateSystemBars(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        applyTimeGateSystemBars(activity)
        TimeGateHeaderNormalizer.install(activity)
        when (activity) {
            is BikeModeChooserActivity -> activity.window.decorView.post {
                RaceLauncherUiInstaller.install(activity)
            }
            is RaceActivity -> {
                if (recoverOrResetRaceTiming(activity)) return
                activity.window.decorView.post {
                    TimeGateProgrammaticSkin.install(activity)
                    RaceTrackBuilderUiInstaller.install(activity)
                    RaceNameLabelUiInstaller.install(activity)
                    RaceProfileServerSync.resume(activity)
                    RaceSavedCourseBackfill.sync(activity)
                    RaceLiveLapDisplayInstaller.install(activity)
                    RaceEventExitUiInstaller.install(activity)
                    RaceDebugUiInstaller.install(activity)
                }
            }
            is RaceSavedCoursesActivity -> activity.window.decorView.post {
                RaceEventCourseQuickAccessInstaller.install(activity)
            }
            is CourseActivity -> activity.window.decorView.post {
                RaceEventCourseQuickAccessInstaller.install(activity)
            }
            is RaceTrackBuilderActivity -> activity.window.decorView.post {
                TimeGateProgrammaticSkin.install(activity)
                RaceTrackGpsQualityOverlay.install(activity)
                RaceTrackDraftAutoSync.install(activity)
                RaceSavedCourseBackfill.sync(activity)
                RaceSavedCourseBrowserLauncher.install(activity)
                RaceSavedCourseBrowserBottomFix.apply(activity)
                RaceSavedCourseInlineFix.apply(activity)
            }
            is MainActivity -> activity.window.decorView.post {
                installVoiceBoostControl(activity)
                RideWarningOverlayController.install(activity)
                RideMapProviderController.install(activity)
                RideLiveLocationBridge.install(activity)
            }
            is SettingsActivity -> activity.window.decorView.post { installVoiceBoostControl(activity) }
        }
    }

    /**
     * APK replacement kills the foreground timing service but SharedPreferences survive the install.
     * Previously RaceActivity then reopened the persisted ARMED screen (for example 12/14156m)
     * without any service receiving GPS, and START only reopened that dead screen instead of syncing
     * the current event GPX. A genuinely live timing service writes raw GPS several times per second,
     * so a stale ARMED heartbeat is safe to discard and re-arm from the event server.
     *
     * RUNNING is different: if its heartbeat is stale we try to recover the service rather than
     * discard an in-progress race.
     *
     * @return true when the Activity is being recreated after clearing stale ARMED state.
     */
    private fun recoverOrResetRaceTiming(activity: RaceActivity): Boolean {
        val store = RaceDataStore(activity)
        val snap = store.snapshot()
        if (snap.state !in setOf("ARMED", "RUNNING")) return false

        val raw = snap.runId.takeIf { it.isNotBlank() }
            ?.let { File(activity.filesDir, "race/raw/$it.jsonl") }
        val heartbeatAt = raw?.takeIf { it.exists() }?.lastModified() ?: 0L
        val ageMs = if (heartbeatAt > 0L) {
            (System.currentTimeMillis() - heartbeatAt).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        val stale = ageMs > TIMING_HEARTBEAT_STALE_MS
        if (!stale) return false

        if (snap.state == "RUNNING") {
            runCatching {
                activity.startForegroundService(Intent(activity, RaceTimingService::class.java))
            }
            return false
        }

        runCatching { activity.stopService(Intent(activity, RaceTimingService::class.java)) }
        store.clearActiveConfig()
        store.writeSnapshot(
            snap.copy(
                state = "STOPPED",
                courseId = "",
                courseName = "",
                runId = "",
                runNumber = 0,
                startedAtMs = 0L,
                lastGateAtMs = 0L,
                elapsedMs = 0L,
                routeM = 0.0,
                deltaMs = null,
                nextGateIndex = 0,
                currentSector = "",
                gpsAccuracyM = 0.0,
                sectors = emptyList(),
                serverStatus = "이전 START 대기 초기화 · START를 다시 눌러 최신 경기맵을 동기화합니다."
            )
        )
        activity.window.decorView.post { activity.recreate() }
        return true
    }

    private fun applyTimeGateSystemBars(activity: Activity) {
        val window = activity.window
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        @Suppress("DEPRECATION")
        run {
            val blocked = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            var flags = window.decorView.systemUiVisibility and blocked.inv()
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is RaceActivity) RaceProfileServerSync.pause(activity)
        if (activity is RaceTrackBuilderActivity) {
            RaceTrackGpsQualityOverlay.pause(activity)
            RaceTrackDraftAutoSync.pause(activity)
        }
        if (activity is MainActivity) {
            RideLiveLocationBridge.pause(activity)
            RideMapProviderController.pause(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        TimeGateHeaderNormalizer.uninstall(activity)
        TimeGateProgrammaticSkin.uninstall(activity)
        if (activity is RaceActivity) {
            RaceProfileServerSync.pause(activity)
            RaceNameLabelUiInstaller.uninstall(activity)
            RaceLiveLapDisplayInstaller.uninstall(activity)
            RaceEventExitUiInstaller.uninstall(activity)
            RaceDebugUiInstaller.uninstall(activity)
        }
        if (activity is RaceTrackBuilderActivity) {
            RaceTrackGpsQualityOverlay.destroy(activity)
            RaceTrackDraftAutoSync.pause(activity)
        }
        if (activity is MainActivity) {
            RideLiveLocationBridge.destroy(activity)
            RideWarningOverlayController.destroy(activity)
            RideMapProviderController.destroy(activity)
        }
    }

    private fun installVoiceBoostControl(activity: Activity) {
        val anchor = activity.findViewById<Switch?>(R.id.switchPageVoice)
            ?: activity.findViewById(R.id.switchSettingsVoice)
            ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(TAG_SWITCH) != null) return

        val index = parent.indexOfChild(anchor)
        val boostSwitch = Switch(activity).apply {
            tag = TAG_SWITCH
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48f))
            text = "음성 안내 볼륨 자동 최대"
            textSize = 15f
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = AppSettings.voiceVolumeBoostEnabled(activity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.prefs(activity).edit().putBoolean(AppSettings.KEY_VOICE_VOLUME_BOOST, checked).apply()
            }
        }
        parent.addView(boostSwitch, index + 1)

        val hint = TextView(activity).apply {
            tag = TAG_HINT
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = "안내할 때만 미디어 볼륨을 최대로 올리고, 음성이 끝나면 원래 볼륨으로 돌아옵니다."
            textSize = 11f
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(activity, 4f))
        }
        parent.addView(hint, index + 2)
    }

    private fun dp(activity: Activity, value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        private const val TAG_SWITCH = "voice_volume_boost_switch_v0334"
        private const val TAG_HINT = "voice_volume_boost_hint_v0334"
        private const val TIMING_HEARTBEAT_STALE_MS = 4_000L
        private const val OLD_RACE_SERVER_URL = "https://rider-control-center.tail8152aa.ts.net"
    }
}
