package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Light-design bridge for older programmatic screens that still hard-code black containers.
 * It deliberately leaves the actual RACE live timing/broadcast canvas untouched.
 */
object TimeGateProgrammaticSkin {
    private val installed = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val white = Color.WHITE
    private val black = Color.rgb(8, 10, 13)
    private val secondary = Color.rgb(94, 105, 120)
    private val blue = Color.rgb(12, 91, 235)
    private val panel = Color.rgb(247, 249, 252)
    private val line = Color.rgb(215, 223, 234)

    fun install(activity: Activity) {
        if (activity !is RaceActivity && activity !is RaceTrackBuilderActivity) return
        if (installed.containsKey(activity)) {
            apply(activity)
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { apply(activity) }
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)
        installed[activity] = listener
        content.post { apply(activity) }
    }

    fun uninstall(activity: Activity) {
        val listener = installed.remove(activity) ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnGlobalLayoutListener(listener)
    }

    private fun apply(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // The live DELTA panel was repurposed to "코스 최고 기록" in v0.34.53, so using only the
        // literal word DELTA as the live-screen guard became unsafe. Detect the three stable timing
        // blocks instead. This keeps the blue/black timing canvas out of the light-theme converter
        // even after the DELTA label changes again.
        if (activity is RaceActivity && isRaceLiveTimingCanvas(content)) return
        styleTree(content)
    }

    private fun isRaceLiveTimingCanvas(view: View): Boolean =
        containsText(view, "BEST") && containsText(view, "PREVIOUS") && containsText(view, "CURRENT")

    private fun styleTree(view: View) {
        var lightContainer = false
        if (view is ViewGroup) {
            val bg = view.background
            if (bg is ColorDrawable && isDark(bg.color)) {
                // Legacy black/dark full-screen containers become the same white canvas as TimeGate.
                view.setBackgroundColor(white)
                lightContainer = true
            } else if (bg is ColorDrawable && !isDark(bg.color)) {
                lightContainer = true
            }
            if (view is ScrollView && view.background == null) {
                view.setBackgroundColor(white)
                lightContainer = true
            }
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (lightContainer) styleDirectChild(child)
                styleTree(child)
            }
        }
    }

    private fun styleDirectChild(view: View) {
        when (view) {
            is EditText -> {
                view.setTextColor(black)
                view.setHintTextColor(secondary)
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(view, 10).toFloat()
                    setColor(panel)
                    setStroke(dp(view, 1), line)
                }
            }
            is Button -> {
                if (view.background is ColorDrawable) view.setBackgroundColor(Color.TRANSPARENT)
                view.setTextColor(blue)
            }
            is TextView -> {
                if (isLight(view.currentTextColor)) {
                    view.setTextColor(if (view.textSize >= 17f) black else secondary)
                }
            }
        }
    }

    private fun containsText(view: View, needle: String): Boolean {
        if (view is TextView && view.text?.toString()?.contains(needle, ignoreCase = true) == true) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) if (containsText(view.getChildAt(i), needle)) return true
        return false
    }

    private fun isDark(color: Int): Boolean = luminance(color) < 0.32
    private fun isLight(color: Int): Boolean = luminance(color) > 0.68
    private fun luminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    private fun dp(view: View, v: Int) = (v * view.resources.displayMetrics.density).toInt()
}
