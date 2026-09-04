package com.seungjae.jangsu280battery

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/**
 * Keeps the first-screen HUD stationary when the ride warning banner appears.
 *
 * MainActivity still owns warning state/text. This controller only changes presentation:
 * the banner keeps its normal measured height, while every sibling below it is translated
 * upward by exactly that height. Visually the warning therefore overlays the HUD instead of
 * pushing the whole first screen down. No prediction, warning, logging or ride state is changed.
 */
object RideWarningOverlayController {
    private val installed = WeakHashMap<Activity, State>()

    fun install(activity: Activity) {
        if (installed.containsKey(activity)) return
        val banner = activity.findViewById<View?>(R.id.layoutRideWarningBanner) ?: return
        val parent = banner.parent as? ViewGroup ?: return
        State(parent, banner).also {
            installed[activity] = it
            it.attach()
        }
    }

    fun destroy(activity: Activity) {
        installed.remove(activity)?.detach()
    }

    private class State(
        private val parent: ViewGroup,
        private val banner: View
    ) : ViewTreeObserver.OnPreDrawListener {
        private var appliedOffset = Float.NaN

        fun attach() {
            if (parent.viewTreeObserver.isAlive) parent.viewTreeObserver.addOnPreDrawListener(this)
            banner.elevation = maxOf(banner.elevation, 24f * banner.resources.displayMetrics.density)
            parent.invalidate()
        }

        override fun onPreDraw(): Boolean {
            val offset = if (banner.visibility == View.VISIBLE && banner.height > 0) {
                -banner.height.toFloat()
            } else {
                0f
            }
            if (offset == appliedOffset) return true

            val bannerIndex = parent.indexOfChild(banner)
            if (bannerIndex >= 0) {
                for (i in bannerIndex + 1 until parent.childCount) {
                    parent.getChildAt(i).translationY = offset
                }
            }
            banner.translationY = 0f
            appliedOffset = offset
            return true
        }

        fun detach() {
            if (parent.viewTreeObserver.isAlive) {
                runCatching { parent.viewTreeObserver.removeOnPreDrawListener(this) }
            }
            val bannerIndex = parent.indexOfChild(banner)
            if (bannerIndex >= 0) {
                for (i in bannerIndex + 1 until parent.childCount) {
                    parent.getChildAt(i).translationY = 0f
                }
            }
            banner.translationY = 0f
        }
    }
}
