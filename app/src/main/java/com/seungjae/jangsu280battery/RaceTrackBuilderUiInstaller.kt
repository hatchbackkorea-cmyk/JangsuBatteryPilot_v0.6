package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * RaceChrono-like RACE home polish.
 *
 * The RACE activity rebuilds its content view when moving between registration/event/live/home.
 * A single global-layout hook therefore re-applies the home presentation whenever the home view
 * comes back, without touching timing state.
 */
object RaceTrackBuilderUiInstaller {
    private const val TRACKS_TAG = "race_tracks_builder_button_v0348"
    private const val REGISTER_TAG = "race_home_register_change"
    private const val JOIN_TAG = "race_home_room_join_change"
    private val hooks = WeakHashMap<RaceActivity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(activity: RaceActivity) {
        val decor = activity.window.decorView
        if (!hooks.containsKey(activity)) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener { applyHomeUi(activity) }
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            hooks[activity] = listener
        }
        applyHomeUi(activity)
    }

    private fun applyHomeUi(activity: RaceActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window.decorView

        // Red RaceChrono-style circle: always show START, including the armed/running return state.
        findButton(decor) { b ->
            val t = b.text?.toString()?.trim().orEmpty()
            t == "START" || t == "LIVE"
        }?.apply {
            if (text?.toString() != "START") text = "START"
            textSize = 51f // 34sp -> 1.5x
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        // The old summary above START duplicated event/rider information, so keep START as the
        // first strong action on the screen and remove the legacy summary + spacer.
        val oldSummary = findTextView(decor) { tv ->
            val t = tv.text?.toString().orEmpty()
            t.startsWith("✓ 참가완료 ·") || t.startsWith("자동 랩 준비 ·")
        }
        oldSummary?.let { tv ->
            val parent = tv.parent as? ViewGroup
            if (parent != null) {
                val index = parent.indexOfChild(tv)
                parent.removeView(tv)
                // showHome() places a 24dp spacer directly after the legacy summary.
                if (index in 0 until parent.childCount) {
                    val next = parent.getChildAt(index)
                    if (next !is TextView && next !is Button && next.layoutParams?.height == dp(activity, 24f)) {
                        parent.removeView(next)
                    }
                }
            } else {
                tv.visibility = View.GONE
            }
        }

        // Stable home actions: START first, then two full-width rows below it.
        val registration = decor.findViewWithTag<Button>(REGISTER_TAG) ?: findButton(decor) { b ->
            b.text?.toString()?.replace(" ", "")?.contains("선수등록") == true
        }
        val join = decor.findViewWithTag<Button>(JOIN_TAG) ?: findButton(decor) { b ->
            b.text?.toString()?.replace(" ", "")?.contains("대회참가") == true
        }

        registration?.apply {
            tag = REGISTER_TAG
            text = "등록/변경"
            textSize = 17f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 8f), dp(activity, 6f), dp(activity, 8f), dp(activity, 6f))
        }

        join?.apply {
            tag = JOIN_TAG
            text = "방참여/변경"
            textSize = 17f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 8f), dp(activity, 6f), dp(activity, 8f), dp(activity, 6f))
        }

        val row = (registration?.parent as? LinearLayout) ?: (join?.parent as? LinearLayout)
        if (row != null && registration != null && join != null && registration.parent === row && join.parent === row) {
            row.orientation = LinearLayout.VERTICAL
            row.gravity = Gravity.CENTER
            registration.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 60f))
            join.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 60f)).apply {
                topMargin = dp(activity, 8f)
            }
        }

        // TRACKS already exists in the Map creation flow. Never duplicate the same course-builder
        // screen below START on the RACE home.
        decor.findViewWithTag<View>(TRACKS_TAG)?.let { stale ->
            (stale.parent as? ViewGroup)?.removeView(stale) ?: run { stale.visibility = View.GONE }
        }
    }

    private fun findButton(v: View, predicate: (Button) -> Boolean): Button? {
        if (v is Button && predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findButton(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun findTextView(v: View, predicate: (TextView) -> Boolean): TextView? {
        if (v is TextView && predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTextView(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun dp(activity: Activity, v: Float) = (v * activity.resources.displayMetrics.density).toInt()
}
