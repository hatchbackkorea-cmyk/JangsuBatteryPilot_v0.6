package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/** Keeps page names at one consistent top-left position and size across TimeGate subpages. */
object TimeGateHeaderNormalizer {
    private const val TAG_MAIN_HEADER = "timegate_main_emtb_header"
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val blue = Color.rgb(12, 91, 235)
    private val black = Color.rgb(8, 10, 13)

    fun install(activity: Activity) {
        if (activity is BikeModeChooserActivity) return
        if (activity is MainActivity) ensureMainHeader(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (listeners.containsKey(activity)) {
            normalize(activity)
            return
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { normalize(activity) }
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listeners[activity] = listener
        content.post { normalize(activity) }
    }

    fun uninstall(activity: Activity) {
        val listener = listeners.remove(activity) ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnGlobalLayoutListener(listener)
    }

    private fun ensureMainHeader(activity: MainActivity) {
        val root = activity.findViewById<LinearLayout?>(R.id.rootMain) ?: return
        if (root.findViewWithTag<View>(TAG_MAIN_HEADER) != null) return
        val header = LinearLayout(activity).apply {
            tag = TAG_MAIN_HEADER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(Button(activity).apply {
                text = "‹"
                textSize = 28f
                setTextColor(blue)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { activity.finish() }
            }, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 58)))
            addView(TextView(activity).apply {
                text = "AVINOX SYSTEM · eMTB"
                textSize = 24f
                setTextColor(black)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(activity, 58), 1f))
        }
        root.addView(header, 0, LinearLayout.LayoutParams(-1, dp(activity, 58)))
    }

    private fun normalize(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val header = findHeader(content) ?: return
        val lp = header.layoutParams
        lp.height = dp(activity, 58)
        header.layoutParams = lp
        header.gravity = Gravity.CENTER_VERTICAL

        var title: TextView? = null
        var back: Button? = null
        for (i in 0 until header.childCount) {
            when (val child = header.getChildAt(i)) {
                is Button -> if (back == null) back = child
                is TextView -> {
                    val txt = child.text?.toString().orEmpty().trim()
                    if (txt.isNotEmpty() && child.visibility == View.VISIBLE && child.textSize >= 15f) {
                        if (title == null || child.textSize > title!!.textSize) title = child
                    }
                }
            }
        }

        back?.let {
            val blp = it.layoutParams
            blp.width = dp(activity, 58)
            blp.height = dp(activity, 58)
            it.layoutParams = blp
            if (it.text.toString().length <= 4) it.text = "‹"
            it.textSize = 28f
            it.setTextColor(blue)
            it.setBackgroundColor(Color.TRANSPARENT)
        }
        title?.let {
            it.textSize = 24f
            it.setTextColor(black)
            it.setTypeface(it.typeface, Typeface.BOLD)
            it.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
    }

    private fun findHeader(view: View): LinearLayout? {
        if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL && view.visibility == View.VISIBLE) {
            var hasButton = false
            var hasTitle = false
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is Button && child.visibility == View.VISIBLE) hasButton = true
                if (child is TextView && child.visibility == View.VISIBLE && child.textSize >= 15f && !child.text.isNullOrBlank()) hasTitle = true
            }
            if (hasButton && hasTitle) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findHeader(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
