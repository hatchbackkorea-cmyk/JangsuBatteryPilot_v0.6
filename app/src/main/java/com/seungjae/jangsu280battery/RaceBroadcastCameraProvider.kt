package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Adds a native HD START/CP/FINISH camera monitor above the existing race-live WebView. */
class RaceBroadcastCameraProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val clients = WeakHashMap<Activity, RaceCameraStreamClient>()
    private val installJobs = WeakHashMap<Activity, Runnable>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is RaceBroadcastActivity) return
        installJobs.remove(activity)?.let { main.removeCallbacks(it) }
        val job = Runnable { if (!activity.isFinishing && !activity.isDestroyed) install(activity) }
        installJobs[activity] = job
        main.postDelayed(job, 80L)
    }

    private fun install(activity: RaceBroadcastActivity) {
        if (findTagged(activity.window.decorView, TAG_ROOT) != null) return
        val web = findWebView(activity.window.decorView) ?: run {
            main.postDelayed({ if (!activity.isFinishing && !activity.isDestroyed) install(activity) }, 180L)
            return
        }
        val root = web.parent as? LinearLayout ?: return
        val index = root.indexOfChild(web)
        if (index < 0) return

        val eventCode = activity.intent.getStringExtra(RaceBroadcastActivity.EXTRA_EVENT_CODE)
            .orEmpty().trim().uppercase().ifBlank {
                RaceDataStore(activity).lastJoined()?.config?.eventCode.orEmpty().trim().uppercase()
            }
        val baseUrl = activity.intent.getStringExtra(RaceBroadcastActivity.EXTRA_SERVER_URL)
            .orEmpty().trim().trimEnd('/').ifBlank {
                RaceServerClient(activity).baseUrl().trim().trimEnd('/')
            }
        if (eventCode.isBlank() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) return

        val container = LinearLayout(activity).apply {
            tag = TAG_ROOT
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 13))
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
        }

        val videoBox = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
        }
        val texture = TextureView(activity).apply {
            tag = TAG_TEXTURE
            isOpaque = true
        }
        videoBox.addView(texture, FrameLayout.LayoutParams(-1, -1))

        val roleBadge = TextView(activity).apply {
            tag = TAG_ROLE
            text = "START"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5))
            background = rounded(Color.argb(190, 0, 0, 0), dp(activity, 8).toFloat())
        }
        videoBox.addView(
            roleBadge,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(activity, 8)
                topMargin = dp(activity, 8)
            }
        )

        val status = TextView(activity).apply {
            tag = TAG_STATUS
            text = "START 카메라 연결 준비 중…"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
            background = rounded(Color.argb(170, 0, 0, 0), dp(activity, 7).toFloat())
        }
        videoBox.addView(
            status,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(activity, 7)
            }
        )
        container.addView(videoBox, LinearLayout.LayoutParams(-1, dp(activity, 214)))

        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val tabs = LinearLayout(activity).apply {
            tag = TAG_TABS
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, 0)
        }
        scroller.addView(tabs, HorizontalScrollView.LayoutParams(-2, dp(activity, 40)))
        container.addView(scroller, LinearLayout.LayoutParams(-1, dp(activity, 44)))

        root.addView(container, index, LinearLayout.LayoutParams(-1, dp(activity, 266)))

        val client = RaceCameraStreamClient(baseUrl, eventCode, texture) { message ->
            status.text = message
        }
        clients[activity] = client

        val tabViews = mutableMapOf<String, TextView>()
        fun select(role: String) {
            roleBadge.text = role
            tabViews.forEach { (name, view) -> styleTab(activity, view, name == role) }
            client.switchRole(role)
        }
        ROLES.forEach { role ->
            val tab = TextView(activity).apply {
                text = role
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { select(role) }
            }
            tabViews[role] = tab
            styleTab(activity, tab, role == "START")
            tabs.addView(tab, LinearLayout.LayoutParams(dp(activity, if (role.startsWith("CP")) 58 else 74), dp(activity, 34)).apply {
                marginEnd = dp(activity, 4)
            })
        }
        client.switchRole("START")
    }

    private fun styleTab(activity: Activity, view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        view.background = rounded(
            if (selected) Color.rgb(235, 242, 250) else Color.rgb(29, 38, 52),
            dp(activity, 9).toFloat()
        )
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findTagged(view: View, tagValue: String): View? {
        if (view.tag == tagValue) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findTagged(view.getChildAt(i), tagValue)?.let { return it }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        installJobs.remove(activity)?.let { main.removeCallbacks(it) }
        clients.remove(activity)?.close()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val ROLES = listOf("START", "CP1", "CP2", "CP3", "CP4", "CP5", "FINISH")
        private const val TAG_ROOT = "race_broadcast_camera_root_v1"
        private const val TAG_TEXTURE = "race_broadcast_camera_texture_v1"
        private const val TAG_ROLE = "race_broadcast_camera_role_v1"
        private const val TAG_STATUS = "race_broadcast_camera_status_v1"
        private const val TAG_TABS = "race_broadcast_camera_tabs_v1"
    }
}
