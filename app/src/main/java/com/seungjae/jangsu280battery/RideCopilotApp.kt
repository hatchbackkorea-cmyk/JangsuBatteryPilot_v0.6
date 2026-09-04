package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView

/**
 * App-wide lightweight UI helpers and map SDK bootstrap.
 */
class RideCopilotApp : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        KakaoMapSdkGate.ensureInitialized(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is MainActivity, is SettingsActivity -> activity.window.decorView.post { installVoiceBoostControl(activity) }
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
                AppSettings.prefs(activity).edit()
                    .putBoolean(AppSettings.KEY_VOICE_VOLUME_BOOST, checked)
                    .apply()
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

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val TAG_SWITCH = "voice_volume_boost_switch_v0334"
        private const val TAG_HINT = "voice_volume_boost_hint_v0334"
    }
}
