package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class CourseActivity : Activity() {
    companion object { private const val REQ_GPX = 2001 }

    private lateinit var repo: CourseRepository
    private lateinit var logManager: RideLogManager
    private lateinit var container: LinearLayout
    private lateinit var tvActive: TextView
    private lateinit var btnImport: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course)
        repo = CourseRepository(this)
        logManager = RideLogManager(this)
        container = findViewById(R.id.courseListContainer)
        tvActive = findViewById(R.id.tvCourseMenuActive)
        btnImport = findViewById(R.id.btnCourseImport)

        findViewById<Button>(R.id.btnCourseBack).setOnClickListener { finish() }
        btnImport.setOnClickListener { importGpx() }
        renderCourses()
    }

    override fun onResume() {
        super.onResume()
        renderCourses()
    }

    private fun renderCourses() {
        val active = repo.activeMeta()
        val activeElev = if (active.hasElevation) "▲${active.totalAscentM.roundToInt()}m · ▼${active.totalDescentM.roundToInt()}m" else "고도 데이터 없음"
        tvActive.text = "현재 선택 · ${active.name}\n${RideFormatter.one(active.totalKm)} km · $activeElev"

        val riding = logManager.isActive()
        btnImport.isEnabled = !riding
        findViewById<TextView>(R.id.tvCourseMenuHint).text = if (riding) {
            "현재 주행 기록 중입니다. 코스 변경/삭제/불러오기는 주행 종료 후 가능합니다."
        } else {
            "GPX를 불러오거나 저장된 코스를 선택하세요. 선택한 코스가 주행 화면의 기준이 됩니다."
        }

        container.removeAllViews()
        repo.listCourses().forEach { meta -> container.addView(buildCourseRow(meta, active.id, riding)) }
    }

    private fun buildCourseRow(meta: CourseMeta, activeId: String, riding: Boolean): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(if (meta.id == activeId) R.drawable.panel_accent_bg else R.drawable.panel_bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        outer.addView(TextView(this).apply {
            text = buildString {
                append(if (meta.id == activeId) "✓ " else "")
                append(meta.name)
                if (meta.builtIn) append(" · 기본")
            }
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        outer.addView(TextView(this).apply {
            val elev = if (meta.hasElevation) "▲${meta.totalAscentM.roundToInt()}m · ▼${meta.totalDescentM.roundToInt()}m" else "고도 데이터 없음"
            text = "${RideFormatter.one(meta.totalKm)} km · $elev"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(8))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(Button(this).apply {
            text = if (meta.id == activeId) "선택됨" else "이 코스 사용"
            isEnabled = !riding && meta.id != activeId
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnClickListener { selectCourse(meta) }
        })
        if (!meta.builtIn) {
            actions.addView(Button(this).apply {
                text = "삭제"
                isEnabled = !riding
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 0.55f).apply { marginStart = dp(8) }
                setOnClickListener { confirmDelete(meta) }
            })
        }
        outer.addView(actions)
        return outer
    }

    private fun selectCourse(meta: CourseMeta) {
        if (logManager.isActive()) return
        repo.setActive(meta.id)
        BatteryActualStore(this).clear()
        AppSettings.prefs(this).edit()
            .putFloat(AppSettings.KEY_LAST_KM, 0f)
            .putFloat(AppSettings.KEY_TEST_KM, 0f)
            .apply()
        renderCourses()
        Toast.makeText(this, "${meta.name} 코스를 선택했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(meta: CourseMeta) {
        if (meta.builtIn || logManager.isActive()) return
        AlertDialog.Builder(this)
            .setTitle("코스 삭제")
            .setMessage("${meta.name} GPX 코스를 앱에서 삭제할까요? 과거 주행 로그는 삭제하지 않습니다.")
            .setPositiveButton("삭제") { _, _ ->
                repo.deleteCourse(meta.id)
                BatteryActualStore(this).clear()
                AppSettings.prefs(this).edit()
                    .putFloat(AppSettings.KEY_LAST_KM, 0f)
                    .putFloat(AppSettings.KEY_TEST_KM, 0f)
                    .apply()
                renderCourses()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun importGpx() {
        if (logManager.isActive()) return Toast.makeText(this, "주행 종료 후 GPX를 불러오세요.", Toast.LENGTH_LONG).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        try { startActivityForResult(intent, REQ_GPX) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Android, retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GPX || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val meta = repo.importGpx(uri, displayName(uri))
            BatteryActualStore(this).clear()
            AppSettings.prefs(this).edit()
                .putFloat(AppSettings.KEY_LAST_KM, 0f)
                .putFloat(AppSettings.KEY_TEST_KM, 0f)
                .apply()
            renderCourses()
            Toast.makeText(this, "${meta.name} · ${RideFormatter.one(meta.totalKm)} km 불러오기 완료", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "GPX 불러오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } catch (_: Exception) { null } finally { cursor?.close() }
    }
}
