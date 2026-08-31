package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RoadRaceSimulationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var course: CourseData? = null
    private var states: List<SimulationRiderState> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val palette = intArrayOf(
        Color.rgb(70, 180, 255), Color.rgb(255, 120, 90), Color.rgb(110, 220, 140), Color.rgb(250, 205, 70),
        Color.rgb(190, 120, 255), Color.rgb(80, 220, 215), Color.rgb(255, 110, 185), Color.rgb(180, 210, 100),
        Color.rgb(255, 165, 65), Color.rgb(120, 155, 255), Color.rgb(230, 110, 110), Color.rgb(125, 225, 175),
        Color.rgb(210, 175, 255), Color.rgb(250, 230, 100), Color.rgb(100, 205, 245), Color.rgb(245, 145, 210),
        Color.rgb(180, 235, 130), Color.rgb(255, 190, 125), Color.rgb(135, 180, 245), Color.rgb(235, 150, 120)
    )

    fun setData(course: CourseData?, states: List<SimulationRiderState>) {
        this.course = course
        this.states = states
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 22, 28))
        val c = course ?: run {
            drawCentered(canvas, "GPX 코스를 불러오면 시뮬레이션 지도가 표시됩니다.", height / 2f, 15f, Color.LTGRAY)
            return
        }
        if (c.track.size < 2) return
        val pad = 26f
        val mapTop = 24f
        val mapBottom = height * 0.64f
        val elevTop = mapBottom + 28f
        val elevBottom = height - 30f

        drawMap(canvas, c, pad, mapTop, width - pad, mapBottom)
        drawElevation(canvas, c, pad, elevTop, width - pad, elevBottom)
    }

    private fun drawMap(canvas: Canvas, c: CourseData, left: Float, top: Float, right: Float, bottom: Float) {
        val minLat = c.track.minOf { it.lat }
        val maxLat = c.track.maxOf { it.lat }
        val minLon = c.track.minOf { it.lon }
        val maxLon = c.track.maxOf { it.lon }
        val latSpan = max(1e-7, maxLat - minLat)
        val lonSpan = max(1e-7, maxLon - minLon)
        fun xy(lat: Double, lon: Double): Pair<Float, Float> {
            val x = left + ((lon - minLon) / lonSpan * (right - left)).toFloat()
            val y = bottom - ((lat - minLat) / latSpan * (bottom - top)).toFloat()
            return x to y
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.color = Color.rgb(65, 72, 84)
        path.reset()
        c.track.forEachIndexed { i, p ->
            val (x, y) = xy(p.lat, p.lon)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        paint.strokeWidth = 2.5f
        paint.color = Color.rgb(165, 175, 190)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = 22f
        c.supplyPois.forEach { poi ->
            val (x, y) = xy(poi.lat, poi.lon)
            paint.color = Color.rgb(255, 205, 80)
            canvas.drawCircle(x, y, 7f, paint)
            paint.color = Color.WHITE
            paint.textSize = 18f
            canvas.drawText(poi.name.take(10), x + 8f, y - 8f, paint)
        }

        states.forEachIndexed { i, s ->
            val p = c.pointAtKm(s.routeKm)
            val (x, y) = xy(p.lat, p.lon)
            val color = palette[i % palette.size]
            paint.color = color
            canvas.drawCircle(x, y, if (s.status.contains("휴식")) 10f else 8f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.BLACK
            canvas.drawCircle(x, y, if (s.status.contains("휴식")) 11f else 9f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 18f
            val label = "${s.nickname} ${String.format(java.util.Locale.US, "%.1f", s.routeKm)}km"
            canvas.drawText(label, x + 10f, y + 5f, paint)
        }
        paint.color = Color.LTGRAY
        paint.textSize = 20f
        canvas.drawText("전체 코스 · 참가자 ${states.size}명", left, top - 3f, paint)
    }

    private fun drawElevation(canvas: Canvas, c: CourseData, left: Float, top: Float, right: Float, bottom: Float) {
        if (bottom <= top + 10f) return
        val minEle = c.track.minOf { it.ele }
        val maxEle = c.track.maxOf { it.ele }
        val span = max(1.0, maxEle - minEle)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(27, 33, 42)
        canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.rgb(120, 135, 150)
        path.reset()
        c.track.forEachIndexed { i, p ->
            val x = left + ((p.routeKm / max(0.001, c.totalKm)) * (right - left)).toFloat()
            val y = bottom - 10f - (((p.ele - minEle) / span) * (bottom - top - 28f)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        states.forEachIndexed { i, s ->
            val p = c.pointAtKm(s.routeKm)
            val x = left + ((s.routeKm / max(0.001, c.totalKm)) * (right - left)).toFloat()
            val y = bottom - 10f - (((p.ele - minEle) / span) * (bottom - top - 28f)).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = palette[i % palette.size]
            canvas.drawCircle(x, y, 7f, paint)
        }
        paint.color = Color.LTGRAY
        paint.textSize = 18f
        canvas.drawText("고도 프로파일 · 현재 위치", left + 8f, top + 20f, paint)
    }

    private fun drawCentered(canvas: Canvas, text: String, y: Float, size: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.color = color
        val x = (width - paint.measureText(text)) / 2f
        canvas.drawText(text, max(8f, x), y, paint)
    }
}

class RoadSimulationSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var course: CourseData? = null
    private var riders: List<SimulationRiderPlan> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val palette = intArrayOf(
        Color.rgb(70, 180, 255), Color.rgb(255, 120, 90), Color.rgb(110, 220, 140), Color.rgb(250, 205, 70),
        Color.rgb(190, 120, 255), Color.rgb(80, 220, 215), Color.rgb(255, 110, 185), Color.rgb(180, 210, 100),
        Color.rgb(255, 165, 65), Color.rgb(120, 155, 255), Color.rgb(230, 110, 110), Color.rgb(125, 225, 175),
        Color.rgb(210, 175, 255), Color.rgb(250, 230, 100), Color.rgb(100, 205, 245), Color.rgb(245, 145, 210),
        Color.rgb(180, 235, 130), Color.rgb(255, 190, 125), Color.rgb(135, 180, 245), Color.rgb(235, 150, 120)
    )

    fun setData(course: CourseData?, riders: List<SimulationRiderPlan>) {
        this.course = course
        this.riders = riders
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 22, 28))
        val c = course ?: return
        if (riders.isEmpty()) return
        drawDistanceChart(canvas, c, 32f, 36f, width - 24f, height * 0.48f)
        drawRankChart(canvas, 32f, height * 0.56f, width - 24f, height - 32f)
    }

    private fun drawDistanceChart(canvas: Canvas, c: CourseData, left: Float, top: Float, right: Float, bottom: Float) {
        val maxSec = riders.maxOf { it.finishRaceSec }.coerceAtLeast(1.0)
        frame(canvas, left, top, right, bottom, "거리-경과시간 예상")
        riders.forEachIndexed { idx, rider ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = palette[idx % palette.size]
            path.reset()
            var first = true
            val steps = 160
            for (i in 0..steps) {
                val t = maxSec * i / steps
                val state = RoadRaceSimulationEngine.stateAt(c, rider, t)
                val x = left + 46f + (t / maxSec * (right - left - 62f)).toFloat()
                val y = bottom - 30f - (state.routeKm / c.totalKm.coerceAtLeast(0.1) * (bottom - top - 68f)).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 17f
            canvas.drawText(rider.nickname, right - 110f, top + 28f + idx * 19f, paint)
        }
    }

    private fun drawRankChart(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val standings = RoadRaceSimulationEngine.checkpointStandings(riders)
        frame(canvas, left, top, right, bottom, "포인트별 예상 순위 변화")
        if (standings.size < 2) return
        val names = riders.map { it.nickname }
        val maxRank = max(1, riders.size)
        names.forEachIndexed { idx, name ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = palette[idx % palette.size]
            path.reset()
            var started = false
            standings.forEachIndexed { cpIdx, s ->
                val rank = s.riders.indexOfFirst { it.first == name }.let { if (it < 0) maxRank else it + 1 }
                val x = left + 46f + (cpIdx.toDouble() / max(1, standings.lastIndex) * (right - left - 62f)).toFloat()
                val y = top + 48f + ((rank - 1).toDouble() / max(1, maxRank - 1) * (bottom - top - 74f)).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.LTGRAY
        paint.textSize = 15f
        standings.forEachIndexed { i, s ->
            if (i % max(1, standings.size / 6) != 0 && i != standings.lastIndex) return@forEachIndexed
            val x = left + 46f + (i.toDouble() / max(1, standings.lastIndex) * (right - left - 62f)).toFloat()
            canvas.save()
            canvas.rotate(-35f, x, bottom - 8f)
            canvas.drawText(s.checkpointName.take(10), x, bottom - 8f, paint)
            canvas.restore()
        }
    }

    private fun frame(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, title: String) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(27, 33, 42)
        canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, paint)
        paint.color = Color.WHITE
        paint.textSize = 21f
        canvas.drawText(title, left + 12f, top + 27f, paint)
    }
}
