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

        // The old summary above START duplicated both event and rider information.
        // Capture the currently displayed event first, then remove that duplicate summary entirely.
        val oldSummary = findTextView(decor) { tv ->
            val t = tv.text?.toString().orEmpty()
            t.startsWith("✓ 참가완료 ·") || t.startsWith("자동 랩 준비 ·")
        }
        var currentEventName = ""
        var currentEventCode = ""
        oldSummary?.text?.toString()?.lineSequence()?.firstOrNull()?.let { first ->
            if (first.startsWith("✓ 참가완료 ·")) {
                val parts = first.removePrefix("✓ 참가완료 ·").split("·").map { it.trim() }
                currentEventName = parts.getOrNull(0).orEmpty()
                currentEventCode = parts.getOrNull(1).orEmpty()
            }
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

        val profile = RaceProfileStore.profile(activity)
        val fallbackJoined = runCatching { RaceDataStore(activity).lastJoined() }.getOrNull()
        if (currentEventName.isBlank()) currentEventName = fallbackJoined?.config?.name.orEmpty()
        if (currentEventCode.isBlank()) currentEventCode = fallbackJoined?.config?.eventCode.orEmpty()

        // Put the saved rider identity directly inside the 선수등록 card; no repeated field labels.
        val registration = findButton(decor) { b ->
            b.text?.toString()?.replace(" ", "")?.contains("선수등록") == true
        }
        registration?.apply {
            text = if (profile.isReady) {
                "✓ 선수등록\n${profile.bib} · ${profile.name} · ${profile.nickname}"
            } else {
                "선수등록\n등록 필요"
            }
            textSize = 15f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(activity, 2f).toFloat(), 1.04f)
            setPadding(dp(activity, 7f), dp(activity, 7f), dp(activity, 7f), dp(activity, 7f))
            layoutParams?.let { lp ->
                if (lp.height != dp(activity, 84f)) {
                    lp.height = dp(activity, 84f)
                    layoutParams = lp
                }
            }
        }

        // After joining, the event card itself becomes the single source for room name + event code.
        val join = findButton(decor) { b ->
            b.text?.toString()?.replace(" ", "")?.contains("대회참가") == true
        }
        join?.apply {
            text = if (currentEventName.isNotBlank() || currentEventCode.isNotBlank()) {
                "✓ 대회참가완료\n${listOf(currentEventName, currentEventCode).filter { it.isNotBlank() }.joinToString(" · ")}"
            } else {
                "대회참가\n참가할 대회를 선택하세요"
            }
            textSize = 15f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(activity, 2f).toFloat(), 1.04f)
            setPadding(dp(activity, 7f), dp(activity, 7f), dp(activity, 7f), dp(activity, 7f))
            layoutParams?.let { lp ->
                if (lp.height != dp(activity, 84f)) {
                    lp.height = dp(activity, 84f)
                    layoutParams = lp
                }
            }
        }

        // TRACKS button on the RACE home.
        if (decor.findViewWithTag<View>(TAG) != null) return
        val homeJoin = join ?: return
        val row = homeJoin.parent as? LinearLayout ?: return
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
