package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * Keeps the launcher simple: RACE itself owns profile entry/save and event join.
 * The camera gate beta stays separate from official GPS timing until its error is measured.
 */
object RaceLauncherUiInstaller {
    private const val TAG_RACE_BUTTON = "race_mode_launcher_button_v2"
    private const val TAG_CAMERA_BUTTON = "camera_gate_beta_launcher_button_v1"

    fun install(activity: Activity) {
        val anchor = activity.findViewById<View?>(R.id.btnBikeModeEmtb) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var insertIndex = parent.indexOfChild(anchor).coerceAtLeast(0)
        if (parent.findViewWithTag<View>(TAG_RACE_BUTTON) == null) {
            val raceButton = Button(activity).apply {
                tag = TAG_RACE_BUTTON
                text = "🏁 RACE MODE\n타임어택 · 섹터 · LIVE"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(92)
                ).apply { topMargin = dp(12) }
                setOnClickListener {
                    activity.startActivity(Intent(activity, RaceActivity::class.java))
                }
            }
            parent.addView(raceButton, insertIndex)
            insertIndex += 1
        } else {
            insertIndex = parent.indexOfChild(parent.findViewWithTag(TAG_RACE_BUTTON)) + 1
        }

        if (parent.findViewWithTag<View>(TAG_CAMERA_BUTTON) == null) {
            val cameraButton = Button(activity).apply {
                tag = TAG_CAMERA_BUTTON
                text = "📷 CAMERA GATE BETA\nSTART 트리거 · FPS · 서버 시간 동기화"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(78)
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    activity.startActivity(Intent(activity, CameraGateTestActivity::class.java))
                }
            }
            parent.addView(cameraButton, insertIndex.coerceAtLeast(0))
        }
    }
}
