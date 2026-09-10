package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private const val REGISTER_TAG = "race_home_register_source"
    private const val JOIN_TAG = "race_home_join_source"
    private const val ACTION_BOX_TAG = "race_home_action_box_v03469"
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

        val start = findButton(decor) { b ->
            val t = b.text?.toString()?.trim().orEmpty()
            t == "START" || t == "LIVE"
        } ?: return

        // Keep START as the strongest visual action.
        start.apply {
            if (text?.toString() != "START") text = "START"
            textSize = 51f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        // Remove the duplicated rider/event summary above START.
        val oldSummary = findTextView(decor) { tv ->
            val t = tv.text?.toString().orEmpty()
            t.startsWith("✓ 참가완료 ·") || t.startsWith("자동 랩 준비 ·")
        }
        oldSummary?.let { tv ->
            val parent = tv.parent as? ViewGroup
            if (parent != null) {
                val index = parent.indexOfChild(tv)
                parent.removeView(tv)
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

        // Locate the original RaceActivity buttons. They keep the real click behavior, but are
        // hidden from the home screen once the dedicated action panel is created.
        val registration = decor.findViewWithTag<Button>(REGISTER_TAG) ?: findButton(decor) { b ->
            b.text?.toString()?.replace(" ", "")?.contains("선수등록") == true ||
                b.text?.toString()?.replace(" ", "")?.contains("등록/변경") == true
        }
        val join = decor.findViewWithTag<Button>(JOIN_TAG) ?: findButton(decor) { b ->
            b.text?.toString()?.replace(" ", "")?.contains("대회참가") == true ||
                b.text?.toString()?.replace(" ", "")?.contains("방참여/변경") == true
        }
        if (registration == null || join == null) return
        registration.tag = REGISTER_TAG
        join.tag = JOIN_TAG

        val legacyRow = registration.parent as? LinearLayout
        if (legacyRow != null && join.parent === legacyRow) {
            legacyRow.visibility = View.GONE
        } else {
            registration.visibility = View.GONE
            join.visibility = View.GONE
        }

        // Create a dedicated menu panel immediately below START. This avoids inherited row-height
        // constraints and prevents Korean labels from being clipped on smaller phones/font scales.
        val body = start.parent as? LinearLayout ?: return
        if (decor.findViewWithTag<View>(ACTION_BOX_TAG) == null) {
            val panel = LinearLayout(activity).apply {
                tag = ACTION_BOX_TAG
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(activity, 12f), dp(activity, 12f), dp(activity, 12f), dp(activity, 12f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(activity, 14f).toFloat()
                    setColor(Color.rgb(22, 22, 22))
                    setStroke(dp(activity, 1f), Color.rgb(68, 68, 68))
                }
            }

            panel.addView(actionButton(activity, "등록/변경") {
                registration.performClick()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 62f)))

            panel.addView(actionButton(activity, "방참여/변경") {
                join.performClick()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 62f)).apply {
                topMargin = dp(activity, 8f)
            })

            val startIndex = body.indexOfChild(start)
            val insertIndex = if (startIndex >= 0) startIndex + 1 else body.childCount
            body.addView(panel, insertIndex, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 16f)
                bottomMargin = dp(activity, 4f)
            })
        }

        // Course creation already exists under Map creation. Keep only that single entry point.
        decor.findViewWithTag<View>(TRACKS_TAG)?.let { stale ->
            (stale.parent as? ViewGroup)?.removeView(stale) ?: run { stale.visibility = View.GONE }
        }
    }

    private fun actionButton(activity: Activity, label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setIncludeFontPadding(false)
        minHeight = 0
        minWidth = 0
        setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, 10f).toFloat()
            setColor(Color.rgb(38, 38, 38))
            setStroke(dp(activity, 1f), Color.rgb(88, 88, 88))
        }
        setOnClickListener { onClick() }
    }

    private fun findButton(v: View, predicate: (Button) -> Boolean): Button? {
        if (v is Button && predicate(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findButton(v.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun findTextView(v: View, predicate: (TextView) -> Boolean): TextView? {
        if (v is TextView && predicate(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextView(v.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun dp(activity: Activity, v: Float) = (v * activity.resources.displayMetrics.density).toInt()
}
