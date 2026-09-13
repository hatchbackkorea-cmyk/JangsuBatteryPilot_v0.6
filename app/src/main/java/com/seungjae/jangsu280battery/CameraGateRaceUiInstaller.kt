package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import java.util.WeakHashMap

/**
 * Injects a permanent CAMERA GATE BETA entry into the TimeGate RACE home screen.
 * RaceActivity builds its UI programmatically, so the entry is re-attached when HOME is rebuilt.
 */
object CameraGateRaceUiInstaller {
    private const val TAG_CAMERA_BUTTON = "camera_gate_beta_race_home_v2"
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(activity: Activity) {
        if (listeners.containsKey(activity)) {
            attachIfHome(activity)
            return
        }
        val decor = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { attachIfHome(activity) }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listeners[activity] = listener
        attachIfHome(activity)
    }

    fun uninstall(activity: Activity) {
        val listener = listeners.remove(activity) ?: return
        val decor = activity.window.decorView
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
    }

    private fun attachIfHome(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG_CAMERA_BUTTON) != null) return
        val start = findStartButton(decor) ?: return
        val body = start.parent as? LinearLayout ?: return
        val startIndex = body.indexOfChild(start)
        if (startIndex < 0) return
        val menuRow = (startIndex + 3 until body.childCount)
            .mapNotNull { i -> body.getChildAt(i) as? LinearLayout }
            .firstOrNull { row -> row.orientation == LinearLayout.HORIZONTAL && row.childCount >= 2 }
        val insertIndex = if (menuRow != null) body.indexOfChild(menuRow) + 1 else (startIndex + 3).coerceAtMost(body.childCount)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val button = Button(activity).apply {
            tag = TAG_CAMERA_BUTTON
            text = "📷 카메라 계측 테스트 (BETA v2)\nSTART 트리거 · 안정 FPS · 정밀 시간 동기화"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setBackgroundColor(Color.rgb(52, 78, 104))
            setOnClickListener {
                activity.startActivity(Intent(activity, CameraGateTestActivityV2::class.java))
            }
        }
        body.addView(button, insertIndex, LinearLayout.LayoutParams(-1, dp(68)).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        })
    }

    private fun findStartButton(root: ViewGroup): Button? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Button && child.text?.toString()?.trim() == "START") return child
            if (child is ViewGroup) findStartButton(child)?.let { return it }
        }
        return null
    }
}
