package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/** Durable status for the event GPX download/apply pipeline. */
object RaceGpxDownloadStatus {
    private const val PREF = "race_gpx_download_status_v1"
    private const val KEY = "status"

    data class Info(
        val state: String = "IDLE",
        val eventCode: String = "",
        val eventName: String = "",
        val courseName: String = "",
        val serverFileName: String = "",
        val localCourseId: String = "",
        val sha256: String = "",
        val sizeBytes: Long = 0L,
        val downloadedAtMs: Long = 0L,
        val message: String = ""
    )

    fun read(context: Context): Info {
        val raw = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return runCatching {
            val o = JSONObject(raw)
            Info(
                state = o.optString("state", "IDLE"),
                eventCode = o.optString("event_code"),
                eventName = o.optString("event_name"),
                courseName = o.optString("course_name"),
                serverFileName = o.optString("server_file_name"),
                localCourseId = o.optString("local_course_id"),
                sha256 = o.optString("sha256"),
                sizeBytes = o.optLong("size_bytes", 0L),
                downloadedAtMs = o.optLong("downloaded_at_ms", 0L),
                message = o.optString("message")
            )
        }.getOrDefault(Info())
    }

    private fun write(context: Context, info: Info) {
        val o = JSONObject().apply {
            put("state", info.state)
            put("event_code", info.eventCode)
            put("event_name", info.eventName)
            put("course_name", info.courseName)
            put("server_file_name", info.serverFileName)
            put("local_course_id", info.localCourseId)
            put("sha256", info.sha256)
            put("size_bytes", info.sizeBytes)
            put("downloaded_at_ms", info.downloadedAtMs)
            put("message", info.message)
        }
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    private fun base(context: Context, eventCode: String): Info {
        val old = read(context)
        return if (old.eventCode.equals(eventCode, ignoreCase = true)) old else Info(eventCode = eventCode.trim().uppercase())
    }

    fun markChecking(context: Context, eventCode: String) {
        val old = base(context, eventCode)
        write(context, old.copy(state = "CHECKING", message = "서버 경기 GPX 확인 중"))
    }

    fun markDownloading(context: Context, eventCode: String, serverFileName: String) {
        val old = base(context, eventCode)
        write(context, old.copy(state = "DOWNLOADING", serverFileName = serverFileName, message = "GPX 다운로드 중"))
    }

    fun markDownloaded(context: Context, eventCode: String, serverFileName: String, sha256: String, sizeBytes: Long) {
        val old = base(context, eventCode)
        write(
            context,
            old.copy(
                state = "DOWNLOADED",
                serverFileName = serverFileName,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                downloadedAtMs = System.currentTimeMillis(),
                message = "GPX 다운로드 완료"
            )
        )
    }

    fun markApplied(context: Context, eventCode: String, eventName: String, courseName: String, localCourseId: String) {
        val old = base(context, eventCode)
        val display = resolveDisplayName(courseName, old.serverFileName)
        write(
            context,
            old.copy(
                state = "COMPLETED",
                eventName = eventName,
                courseName = display,
                localCourseId = localCourseId,
                downloadedAtMs = old.downloadedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                message = "GPX 다운로드/적용 완료"
            )
        )
    }

    fun markFailed(context: Context, eventCode: String, message: String) {
        val old = base(context, eventCode)
        write(context, old.copy(state = "FAILED", message = message.take(180)))
    }

    fun resolveDisplayName(serverCourseName: String, serverFileName: String): String {
        val course = serverCourseName.trim()
        val stem = serverFileName.substringBeforeLast('.').trim()
        val generic = setOf("새 gpx 코스", "새 gps 코스", "gpx 코스", "gps 코스", "경기 gpx", "경기 gps", "새 코스")
        return when {
            course.isBlank() && stem.isNotBlank() -> stem
            course.lowercase(Locale.ROOT) in generic && stem.isNotBlank() -> stem
            course.isNotBlank() -> course
            stem.isNotBlank() -> stem
            else -> "경기 GPX"
        }
    }

    fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}

/** Bright GPX status banner + field-debug menu for RaceActivity. */
object RaceDebugUiInstaller {
    private const val TAG_BANNER = "timegate_gpx_status_banner_v03449"
    private const val TAG_DEBUG = "timegate_debug_button_v03449"
    private val states = WeakHashMap<RaceActivity, UiState>()

    private data class UiState(val handler: Handler, val runnable: Runnable)

