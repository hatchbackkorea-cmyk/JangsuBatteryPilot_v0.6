package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
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

/** Application-level TimeGate UI helpers plus Kakao Maps SDK initialization. */
class RideCopilotApp : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        }
        registerActivityLifecycleCallbacks(this)
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
            is RaceActivity -> activity.window.decorView.post {
                TimeGateProgrammaticSkin.install(activity)
                RaceTrackBuilderUiInstaller.install(activity)
                RaceNameLabelUiInstaller.install(activity)
                RaceProfileServerSync.resume(activity)
                RaceSavedCourseBackfill.sync(activity)
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
            is AdminCenterActivity -> activity.window.decorView.post {
                SystemDiagnosticsUiInstaller.install(activity)
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
    }
}
