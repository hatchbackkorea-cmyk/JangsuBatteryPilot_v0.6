package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var course: CourseData? = null
    private var currentKm: Double = 0.0
    private var compactMode: Boolean = false
    private var checkpointKmsOverride: List<Double>? = null

    // v0.27.4: ride HUD can zoom to the active charging segment while other pages keep full-course mode.
    private var windowStartKm: Double? = null
    private var windowEndKm: Double? = null
    // v0.27.5: blue = recommended reserve reach, red = hard-reserve absolute reach.
    private var recommendedReachKm: Double? = null
    private var hardReachKm: Double? = null

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
    private val currentDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
    private val recommendedLimitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        color = context.getColor(R.color.accent)
    }
    private val recommendedBeyondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.accent)
        alpha = 16
    }
    private val dangerLimitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
        color = context.getColor(R.color.danger)
    }
    private val dangerBeyondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.danger)
        alpha = 28
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

    fun setCompactMode(value: Boolean) {
        compactMode = value
        invalidate()
    }

    fun setCheckpointKms(value: List<Double>?) {
        checkpointKmsOverride = value?.distinct()?.sorted()
        invalidate()
    }

    /** null/null means full-course view. */
    fun setWindow(startKm: Double?, endKm: Double?) {
        windowStartKm = startKm
        windowEndKm = endKm
        invalidate()
    }

    /**
     * Blue = normal/recommended reserve reach. Red = hard-reserve absolute reach.
     * Either may be null independently; the compact HUD normally keeps blue always on.
     */
    fun setReachLimits(recommendedKm: Double?, hardKm: Double?) {
        recommendedReachKm = recommendedKm
        hardReachKm = hardKm
        invalidate()
    }

    /** Backward-compatible helper used by older callers/tests. */
    fun setReachLimitKm(value: Double?) {
        hardReachKm = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = course ?: return
        if (c.track.size < 2 || width <= 0 || height <= 0 || c.totalKm <= 0.01) return

        val left = if (compactMode) dp(2f) else dp(8f)
        val right = width - if (compactMode) dp(2f) else dp(8f)
        val top = if (compactMode) dp(3f) else dp(10f)
        val bottom = height - if (compactMode) dp(3f) else dp(20f)
        val plotW = max(1f, right - left)
        val plotH = max(1f, bottom - top)

        var visibleStart = (windowStartKm ?: 0.0).coerceIn(0.0, c.totalKm)
        var visibleEnd = (windowEndKm ?: c.totalKm).coerceIn(0.0, c.totalKm)
        if (visibleEnd <= visibleStart + 0.10) {
            visibleStart = 0.0
            visibleEnd = c.totalKm
        }
        val visibleSpan = max(0.10, visibleEnd - visibleStart)
        fun x(km: Double): Float = left + ((km - visibleStart) / visibleSpan * plotW).toFloat()

        if (!c.hasElevation) {
            if (!compactMode) canvas.drawText("GPX 고도 데이터 없음 · 거리 기반 모드", left, height / 2f, textPaint)
            if (currentKm in visibleStart..visibleEnd) {
                val cx = x(currentKm)
                canvas.drawLine(cx, top, cx, bottom, currentPaint)
            }
            drawReachLimits(canvas, visibleStart, visibleEnd, left, right, top, bottom) { km -> x(km) }
            return
        }

        val visiblePoints = ArrayList<TrackPoint>()
        visiblePoints += c.pointAtKm(visibleStart)
        c.track.asSequence()
            .filter { it.routeKm > visibleStart + 0.001 && it.routeKm < visibleEnd - 0.001 }
            .forEach { visiblePoints += it }
        visiblePoints += c.pointAtKm(visibleEnd)

        var minEle = Double.MAX_VALUE
        var maxEle = -Double.MAX_VALUE
        visiblePoints.forEach {
            minEle = min(minEle, it.ele)
            maxEle = max(maxEle, it.ele)
        }
        val eleSpan = max(1.0, maxEle - minEle)
        fun y(ele: Double): Float = bottom - ((ele - minEle) / eleSpan * plotH).toFloat()

        // Keep a small "next 10 km" shade, clipped to the currently visible segment window.
        if (currentKm in visibleStart..visibleEnd) {
            val previewEnd = min(visibleEnd, currentKm + 10.0)
            if (previewEnd > currentKm + 0.01) {
                canvas.drawRect(x(currentKm), top, x(previewEnd), bottom, futurePaint)
            }
        }

        val path = Path()
        val step = max(1, visiblePoints.size / 700)
        var first = true
        var i = 0
        while (i < visiblePoints.size) {
            val p = visiblePoints[i]
            val px = x(p.routeKm)
            val py = y(p.ele)
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
            i += step
        }
        val last = visiblePoints.last()
        path.lineTo(x(last.routeKm), y(last.ele))

        val fill = Path(path)
        fill.lineTo(x(visibleEnd), bottom)
        fill.lineTo(left, bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        val markerKms = buildList {
            addAll(checkpointKmsOverride ?: c.supplyPois.map { it.routeKm })
            add(c.totalKm)
        }.distinct().sorted()
        markerKms.forEach { km ->
            if (km >= visibleStart - 0.001 && km <= visibleEnd + 0.001) {
                val px = x(km)
                canvas.drawLine(px, top, px, bottom, checkpointPaint)
                if (!compactMode) {
                    val label = if (abs(km - c.totalKm) < 0.2) "FIN" else km.toInt().toString()
                    canvas.drawText(label, px + dp(2f), height - dp(5f), textPaint)
                }
            }
        }

        drawReachLimits(canvas, visibleStart, visibleEnd, left, right, top, bottom) { km -> x(km) }

        if (currentKm in visibleStart..visibleEnd) {
            val cx = x(currentKm)
            canvas.drawLine(cx, top, cx, bottom, currentPaint)
            canvas.drawCircle(cx, y(c.pointAtKm(currentKm).ele), dp(4f), currentDotPaint)
        }
    }

    private fun drawReachLimits(
        canvas: Canvas,
        visibleStart: Double,
        visibleEnd: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        x: (Double) -> Float
    ) {
        val recommended = recommendedReachKm
        if (recommended != null && recommended >= visibleStart - 0.001 && recommended <= visibleEnd + 0.001) {
            val px = x(recommended).coerceIn(left, right)
            // A tiny cool-blue band makes the everyday reserve line readable without looking like danger.
            if (px < right - dp(1f)) canvas.drawRect(px, top, right, bottom, recommendedBeyondPaint)
            canvas.drawLine(px, top, px, bottom, recommendedLimitPaint)
        }

        val hard = hardReachKm
        if (hard != null && hard >= visibleStart - 0.001 && hard <= visibleEnd + 0.001) {
            val px = x(hard).coerceIn(left, right)
            // Red is reserved for the real last-resort line. Beyond it is intentionally shaded.
            if (px < right - dp(1f)) canvas.drawRect(px, top, right, bottom, dangerBeyondPaint)
            canvas.drawLine(px, top, px, bottom, dangerLimitPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
