package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
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
 * RaceChrono-like RACE home polish + TRACKS entry.
 *
 * The RACE activity rebuilds its content view when moving between registration/event/live/home.
 * A single global-layout hook therefore re-applies the home presentation whenever the home view
 * comes back, without touching timing state.
 */
object RaceTrackBuilderUiInstaller {
    private const val TAG = "race_tracks_builder_button_v0348"
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

        // Keep the existing event/status first line. Make the rider identity explicit and larger.
        findTextView(decor) { it.text?.toString()?.startsWith("✓ 참가완료 ·") == true }?.let { tv ->
            val first = tv.text.toString().lineSequence().firstOrNull().orEmpty()
            val p = RaceProfileStore.profile(activity)
            val second = "배번 ${p.bib}  ·  아이디 ${p.name}  ·  닉네임 ${p.nickname}"
            val desired = "$first\n$second"
            if (tv.text.toString() != desired) tv.text = desired
            tv.textSize = 22f
            tv.gravity = Gravity.CENTER
            tv.setLineSpacing(dp(activity, 3f).toFloat(), 1.08f)
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setPadding(dp(activity, 10f), dp(activity, 12f), dp(activity, 10f), dp(activity, 12f))
        }

        // TRACKS button on the RACE home.
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

    private fun findTextView(v: View, predicate: (TextView) -> Boolean): TextView? {
        if (v is TextView && predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTextView(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun dp(activity: Activity, v: Float) = (v * activity.resources.displayMetrics.density).toInt()
}
