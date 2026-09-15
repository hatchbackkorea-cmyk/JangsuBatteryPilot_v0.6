package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import java.util.WeakHashMap

/**
 * RACE home field controls.
 *
 * - Admin phones keep the timing-device QR manager.
 * - Any phone that successfully scanned an official timing QR gets a direct measurement menu for
 *   the lifetime of its stored 12-hour assignment, even after the app is restarted.
 * - Once that assignment expires, the measurement menu is removed automatically.
 */
object CameraGateRaceUiInstaller {
    private const val TAG_CAMERA_BUTTON = "camera_gate_beta_race_home_v6"
    private const val TAG_OPERATOR_BUTTON = "timegate_timing_operator_race_home_v1"
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val expiryJobs = WeakHashMap<Activity, Runnable>()
    private val main = Handler(Looper.getMainLooper())

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
        expiryJobs.remove(activity)?.let(main::removeCallbacks)
        val listener = listeners.remove(activity) ?: return
        val decor = activity.window.decorView
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
    }

    private fun attachIfHome(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val start = findStartButton(decor)
        if (start == null) {
            removeTagged(decor, TAG_OPERATOR_BUTTON)
            removeTagged(decor, TAG_CAMERA_BUTTON)
            return
        }
        val body = start.parent as? LinearLayout ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val assignment = runCatching { TimingOperatorStore.current(activity) }.getOrNull()
        val operatorExisting = decor.findViewWithTag<View>(TAG_OPERATOR_BUTTON)
        if (assignment == null) {
            operatorExisting?.let { (it.parent as? ViewGroup)?.removeView(it) }
            expiryJobs.remove(activity)?.let(main::removeCallbacks)
        } else {
            val button = (operatorExisting as? Button) ?: Button(activity).apply {
                tag = TAG_OPERATOR_BUTTON
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                isAllCaps = false
                setBackgroundColor(Color.rgb(12, 91, 235))
                setOnClickListener {
                    val active = TimingOperatorStore.current(activity) ?: run {
                        attachIfHome(activity)
                        return@setOnClickListener
                    }
                    activity.startActivity(Intent(activity, CameraGateHighSpeedActivity::class.java).apply {
                        putExtra("timegate_operator_role", active.role)
                        putExtra("timegate_operator_event", active.eventCode)
                    })
                }
            }
            button.text = "📷 ${assignment.role} 계측 시작\n${assignment.eventCode} · QR 운영권한 활성"
            if (button.parent == null) {
                val insertIndex = fieldControlInsertIndex(body, start)
                body.addView(button, insertIndex, LinearLayout.LayoutParams(-1, dp(72)).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(4)
                })
            }
            scheduleExpiryRefresh(activity, assignment.expiresAtMs)
        }

        val adminExisting = decor.findViewWithTag<View>(TAG_CAMERA_BUTTON)
        val admin = runCatching { RiderServerSync(activity).isAdminDeviceCached() }.getOrDefault(false)
        if (!admin) {
            adminExisting?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return
        }
        if (adminExisting != null) return

        val button = Button(activity).apply {
            tag = TAG_CAMERA_BUTTON
            text = "🔒 공식 계측기 관리\nSTART · CP · FINISH QR 배정"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setBackgroundColor(Color.rgb(52, 78, 104))
            setOnClickListener {
                if (!RiderServerSync(activity).isAdminDeviceCached()) return@setOnClickListener
                activity.startActivity(Intent(activity, TimingDeviceManagerActivity::class.java))
            }
        }
        val insertIndex = fieldControlInsertIndex(body, start) + if (body.findViewWithTag<View>(TAG_OPERATOR_BUTTON) != null) 1 else 0
        body.addView(button, insertIndex.coerceAtMost(body.childCount), LinearLayout.LayoutParams(-1, dp(72)).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        })
    }

    private fun scheduleExpiryRefresh(activity: Activity, expiresAtMs: Long) {
        expiryJobs.remove(activity)?.let(main::removeCallbacks)
        val delay = (expiresAtMs - System.currentTimeMillis() + 250L).coerceAtLeast(250L)
        val job = Runnable {
            expiryJobs.remove(activity)
            if (!activity.isFinishing && !activity.isDestroyed) attachIfHome(activity)
        }
        expiryJobs[activity] = job
        main.postDelayed(job, delay)
    }

    private fun fieldControlInsertIndex(body: LinearLayout, start: Button): Int {
        val startIndex = body.indexOfChild(start)
        if (startIndex < 0) return body.childCount
        val menuRow = (startIndex + 3 until body.childCount)
            .mapNotNull { i -> body.getChildAt(i) as? LinearLayout }
            .firstOrNull { row -> row.orientation == LinearLayout.HORIZONTAL && row.childCount >= 2 }
        return if (menuRow != null) body.indexOfChild(menuRow) + 1 else (startIndex + 3).coerceAtMost(body.childCount)
    }

    private fun removeTagged(root: ViewGroup, tag: String) {
        root.findViewWithTag<View>(tag)?.let { (it.parent as? ViewGroup)?.removeView(it) }
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
