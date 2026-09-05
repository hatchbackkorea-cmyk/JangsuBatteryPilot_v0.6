package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/** Adds a RaceChrono-like TRACKS entry to the RACE home without coupling it to timing logic. */
object RaceTrackBuilderUiInstaller {
    private const val TAG = "race_tracks_builder_button_v0346"

    fun install(activity: RaceActivity) {
        val decor = activity.window.decorView
        if (decor.findViewWithTag<View>(TAG) != null) return
        val join = findButton(decor) { it.text?.toString()?.contains("대회 참가") == true } ?: return
        val row = join.parent as? LinearLayout ?: return
        val body = row.parent as? LinearLayout ?: return
        val index = body.indexOfChild(row)
        val b = Button(activity).apply {
            tag = TAG
            text = "🗺 코스 만들기 · TRACKS"
            textSize = 16f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 8f).toFloat()
                setColor(Color.rgb(28, 40, 55))
                setStroke(dp(activity, 1f), Color.rgb(65, 92, 122))
            }
            setOnClickListener { activity.startActivity(Intent(activity, RaceTrackBuilderActivity::class.java)) }
        }
        body.addView(b, index + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 58f)).apply { topMargin = dp(activity, 8f) })
    }

    private fun findButton(v: View, predicate: (Button) -> Boolean): Button? {
        if (v is Button && predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findButton(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun dp(activity: Activity, v: Float) = (v * activity.resources.displayMetrics.density).toInt()
}
