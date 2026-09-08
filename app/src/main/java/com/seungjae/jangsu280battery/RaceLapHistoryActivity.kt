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
import java.util.Calendar
import kotlin.math.abs

/** Today's completed lap table for one course, including per-sector extrema and theoretical best. */
class RaceLapHistoryActivity : Activity() {
    companion object {
        const val EXTRA_COURSE_ID = "timegate_lap_history_course_id"
    }

    private lateinit var store: RaceDataStore
    private lateinit var repo: CourseRepository
    private var courseId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RaceDataStore(this)
        repo = CourseRepository(this)
        courseId = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty().ifBlank {
            store.snapshot().courseId.ifBlank { store.activeConfig()?.second.orEmpty() }
        }
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
            setTextColor(Color.rgb(12, 91, 235))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(58)))
        top.addView(TextView(this).apply {
            text = "랩 기록 · 오늘"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(8, 10, 13))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(22))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val courseName = repo.listCourses().firstOrNull { it.id == courseId }?.name
            ?: store.completed().lastOrNull { it.courseId == courseId }?.courseName
            ?: "현재 코스"
        body.addView(TextView(this).apply {
            text = courseName
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(8, 10, 13))
        })
        body.addView(TextView(this).apply {
            text = "오늘 이 코스의 완료된 모든 랩입니다. 파랑 = 해당 구간 최속, 빨강 = 해당 구간 최저속. INVALID 랩은 표에는 남기지만 비교 계산에서는 제외합니다."
            textSize = 12f
            setTextColor(Color.rgb(94, 105, 120))
            setPadding(0, dp(4), 0, dp(12))
        })

        val laps = todaysRuns(store.completed(), courseId)
        if (laps.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "오늘 완료된 랩 기록이 없습니다."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(0, dp(48), 0, dp(48))
            })
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
        val theoreticalParts = (0 until segmentCount).mapNotNull { i -> minBySegment[i].takeIf { it != Long.MAX_VALUE } }
        val theoretical = if (segmentCount > 0 && theoreticalParts.size == segmentCount) theoreticalParts.sum() else null

        val summaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        summaryRow.addView(summaryCard("실제 BEST", actualBest?.elapsedMs?.let(::formatTime) ?: "—", BLUE), LinearLayout.LayoutParams(0, dp(86), 1f))
        summaryRow.addView(summaryCard("THEORETICAL BEST", theoretical?.let(::formatTime) ?: "—", BLUE), LinearLayout.LayoutParams(0, dp(86), 1f).apply { marginStart = dp(6) })
        summaryRow.addView(summaryCard("남은 가능성", if (actualBest != null && theoretical != null) "-${formatTime((actualBest.elapsedMs - theoretical).coerceAtLeast(0L))}" else "—", Color.rgb(60, 70, 82)), LinearLayout.LayoutParams(0, dp(86), 1f).apply { marginStart = dp(6) })
        body.addView(summaryRow)
        body.addView(TextView(this).apply {
            text = "THEORETICAL BEST = 각 CP 구간에서 기록한 가장 빠른 구간시간들을 한 랩으로 조합한 이론상 최상 기록"
            textSize = 11f
            setTextColor(Color.rgb(94, 105, 120))
            setPadding(0, dp(6), 0, dp(12))
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
        header.addView(cell("LAP", true, Color.rgb(8, 10, 13), dp(62)))
        segmentLabels.forEach { header.addView(cell(it, true, Color.rgb(8, 10, 13), dp(118))) }
        header.addView(cell("랩타임", true, Color.rgb(8, 10, 13), dp(108)))
        header.addView(cell("상태", true, Color.rgb(8, 10, 13), dp(84)))
        table.addView(header)

        laps.forEachIndexed { lapIndex, run ->
            val invalid = run.status == "INVALID"
            val row = TableRow(this).apply { setBackgroundColor(Color.WHITE) }
            val isBest = actualBest?.runId == run.runId
            row.addView(cell("${lapIndex + 1}${if (isBest) " ★" else ""}", true, if (isBest) BLUE else Color.rgb(8, 10, 13), dp(62)))
            for (i in 0 until segmentCount) {
                val value = run.sectors.getOrNull(i)?.sectorMs?.takeIf { it > 0L }
                val color = when {
                    invalid -> Color.GRAY
                    value == null -> Color.GRAY
                    minBySegment[i] == Long.MAX_VALUE || maxBySegment[i] == Long.MIN_VALUE -> Color.rgb(8, 10, 13)
                    minBySegment[i] == maxBySegment[i] -> Color.rgb(8, 10, 13)
                    value == minBySegment[i] -> BLUE
                    value == maxBySegment[i] -> RED
                    else -> Color.rgb(8, 10, 13)
                }
                row.addView(cell(value?.let(::formatTime) ?: "—", false, color, dp(118)))
            }
            val finishColor = when {
                invalid -> Color.GRAY
                actualBest?.runId == run.runId -> BLUE
                actualWorst?.runId == run.runId && actualWorst.runId != actualBest?.runId -> RED
                else -> Color.rgb(8, 10, 13)
            }
            row.addView(cell(formatTime(run.elapsedMs), true, finishColor, dp(108)))
            row.addView(cell(run.status, false, if (invalid) RED else Color.rgb(94, 105, 120), dp(84)))
            table.addView(row)
        }

        if (theoretical != null) {
            val row = TableRow(this).apply { setBackgroundColor(Color.rgb(235, 243, 255)) }
            row.addView(cell("최상 조합", true, BLUE, dp(62)))
            for (i in 0 until segmentCount) row.addView(cell(formatTime(minBySegment[i]), true, BLUE, dp(118)))
            row.addView(cell(formatTime(theoretical), true, BLUE, dp(108)))
            row.addView(cell("이론상", true, BLUE, dp(84)))
            table.addView(row)
        }
    }

    private fun summaryCard(label: String, value: String, valueColor: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        setBackgroundColor(Color.rgb(247, 249, 252))
        addView(TextView(this@RaceLapHistoryActivity).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(94, 105, 120))
        })
        addView(TextView(this@RaceLapHistoryActivity).apply {
            text = value
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(valueColor)
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

    private fun todaysRuns(all: List<RaceRunSummary>, targetCourseId: String): List<RaceRunSummary> {
        val (start, end) = todayBounds()
        return all.asSequence()
            .filter { it.courseId == targetCourseId }
            .filter { it.finishedAtMs in start until end }
            .sortedBy { it.finishedAtMs }
            .toList()
    }

    private fun todayBounds(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val milli = safe % 1000
        return if (minutes > 0) "%d:%02d.%03d".format(minutes, seconds, milli) else "%d.%03d".format(seconds, milli)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val BLUE = Color.rgb(12, 91, 235)
    private val RED = Color.rgb(217, 22, 53)
}
