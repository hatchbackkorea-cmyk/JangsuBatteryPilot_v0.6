package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Browser for RACE courses already saved on the phone.
 *
 * This is deliberately local-first: a course stays visible here even if the PC/server was offline
 * when the user tapped "서버 등록". Event-downloaded GPX files are also shown explicitly.
 */
class RaceSavedCoursesActivity : Activity() {
    private lateinit var repo: CourseRepository
    private lateinit var sync: RiderServerSync
    private lateinit var listBox: LinearLayout
    private lateinit var detailBox: LinearLayout
    private lateinit var map: RaceTrackBuilderMapView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = CourseRepository(this)
        sync = RiderServerSync(this)
        buildUi()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 21))
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), 0)
            setBackgroundColor(Color.rgb(43, 43, 43))
        }
        top.addView(Button(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(58)))
        top.addView(TextView(this).apply {
            text = "저장된 RACE 코스"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(top)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(14), dp(10), dp(14), dp(8))
            text = "휴대폰에 저장된 RACE 제작 코스와 경기에서 다운로드한 GPX를 모두 표시합니다."
        }
        root.addView(status)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(11, 16, 23)) }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 0.52f))

        detailBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.rgb(7, 12, 18))
        }
        map = RaceTrackBuilderMapView(this)
        detailBox.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(detailBox, LinearLayout.LayoutParams(-1, 0, 0.48f))
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val eventEntries = RaceEventCourseRegistry.entries(this).associateBy { it.courseId }
        val courses = repo.listCourses().filter { meta ->
            if (meta.builtIn) return@filter false
            val builder = repo.sourceFile(meta.id)?.let(::isRaceBuilderFile) == true
            builder || eventEntries.containsKey(meta.id)
        }
        if (courses.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "저장된 RACE 제작 코스나 경기 다운로드 GPX가 없습니다."
                textSize = 15f
                setTextColor(Color.LTGRAY)
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            return
        }
        val downloadedCount = courses.count { eventEntries.containsKey(it.id) }
        status.text = "저장 코스 ${courses.size}개 · 경기 다운로드 GPX ${downloadedCount}개 · 서버 파일명도 함께 표시합니다."
        courses.forEach { meta ->
            val eventEntry = eventEntries[meta.id]
            val gates = gatesFor(meta)
            val ready = gates.any { it.type == "START" } && gates.any { it.type == "FINISH" }
            val displayName = eventEntry?.courseName?.takeIf { it.isNotBlank() } ?: meta.name
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Color.rgb(24, 31, 42))
            }
            row.addView(TextView(this).apply {
                text = if (eventEntry != null) "🏁 $displayName" else displayName
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = if (eventEntry != null) {
                    val file = eventEntry.serverFileName.ifBlank { meta.fileName }
                    "경기 다운로드 GPX · 파일: $file\n${eventEntry.eventName} · ${eventEntry.eventCode} · ${"%.2f".format(Locale.US, meta.totalKm)} km"
                } else {
                    "${"%.2f".format(Locale.US, meta.totalKm)} km · ${if (ready) "START/FINISH 있음" else "START/FINISH 미완성"}"
                }
                textSize = 12f
                setTextColor(if (eventEntry != null || ready) Color.rgb(95, 220, 135) else Color.rgb(255, 184, 92))
                setPadding(0, dp(3), 0, dp(7))
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this).apply {
                text = "지도 보기"
                isAllCaps = false
                setOnClickListener { showCourse(meta) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            buttons.addView(Button(this).apply {
                text = if (eventEntry != null) "이 코스 사용" else "서버 다시 등록"
                isAllCaps = false
                isEnabled = eventEntry != null || sync.isAdminDeviceCached()
                setOnClickListener {
                    if (eventEntry != null) {
                        runCatching { repo.setActive(meta.id) }
                            .onSuccess { Toast.makeText(this@RaceSavedCoursesActivity, "경기 다운로드 코스를 선택했습니다.", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@RaceSavedCoursesActivity, "코스를 선택하지 못했습니다: ${it.message}", Toast.LENGTH_LONG).show() }
                    } else {
                        publish(meta)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
            row.addView(buttons)
            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun showCourse(meta: CourseMeta) {
        val course = runCatching { repo.loadCourse(meta.id) }.getOrElse {
            Toast.makeText(this, "코스를 열지 못했습니다 · ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        val points = course.track.mapIndexed { i, p ->
            RaceTrackDraftStore.Point(
                lat = p.lat,
                lon = p.lon,
                ele = p.ele,
                timeMs = i.toLong(),
                accuracyM = 0.0,
                bearingDeg = Double.NaN,
                routeM = p.routeKm * 1000.0
            )
        }
        val gates = gatesFor(meta)
        detailBox.removeAllViews()
        detailBox.visibility = View.VISIBLE
        map = RaceTrackBuilderMapView(this)
        detailBox.addView(TextView(this).apply {
            text = "${meta.name} · ${"%.2f".format(Locale.US, meta.totalKm)} km"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        detailBox.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        detailBox.addView(TextView(this).apply {
            val ready = gates.any { it.type == "START" } && gates.any { it.type == "FINISH" }
            text = if (ready) "START/FINISH 포함 · 서버 등록 가능" else "경기 다운로드 GPX 또는 START/FINISH 미완성 코스"
            textSize = 11f
            setTextColor(if (ready) Color.rgb(95, 220, 135) else Color.rgb(255, 184, 92))
            setPadding(0, dp(5), 0, 0)
        })
        map.render(points, gates, null, false)
    }

    private fun publish(meta: CourseMeta) {
        val file = repo.sourceFile(meta.id) ?: run {
            Toast.makeText(this, "저장된 GPX 파일을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val gates = gatesFor(meta)
        status.setTextColor(Color.LTGRAY)
        status.text = "${meta.name} 서버 등록 재시도 중…"
        RaceCoursePublisher(sync).publishDraftAsync(meta.id, meta.name, file, gates) { result ->
            runOnUiThread {
                status.setTextColor(if (result.ok) Color.rgb(95, 220, 135) else Color.rgb(255, 130, 80))
                status.text = if (result.ok) {
                    "✓ ${result.message}${result.serverCourseId?.let { " · 서버 코스 #$it" } ?: ""}"
                } else {
                    "등록 실패 · ${result.message}\n휴대폰 원본은 그대로 보관됩니다. 서버 연결 후 이 버튼을 다시 누르세요."
                }
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun gatesFor(meta: CourseMeta): List<RaceGate> = runCatching {
        val course = repo.loadCourse(meta.id)
        course.pois.mapNotNull { p ->
            val type = when (p.type.uppercase()) {
                "RACE_START" -> "START"
                "RACE_FINISH" -> "FINISH"
                "RACE_SECTOR" -> "SECTOR"
                else -> return@mapNotNull null
            }
            val fallback = RaceGateMath.gateAt(course, p.routeKm * 1000.0, p.name.ifBlank { type }, type, 5.0)
            val bearing = Regex("(?:^|;)bearing=([-+0-9.]+)").find(p.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback.bearingDeg
            val width = Regex("(?:^|;)width=([-+0-9.]+)").find(p.desc)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback.widthM
            RaceGate(p.name.ifBlank { type }, type, p.routeKm * 1000.0, p.lat, p.lon, bearing, width.coerceIn(1.0, 20.0))
        }.sortedBy { it.routeM }
    }.getOrDefault(emptyList())

    private fun isRaceBuilderFile(file: File): Boolean = runCatching {
        file.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = CharArray(4096)
            val n = r.read(buf)
            n > 0 && String(buf, 0, n).contains("creator=\"Ride Copilot RACE\"")
        }
    }.getOrDefault(false)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Adds a persistent entry point to the existing RACE course-builder screen without replacing it. */
object RaceSavedCourseBrowserLauncher {
    private const val TAG = "race_saved_courses_launcher_v03420"

    fun install(activity: RaceTrackBuilderActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        if (root.findViewWithTag<View>(TAG) != null) return
        val button = Button(activity).apply {
            tag = TAG
            text = "📂 저장된 RACE 코스"
            isAllCaps = false
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { activity.startActivity(Intent(activity, RaceSavedCoursesActivity::class.java)) }
        }
        val index = if (root.childCount >= 2) 1 else root.childCount
        root.addView(button, index, LinearLayout.LayoutParams(-1, dp(activity, 46)))
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
