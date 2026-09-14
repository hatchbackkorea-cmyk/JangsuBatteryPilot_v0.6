package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/** Keeps the TimeGate RACE entry on the launcher. Operator Camera Gate is admin-only inside RACE. */
object RaceLauncherUiInstaller {
    private const val TAG_RACE_BUTTON = "race_mode_launcher_button_v2"
    private const val TAG_LEGACY_CAMERA_BUTTON = "camera_gate_beta_launcher_button_v5"

    fun install(activity: Activity) {
        val anchor = activity.findViewById<View?>(R.id.btnBikeModeEmtb) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val insertIndex = parent.indexOfChild(anchor).coerceAtLeast(0)

        // v0.34.114: remove the old public Camera Gate beta entry if it still exists in this view.
        parent.findViewWithTag<View>(TAG_LEGACY_CAMERA_BUTTON)?.let { legacy ->
            (legacy.parent as? ViewGroup)?.removeView(legacy)
        }

        if (parent.findViewWithTag<View>(TAG_RACE_BUTTON) == null) {
            val raceButton = Button(activity).apply {
                tag = TAG_RACE_BUTTON
                text = "🏁 RACE MODE\n타임어택 · 섹터 · LIVE"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)).apply { topMargin = dp(12) }
                setOnClickListener { activity.startActivity(Intent(activity, RaceActivity::class.java)) }
            }
            parent.addView(raceButton, insertIndex)
        }
    }
}
