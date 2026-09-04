package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

    private var windowStartKm: Double? = null
    private var windowEndKm: Double? = null
    private var recommendedReachKm: Double? = null
    private var hardReachKm: Double? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = context.getColor(R.color.profile_line)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.profile_fill)
    }
    private val gradeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 78
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (id == R.id.rideMiniProfileView) {
            post { installMtbLiveMapLayout() }
        }
    }

    private fun installMtbLiveMapLayout() {
        if (!isAttachedToWindow || id != R.id.rideMiniProfileView) return

        layoutParams?.let { lp ->
            val target = dp(92f).toInt()
            if (lp.height != target) {
                lp.height = target
                layoutParams = lp
            }
        }

        val mapFrame = rootView.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
        mapFrame.layoutParams?.let { lp ->
            // v0.33.7: reclaim 40dp so the ride/charge controls stay above the pager indicator.
            val target = dp(200f).toInt()
            if (lp.height != target) {
                lp.height = target
                mapFrame.layoutParams = lp
            }
        }

        if (mapFrame.findViewWithTag<View>(RideLiveMapWebView.TAG_LIVE_MAP) == null) {
            val liveMap = RideLiveMapWebView(context)
            val status = mapFrame.findViewById<View?>(R.id.tvRideMapPreviewStatus)
            val insertAt = status?.let { mapFrame.indexOfChild(it) }?.takeIf { it >= 0 } ?: mapFrame.childCount
            mapFrame.addView(
                liveMap,
                insertAt,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        MtbHudCompactController.install(rootView, context)
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

    fun setWindow(startKm: Double?, endKm: Double?) {
        windowStartKm = startKm
        windowEndKm = endKm
        invalidate()
    }

    fun setReachLimits(recommendedKm: Double?, hardKm: Double?) {
        recommendedReachKm = recommendedKm
        hardReachKm = hardKm
        invalidate()
    }

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

        if (currentKm in visibleStart..visibleEnd) {
            val previewEnd = min(visibleEnd, currentKm + 10.0)
            if (previewEnd > currentKm + 0.01) {
                canvas.drawRect(x(currentKm), top, x(previewEnd), bottom, futurePaint)
            }
        }

        val sampled = ArrayList<TrackPoint>()
        val step = max(1, visiblePoints.size / 700)
        var i = 0
        while (i < visiblePoints.size) {
            sampled += visiblePoints[i]
            i += step
        }
        if (sampled.lastOrNull() !== visiblePoints.lastOrNull()) sampled += visiblePoints.last()

        val fillPath = Path()
        sampled.forEachIndexed { idx, p ->
            val px = x(p.routeKm)
            val py = y(p.ele)
            if (idx == 0) fillPath.moveTo(px, py) else fillPath.lineTo(px, py)
        }
        fillPath.lineTo(x(visibleEnd), bottom)
        fillPath.lineTo(left, bottom)
        fillPath.close()

        if (sampled.size >= 2) {
            for (j in 1 until sampled.size) {
                val a = sampled[j - 1]
                val b = sampled[j]
                val distanceM = (b.routeKm - a.routeKm) * 1000.0
                val gradePct = if (distanceM > 2.0) (b.ele - a.ele) / distanceM * 100.0 else 0.0
                val color = gradeColor(gradePct)

                gradeFillPaint.color = color
                gradeFillPaint.alpha = 78
                val area = Path().apply {
                    moveTo(x(a.routeKm), y(a.ele))
                    lineTo(x(b.routeKm), y(b.ele))
                    lineTo(x(b.routeKm), bottom)
                    lineTo(x(a.routeKm), bottom)
                    close()
                }
                canvas.drawPath(area, gradeFillPaint)

                gradePaint.color = color
                canvas.drawLine(x(a.routeKm), y(a.ele), x(b.routeKm), y(b.ele), gradePaint)
            }
        } else {
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(fillPath, linePaint)
        }

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

    private fun gradeColor(gradePct: Double): Int = when {
        gradePct < -2.0 -> Color.rgb(66, 165, 245)
        gradePct < 2.0 -> Color.rgb(76, 175, 80)
        gradePct < 5.0 -> Color.rgb(253, 216, 53)
        gradePct < 8.0 -> Color.rgb(251, 140, 0)
        gradePct < 12.0 -> Color.rgb(229, 57, 53)
        else -> Color.rgb(142, 36, 170)
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
            if (px < right - dp(1f)) canvas.drawRect(px, top, right, bottom, recommendedBeyondPaint)
            canvas.drawLine(px, top, px, bottom, recommendedLimitPaint)
        }

        val hard = hardReachKm
        if (hard != null && hard >= visibleStart - 0.001 && hard <= visibleEnd + 0.001) {
            val px = x(hard).coerceIn(left, right)
            if (px < right - dp(1f)) canvas.drawRect(px, top, right, bottom, dangerBeyondPaint)
            canvas.drawLine(px, top, px, bottom, dangerLimitPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
