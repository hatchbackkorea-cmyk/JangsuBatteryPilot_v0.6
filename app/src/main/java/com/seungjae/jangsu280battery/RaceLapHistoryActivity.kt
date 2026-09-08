package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent event-session lap history with Chrono-style summary and detailed CP table. */
class RaceLapHistoryActivity : Activity() {
    companion object {
        const val EXTRA_COURSE_ID = "timegate_lap_history_course_id"
        const val EXTRA_EVENT_CODE = "timegate_lap_history_event_code"
    }

    private lateinit var store: RaceDataStore
    private lateinit var repo: CourseRepository
    private lateinit var sessionStore: RaceLapSessionStore
    private var courseId = ""
    private var eventCode = "PRACTICE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RaceDataStore(this)
        repo = CourseRepository(this)
        sessionStore = RaceLapSessionStore(this)
        val active = store.activeConfig()
        courseId = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty().ifBlank {
            store.snapshot().courseId.ifBlank { active?.second.orEmpty() }
        }
        eventCode = intent.getStringExtra(EXTRA_EVENT_CODE).orEmpty().ifBlank {
            store.snapshot().eventCode.ifBlank { active?.first?.eventCode.orEmpty() }
        }.ifBlank { "PRACTICE" }.uppercase()
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), 0)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(BLUE)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(58)))
        top.addView(TextView(this).apply {
            text = "랩 기록"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val session = sessionStore.matching(eventCode, courseId)
        val courseName = repo.listCourses().firstOrNull { it.id == courseId }?.name
            ?: store.completed().lastOrNull { it.courseId == courseId }?.courseName
            ?: "현재 코스"
        body.addView(TextView(this).apply {
            text = courseName
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
        })
        body.addView(TextView(this).apply {
            text = if (eventCode == "PRACTICE") "연습 세션" else "경기 $eventCode"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BLUE)
            setPadding(0, dp(3), 0, dp(3))
        })
        body.addView(TextView(this).apply {
            text = "같은 경기방을 나갔다 다시 들어와도 당일 세션의 랩은 계속 이어집니다. OPTIMAL LAP은 각 CP 구간의 최고 기록을 조합한 이론상 최상 랩입니다."
            textSize = 12f
            setTextColor(SECONDARY)
            setPadding(0, dp(3), 0, dp(12))
        })

        if (session == null) {
            body.addView(emptyMessage("현재 경기의 랩 세션이 없습니다."))
            return
        }

        val laps = sessionRuns(store.completed(), eventCode, courseId, session.startedAtMs)
        if (laps.isEmpty()) {
            body.addView(emptyMessage("이 세션에서 완료된 랩 기록이 없습니다."))
            return
        }

        val usable = laps.filter { it.status != "INVALID" }
        val schemaRun = usable.maxWithOrNull(compareBy<RaceRunSummary> { it.sectors.size }.thenBy { it.finishedAtMs })
            ?: laps.maxByOrNull { it.finishedAtMs }
        val segmentCount = schemaRun?.sectors?.size ?: 0
        val segmentLabels = (0 until segmentCount).map { i -> segmentLabel(schemaRun?.sectors.orEmpty(), i) }
        val minBySegment = LongArray(segmentCount) { Long.MAX_VALUE }
        val maxBySegment = LongArray(segmentCount) { Long.MIN_VALUE }
        for (i in 0 until segmentCount) {
            val values = usable.mapNotNull { run -> run.sectors.getOrNull(i)?.sectorMs?.takeIf { it > 0L } }
            if (values.isNotEmpty()) {
                minBySegment[i] = values.minOrNull() ?: Long.MAX_VALUE
                maxBySegment[i] = values.maxOrNull() ?: Long.MIN_VALUE
            }
        }

        val actualBest = usable.minByOrNull { it.elapsedMs }
        val actualWorst = usable.maxByOrNull { it.elapsedMs }
        val bestLapNo = actualBest?.let { target -> laps.indexOfFirst { it.runId == target.runId } + 1 }?.takeIf { it > 0 }
        val theoreticalParts = (0 until segmentCount).mapNotNull { i -> minBySegment[i].takeIf { it != Long.MAX_VALUE } }
        val theoretical = if (segmentCount > 0 && theoreticalParts.size == segmentCount) theoreticalParts.sum() else null

        val summaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        summaryRow.addView(summaryCard("LAPS", laps.size.toString(), null, TEXT), LinearLayout.LayoutParams(0, dp(94), 1f))
        summaryRow.addView(summaryCard("BEST LAP", actualBest?.elapsedMs?.let(::formatTime) ?: "—", bestLapNo?.let { "LAP $it" }, BLUE), LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginStart = dp(6) })
        summaryRow.addView(summaryCard("OPTIMAL LAP", theoretical?.let(::formatTime) ?: "—", "구간 최속 조합", BLUE), LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginStart = dp(6) })
        body.addView(summaryRow)

        body.addView(sectionTitle("랩별 전체 기록"))
        body.addView(TextView(this).apply {
            text = "DELTA는 OPTIMAL LAP 대비 전체 코스 차이입니다."
            textSize = 11f
            setTextColor(SECONDARY)
            setPadding(0, 0, 0, dp(6))
        })
        body.addView(buildLapOverview(laps, actualBest, theoretical))

        body.addView(sectionTitle("CP 구간 상세"))
        body.addView(TextView(this).apply {
            text = "각 CP 구간별 최속은 파랑, 최저속은 빨강으로 표시합니다. INVALID 랩은 표에는 남지만 BEST/OPTIMAL 계산에서는 제외합니다."
            textSize = 11f
            setTextColor(SECONDARY)
            setPadding(0, 0, 0, dp(7))
        })

        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true }
        val table = TableLayout(this).apply {
            isShrinkAllColumns = false
            isStretchAllColumns = false
            setBackgroundColor(Color.rgb(238, 242, 247))
        }
        horizontal.addView(table)
        body.addView(horizontal, LinearLayout.LayoutParams(-1, -2))

        val header = TableRow(this)
        header.addView(cell("LAP", true, TEXT, dp(62)))
        segmentLabels.forEach { header.addView(cell(it, true, TEXT, dp(118))) }
        header.addView(cell("FINISH", true, TEXT, dp(108)))
        header.addView(cell("상태", true, TEXT, dp(84)))
        table.addView(header)

        laps.forEachIndexed { lapIndex, run ->
            val invalid = run.status == "INVALID"
            val row = TableRow(this).apply { setBackgroundColor(Color.WHITE) }
            val isBest = actualBest?.runId == run.runId
            row.addView(cell("${lapIndex + 1}${if (isBest) " ★" else ""}", true, if (isBest) BLUE else TEXT, dp(62)))
            for (i in 0 until segmentCount) {
                val value = run.sectors.getOrNull(i)?.sectorMs?.takeIf { it > 0L }
                val color = when {
                    invalid -> Color.GRAY
                    value == null -> Color.GRAY
                    minBySegment[i] == Long.MAX_VALUE || maxBySegment[i] == Long.MIN_VALUE -> TEXT
                    minBySegment[i] == maxBySegment[i] -> TEXT
                    value == minBySegment[i] -> BLUE
                    value == maxBySegment[i] -> RED
                    else -> TEXT
                }
                row.addView(cell(value?.let(::formatTime) ?: "—", false, color, dp(118)))
            }
            val finishColor = when {
                invalid -> Color.GRAY
                actualBest?.runId == run.runId -> BLUE
                actualWorst != null && actualWorst.runId == run.runId && actualWorst.runId != actualBest?.runId -> RED
                else -> TEXT
            }
            row.addView(cell(formatTime(run.elapsedMs), true, finishColor, dp(108)))
            row.addView(cell(run.status, false, if (invalid) RED else SECONDARY, dp(84)))
            table.addView(row)
        }

        if (theoretical != null) {
            val row = TableRow(this).apply { setBackgroundColor(Color.rgb(235, 243, 255)) }
            row.addView(cell("OPT", true, BLUE, dp(62)))
            for (i in 0 until segmentCount) row.addView(cell(formatTime(minBySegment[i]), true, BLUE, dp(118)))
            row.addView(cell(formatTime(theoretical), true, BLUE, dp(108)))
            row.addView(cell("이론상", true, BLUE, dp(84)))
            table.addView(row)
        }
    }

    private fun buildLapOverview(laps: List<RaceRunSummary>, actualBest: RaceRunSummary?, theoretical: Long?): View {
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true }
        val table = TableLayout(this).apply { setBackgroundColor(Color.rgb(238, 242, 247)) }
        horizontal.addView(table)

        val header = TableRow(this)
        header.addView(cell("LAP", true, TEXT, dp(64)))
        header.addView(cell("FULL", true, TEXT, dp(112)))
        header.addView(cell("DELTA", true, TEXT, dp(96)))
        header.addView(cell("START", true, TEXT, dp(104)))
        table.addView(header)

        if (theoretical != null) {
            val opt = TableRow(this)
            opt.addView(cell("OPT", true, BLUE, dp(64)))
            opt.addView(cell(formatTime(theoretical), true, BLUE, dp(112)))
            opt.addView(cell("0.000", true, BLUE, dp(96)))
            opt.addView(cell("—", false, BLUE, dp(104)))
            table.addView(opt)
        }

        laps.forEachIndexed { index, run ->
            val invalid = run.status == "INVALID"
            val isBest = actualBest?.runId == run.runId
            val color = when {
                invalid -> Color.GRAY
                isBest -> BLUE
                else -> TEXT
            }
            val deltaText = theoretical?.let { base ->
                val d = run.elapsedMs - base
                if (d >= 0L) "+${formatTime(d)}" else "−${formatTime(-d)}"
            } ?: "—"
            val row = TableRow(this)
            row.addView(cell("${index + 1}${if (isBest) " ★" else ""}", true, color, dp(64)))
            row.addView(cell(formatTime(run.elapsedMs), true, color, dp(112)))
            row.addView(cell(deltaText, false, if (invalid) Color.GRAY else if (isBest) BLUE else SECONDARY, dp(96)))
            row.addView(cell(formatClock(run.startedAtMs), false, if (invalid) Color.GRAY else SECONDARY, dp(104)))
            table.addView(row)
        }
        return horizontal
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT)
        setPadding(0, dp(16), 0, dp(5))
    }

    private fun emptyMessage(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Color.GRAY)
        setPadding(0, dp(48), 0, dp(48))
    }

    private fun summaryCard(label: String, value: String, sub: String?, valueColor: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(7), dp(5), dp(7))
        setBackgroundColor(Color.rgb(247, 249, 252))
        addView(TextView(this@RaceLapHistoryActivity).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(SECONDARY)
        })
        addView(TextView(this@RaceLapHistoryActivity).apply {
            text = value
            textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(valueColor)
        })
        if (!sub.isNullOrBlank()) addView(TextView(this@RaceLapHistoryActivity).apply {
            text = sub
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(SECONDARY)
        })
    }

    private fun cell(textValue: String, bold: Boolean, color: Int, width: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(10), dp(6), dp(10))
        setBackgroundColor(Color.WHITE)
        layoutParams = TableRow.LayoutParams(width, dp(48)).apply { marginEnd = 1; bottomMargin = 1 }
    }

    private fun segmentLabel(sectors: List<RaceSectorResult>, index: Int): String {
        val current = sectors.getOrNull(index)?.name?.ifBlank { "CP${index + 1}" } ?: "CP${index + 1}"
        return if (index == 0) "START→$current" else {
            val previous = sectors.getOrNull(index - 1)?.name?.ifBlank { "CP$index" } ?: "CP$index"
            "$previous→$current"
        }
    }

    private fun sessionRuns(all: List<RaceRunSummary>, targetEventCode: String, targetCourseId: String, sessionStartMs: Long): List<RaceRunSummary> =
        all.asSequence()
            .filter { it.eventCode.equals(targetEventCode, ignoreCase = true) }
            .filter { it.courseId == targetCourseId }
            .filter { it.finishedAtMs >= sessionStartMs }
            .sortedBy { it.finishedAtMs }
            .toList()

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val milli = safe % 1000
        return if (minutes > 0) "%d:%02d.%03d".format(minutes, seconds, milli) else "%d.%03d".format(seconds, milli)
    }

    private fun formatClock(ms: Long): String = if (ms <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val TEXT = Color.rgb(8, 10, 13)
    private val SECONDARY = Color.rgb(94, 105, 120)
    private val BLUE = Color.rgb(12, 91, 235)
    private val RED = Color.rgb(217, 22, 53)
}
