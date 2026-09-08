package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/** Persistent registry for GPX files downloaded through RACE event participation. */
object RaceEventCourseRegistry {
    private const val PREF = "race_event_course_registry_v1"
    private const val KEY = "items"

    data class Entry(
        val courseId: String,
        val eventCode: String,
        val eventName: String,
        val courseName: String,
        val savedAtMs: Long
    )

    fun rememberCurrent(context: Context): Entry? {
        val joined = RaceDataStore(context).lastJoined() ?: return null
        val id = joined.localCourseId.trim()
        if (id.isBlank() || CourseRepository(context).listCourses().none { it.id == id }) return null
        val entry = Entry(
            courseId = id,
            eventCode = joined.config.eventCode,
            eventName = joined.config.name,
            courseName = joined.config.courseName,
            savedAtMs = System.currentTimeMillis()
        )
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("course_id") != id) out.put(o)
        }
        out.put(entry.toJson())
        prefs.edit().putString(KEY, out.toString()).apply()
        return entry
    }

    fun latest(context: Context): Entry? = entries(context).maxByOrNull { it.savedAtMs }

    fun entries(context: Context): List<Entry> {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val a = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val repo = CourseRepository(context)
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let(::fromJson)
        }.filter { e -> repo.listCourses().any { it.id == e.courseId } }
    }

    private fun Entry.toJson() = JSONObject().apply {
        put("course_id", courseId)
        put("event_code", eventCode)
        put("event_name", eventName)
        put("course_name", courseName)
        put("saved_at_ms", savedAtMs)
    }

    private fun fromJson(o: JSONObject) = Entry(
        courseId = o.optString("course_id"),
        eventCode = o.optString("event_code"),
        eventName = o.optString("event_name"),
        courseName = o.optString("course_name"),
        savedAtMs = o.optLong("saved_at_ms", 0L)
    )
}

/**
 * Adds a persistent '대회 나가기' control without changing the server roster/history.
 * Rejoining is intentionally allowed: the server returns a fresh participant token and the app
 * downloads the event GPX again, which makes field map-sync testing deterministic.
 */
object RaceEventExitUiInstaller {
    private const val TAG = "timegate_event_exit_v03448"
    private val listeners = WeakHashMap<RaceActivity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(activity: RaceActivity) {
        RaceEventCourseRegistry.rememberCurrent(activity)
        if (listeners.containsKey(activity)) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { ensureButton(activity) }
        listeners[activity] = listener
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        ensureButton(activity)
    }

    fun uninstall(activity: RaceActivity) {
        listeners.remove(activity)?.let { listener ->
            val observer = activity.window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }

    private fun ensureButton(activity: RaceActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val joined = RaceDataStore(activity).lastJoined()
        val existing = content.findViewWithTag<View>(TAG)
        if (joined == null) {
            if (existing != null) (existing.parent as? ViewGroup)?.removeView(existing)
            return
        }
        RaceEventCourseRegistry.rememberCurrent(activity)
        if (existing != null) return

        val button = Button(activity).apply {
            tag = TAG
            text = "대회 나가기"
            isAllCaps = false
            textSize = 12f
            alpha = 0.92f
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("대회에서 나갈까요?")
                    .setMessage("이 휴대폰의 현재 참가 선택만 해제합니다. 서버의 기존 참가자/기록은 삭제하지 않습니다. 다시 참가하면 최신 경기 GPX를 다시 다운로드합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("대회 나가기") { _, _ -> leave(activity) }
                    .show()
            }
        }
        val lp = FrameLayout.LayoutParams(dp(activity, 116), dp(activity, 44)).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(activity, 12)
            bottomMargin = dp(activity, 42)
        }
        content.addView(button, lp)
    }

    private fun leave(activity: RaceActivity) {
        val store = RaceDataStore(activity)
        val joined = store.lastJoined() ?: return
        RaceEventCourseRegistry.rememberCurrent(activity)
        runCatching {
            activity.startService(Intent(activity, RaceTimingService::class.java).apply { action = RaceTimingService.ACTION_STOP })
        }
        runCatching {
            activity.startService(Intent(activity, RaceAutoLapDiscoveryService::class.java).apply { action = RaceAutoLapDiscoveryService.ACTION_STOP })
        }

        val code = joined.config.eventCode.trim().uppercase()
        val prefs = activity.getSharedPreferences("race_runtime_v1", Context.MODE_PRIVATE)
        val edit = prefs.edit().remove("joined_$code").remove("active_config")
        if (prefs.getString("last_event_code", "").orEmpty().uppercase() == code) edit.remove("last_event_code")
        edit.apply()
        store.writeSnapshot(
            RaceDataStore.Snapshot(
                state = "STOPPED",
                serverStatus = "대회 나가기 완료 · 다시 참가하면 최신 경기맵을 다운로드합니다."
            )
        )
        Toast.makeText(activity, "대회에서 나왔습니다. 다시 참가하면 경기맵을 새로 받습니다.", Toast.LENGTH_LONG).show()
        activity.window.decorView.post { activity.recreate() }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}

/** Shows downloaded event GPX explicitly in the two course-browsing screens. */
object RaceEventCourseQuickAccessInstaller {
    private const val TAG = "timegate_event_course_quick_v03448"

    fun install(activity: Activity) {
        val entry = RaceEventCourseRegistry.latest(activity) ?: return
        val repo = CourseRepository(activity)
        if (repo.listCourses().none { it.id == entry.courseId }) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return
        val linear = firstLinear(content) ?: return
        val courseLabel = entry.courseName.ifBlank { "경기 GPX" }
        val button = Button(activity).apply {
            tag = TAG
            text = "🏁 경기 다운로드 코스 · $courseLabel\n${entry.eventName} · ${entry.eventCode}"
            isAllCaps = false
            textSize = 12f
            setOnClickListener {
                runCatching { repo.setActive(entry.courseId) }
                    .onSuccess { Toast.makeText(activity, "경기 다운로드 코스를 선택했습니다.", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(activity, "경기코스를 열지 못했습니다: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        val index = if (linear.childCount > 1) 1 else linear.childCount
        linear.addView(button, index, LinearLayout.LayoutParams(-1, dp(activity, 58)).apply {
            marginStart = dp(activity, 8)
            marginEnd = dp(activity, 8)
            topMargin = dp(activity, 4)
            bottomMargin = dp(activity, 4)
        })
    }

    private fun firstLinear(root: ViewGroup): LinearLayout? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout) return child
            if (child is ViewGroup) firstLinear(child)?.let { return it }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
