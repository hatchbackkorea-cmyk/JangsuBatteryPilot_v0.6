package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var course: CourseData? = null
    private var currentKm: Double = 0.0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = context.getColor(R.color.profile_line)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.profile_fill)
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.accent)
    }
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.warn)
        alpha = 180
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.preview_band)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = dp(10f)
    }

    fun setCourse(value: CourseData) {
        course = value
        invalidate()
    }

    fun setCurrentKm(value: Double) {
        currentKm = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = course ?: return
        if (c.track.size < 2 || width <= 0 || height <= 0) return

        val left = dp(8f)
        val right = width - dp(8f)
        val top = dp(10f)
        val bottom = height - dp(20f)
        val plotW = max(1f, right - left)
        val plotH = max(1f, bottom - top)

        if (!c.hasElevation) {
            canvas.drawText("GPX 고도 데이터 없음 · 거리 기반 모드", left, height / 2f, textPaint)
            val cx = left + (currentKm.coerceIn(0.0, c.totalKm) / c.totalKm * plotW).toFloat()
            canvas.drawLine(cx, top, cx, bottom, currentPaint)
            return
        }

        var minEle = Double.MAX_VALUE
        var maxEle = -Double.MAX_VALUE
        c.track.forEach {
            minEle = min(minEle, it.ele)
            maxEle = max(maxEle, it.ele)
        }
        val eleSpan = max(1.0, maxEle - minEle)
        fun x(km: Double): Float = left + (km / c.totalKm * plotW).toFloat()
        fun y(ele: Double): Float = bottom - ((ele - minEle) / eleSpan * plotH).toFloat()

        val previewEnd = min(c.totalKm, currentKm + 10.0)
        canvas.drawRect(x(currentKm), top, x(previewEnd), bottom, futurePaint)

        val path = Path()
        val step = max(1, c.track.size / 700)
        var first = true
        var i = 0
        while (i < c.track.size) {
            val p = c.track[i]
            val px = x(p.routeKm)
            val py = y(p.ele)
            if (first) {
                path.moveTo(px, py)
                first = false
            } else path.lineTo(px, py)
            i += step
        }
        val last = c.track.last()
        path.lineTo(x(last.routeKm), y(last.ele))

        val fill = Path(path)
        fill.lineTo(x(c.totalKm), bottom)
        fill.lineTo(left, bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        val markerKms = buildList {
            addAll(c.supplyPois.map { it.routeKm })
            add(c.totalKm)
        }.distinct().sorted()
        markerKms.forEach { km ->
            if (km in 0.0..(c.totalKm + 0.5)) {
                val px = x(km)
                canvas.drawLine(px, top, px, bottom, checkpointPaint)
                val label = if (kotlin.math.abs(km - c.totalKm) < 0.2) "FIN" else km.toInt().toString()
                canvas.drawText(label, px + dp(2f), height - dp(5f), textPaint)
            }
        }

        val cx = x(currentKm.coerceIn(0.0, c.totalKm))
        canvas.drawLine(cx, top, cx, bottom, currentPaint)
        canvas.drawCircle(cx, y(c.pointAtKm(currentKm).ele), dp(4f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.accent)
        })
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
