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
 * This installer only restores a permanent RACE MODE entry on the first screen.
 */
object RaceLauncherUiInstaller {
    private const val TAG_BUTTON = "race_mode_launcher_button_v2"

    fun install(activity: Activity) {
        val anchor = activity.findViewById<View?>(R.id.btnBikeModeEmtb) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(TAG_BUTTON) != null) return

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val raceButton = Button(activity).apply {
            tag = TAG_BUTTON
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

        val index = parent.indexOfChild(anchor).coerceAtLeast(0)
        parent.addView(raceButton, index)
    }
}