    fun install(activity: RaceActivity) {
        ensureViews(activity)
        if (states.containsKey(activity)) return
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                ensureViews(activity)
                refreshBanner(activity)
                handler.postDelayed(this, 400L)
            }
        }
        states[activity] = UiState(handler, runnable)
        handler.post(runnable)
    }

    fun uninstall(activity: RaceActivity) {
        states.remove(activity)?.let { it.handler.removeCallbacks(it.runnable) }
    }

    private fun ensureViews(activity: RaceActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG_BANNER) == null) {
            val banner = TextView(activity).apply {
                tag = TAG_BANNER
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 5))
                elevation = dp(activity, 7).toFloat()
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                marginStart = dp(activity, 8)
                marginEnd = dp(activity, 8)
                topMargin = dp(activity, 58)
            }
            content.addView(banner, lp)
        }
        if (content.findViewWithTag<View>(TAG_DEBUG) == null) {
            val button = Button(activity).apply {
                tag = TAG_DEBUG
                text = "DEBUG"
                textSize = 11f
                isAllCaps = false
                alpha = 0.94f
                setOnClickListener { showDebug(activity) }
                elevation = dp(activity, 8).toFloat()
            }
            val lp = FrameLayout.LayoutParams(dp(activity, 92), dp(activity, 44)).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                marginStart = dp(activity, 12)
                bottomMargin = dp(activity, 42)
            }
            content.addView(button, lp)
        }
    }

    private fun refreshBanner(activity: RaceActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val banner = content.findViewWithTag<TextView>(TAG_BANNER) ?: return
        val store = RaceDataStore(activity)
        val joined = store.lastJoined()
        val info = RaceGpxDownloadStatus.read(activity)

        val (text, color) = when {
            info.state == "CHECKING" -> "GPX 확인 중 · ${info.eventCode}" to Color.rgb(188, 126, 0)
            info.state == "DOWNLOADING" -> "GPX 다운로드 중 · ${info.serverFileName.ifBlank { info.eventCode }}" to Color.rgb(0, 104, 190)
            joined == null -> "대회 미참가 · START = 저장 GPX 로컬 자동랩" to Color.rgb(80, 80, 80)
            info.eventCode.equals(joined.config.eventCode, true) && info.state in setOf("DOWNLOADED", "COMPLETED") -> {
                val name = info.courseName.ifBlank { RaceGpxDownloadStatus.resolveDisplayName(joined.config.courseName, info.serverFileName) }
                val filePart = info.serverFileName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "✓ GPX 다운로드 완료 · $name$filePart" to Color.rgb(20, 126, 65)
            }
            info.eventCode.equals(joined.config.eventCode, true) && info.state == "FAILED" -> "⚠ GPX 다운로드 실패 · ${info.message}" to Color.rgb(174, 48, 38)
            else -> "대회 참가됨 · ${joined.config.name} · GPX 상태 확인 대기" to Color.rgb(94, 74, 150)
        }
        banner.text = text
        banner.background = GradientDrawable().apply {
            cornerRadius = dp(activity, 8).toFloat()
            setColor(color)
            setStroke(dp(activity, 1), Color.WHITE)
        }
    }

    private fun showDebug(activity: RaceActivity) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 14))
        }
        val report = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setText(buildReport(activity))
        }
        box.addView(report, LinearLayout.LayoutParams(-1, -2))

        fun action(label: String, onClick: () -> Unit) {
            box.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { topMargin = dp(activity, 7) })
        }

        action("GPX 강제 다운로드") {
            forceDownload(activity) { report.text = buildReport(activity) }
        }
        action("레이스 상태 초기화") {
            resetRaceState(activity)
            report.text = buildReport(activity)
        }
        action("디버그 로그 복사") {
            val text = buildReport(activity)
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("TimeGate DEBUG", text))
            Toast.makeText(activity, "디버그 로그를 복사했습니다.", Toast.LENGTH_SHORT).show()
            report.text = text
        }

        val scroll = ScrollView(activity).apply { addView(box) }
        AlertDialog.Builder(activity)
            .setTitle("TimeGate 디버그")
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun forceDownload(activity: RaceActivity, after: () -> Unit) {
        val store = RaceDataStore(activity)
        val joined = store.lastJoined()
        if (joined == null) {
            Toast.makeText(activity, "대회 참가 상태가 아닙니다.", Toast.LENGTH_LONG).show()
            return
        }
        if (store.snapshot().state == "RUNNING") {
            Toast.makeText(activity, "주행 중에는 GPX를 바꾸지 않습니다. FINISH 후 다시 시도하세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (store.snapshot().state == "ARMED") resetRaceState(activity, showToast = false)

        RaceGpxDownloadStatus.markChecking(activity, joined.config.eventCode)
        Toast.makeText(activity, "최신 경기 GPX를 다시 받습니다.", Toast.LENGTH_SHORT).show()
        Thread {
            val client = RaceServerClient(activity)
            val result = runCatching {
                val cfg = runCatching { client.eventState(joined.config.eventCode).config }.getOrDefault(joined.config)
                val tmp = client.downloadCourse(cfg.eventCode)
                try {
                    val repo = CourseRepository(activity)
                    val display = RaceGpxDownloadStatus.resolveDisplayName(cfg.courseName, tmp.name)
                    var localId = joined.localCourseId
                    val target = localId.takeIf { it.isNotBlank() }?.let { repo.sourceFile(it) }
                    if (target != null) {
                        tmp.copyTo(target, overwrite = true)
                        repo.setActive(localId)
                    } else {
                        localId = repo.importGpxFile(tmp, display, enqueueServer = false).id
                    }
                    store.saveJoined(cfg, joined.token, localId, joined.serverUrl.ifBlank { client.baseUrl() })
                    RaceGpxDownloadStatus.markApplied(activity, cfg.eventCode, cfg.name, display, localId)
                    RaceEventCourseRegistry.rememberCurrent(activity)
                    localId
                } finally {
                    tmp.delete()
                }
            }
            result.onFailure { RaceGpxDownloadStatus.markFailed(activity, joined.config.eventCode, it.message ?: "GPX 강제 다운로드 실패") }
            activity.runOnUiThread {
                result.onSuccess { Toast.makeText(activity, "GPX 강제 다운로드 완료", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(activity, "GPX 다운로드 실패 · ${it.message}", Toast.LENGTH_LONG).show() }
                refreshBanner(activity)
                after()
            }
        }.start()
    }

    private fun resetRaceState(activity: RaceActivity, showToast: Boolean = true) {
        runCatching { activity.stopService(Intent(activity, RaceTimingService::class.java)) }
        runCatching { activity.stopService(Intent(activity, RaceAutoLapDiscoveryService::class.java)) }
        val store = RaceDataStore(activity)
        val joined = store.lastJoined()
        store.clearActiveConfig()
        store.writeSnapshot(
            RaceDataStore.Snapshot(
                state = "STOPPED",
                eventCode = joined?.config?.eventCode.orEmpty(),
                eventName = joined?.config?.name.orEmpty(),
                serverStatus = "디버그 초기화 완료 · START를 다시 누르세요."
            )
        )
        if (showToast) Toast.makeText(activity, "레이스 상태를 초기화했습니다. 참가 상태는 유지됩니다.", Toast.LENGTH_LONG).show()
    }

    private fun buildReport(activity: RaceActivity): String {
        val store = RaceDataStore(activity)
        val joined = store.lastJoined()
        val snap = store.snapshot()
        val active = store.activeConfig()
        val info = RaceGpxDownloadStatus.read(activity)
        val registry = RaceEventCourseRegistry.latest(activity)
        val repo = CourseRepository(activity)
        val courseId = joined?.localCourseId.orEmpty().ifBlank { info.localCourseId.ifBlank { snap.courseId.ifBlank { active?.second.orEmpty() } } }
        val meta = repo.listCourses().firstOrNull { it.id == courseId }
        val file = courseId.takeIf { it.isNotBlank() }?.let { repo.sourceFile(it) }
        val raw = snap.runId.takeIf { it.isNotBlank() }?.let { File(activity.filesDir, "race/raw/$it.jsonl") }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        fun time(ms: Long) = if (ms > 0) df.format(Date(ms)) else "-"
        val fileHash = file?.takeIf { it.exists() }?.let { RaceGpxDownloadStatus.sha256(it) }.orEmpty()

        return buildString {
            append("[TimeGate DEBUG]\n")
            append("앱 v").append(UpdateManager.currentVersion(activity)).append('\n')
            append("서버 · ").append(RaceServerClient(activity).baseUrl()).append('\n')
            append("참가 · ").append(if (joined != null) "YES" else "NO").append('\n')
            if (joined != null) {
                append("이벤트 · ").append(joined.config.name).append(" · ").append(joined.config.eventCode).append('\n')
                append("서버 코스명 · ").append(joined.config.courseName).append('\n')
                append("participant token · ").append(if (joined.token.isNotBlank()) "있음" else "없음").append('\n')
            }
            append("계측 상태 · ").append(snap.state).append(" · runId=").append(snap.runId.ifBlank { "-" }).append('\n')
            append("코스 진행 · ").append(snap.routeM.toInt()).append('/').append(snap.totalM.toInt()).append("m\n")
            append("active config · ").append(active?.first?.eventCode ?: "-").append(" · courseId=").append(active?.second ?: "-").append('\n')
            append("\n[GPX 다운로드]\n")
            append("상태 · ").append(info.state).append(" · ").append(info.message).append('\n')
            append("서버 파일명 · ").append(info.serverFileName.ifBlank { "-" }).append('\n')
            append("표시 코스명 · ").append(info.courseName.ifBlank { "-" }).append('\n')
            append("다운로드 시각 · ").append(time(info.downloadedAtMs)).append('\n')
            append("다운로드 SHA256 · ").append(info.sha256.ifBlank { "-" }).append('\n')
            append("다운로드 크기 · ").append(info.sizeBytes).append(" bytes\n")
            append("\n[휴대폰 저장]\n")
            append("courseId · ").append(courseId.ifBlank { "-" }).append('\n')
            append("로컬 코스명 · ").append(meta?.name ?: "-").append('\n')
            append("로컬 파일명 · ").append(meta?.fileName ?: "-").append('\n')
            append("파일 존재 · ").append(file?.exists() == true).append(" · size=").append(file?.length() ?: 0L).append('\n')
            append("로컬 SHA256 · ").append(fileHash.ifBlank { "-" }).append('\n')
            if (registry != null) append("경기코스 등록 · ").append(registry.courseName).append(" · ").append(registry.serverFileName.ifBlank { "파일명 미기록" }).append('\n')
            append("GPS heartbeat · ").append(if (raw?.exists() == true) time(raw.lastModified()) else "없음").append('\n')
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
