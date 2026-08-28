package com.seungjae.jangsu280battery

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar
import kotlin.math.roundToInt

/**
 * Full-course distance seek bar used by the ride HUD.
 * - Live ride: read-only current route position.
 * - Test mode: draggable virtual route position.
 * - Planned charge points are drawn as compact yellow ticks.
 *
 * This intentionally represents course/charging distance, not battery SOC.
 */
class ChargeDistanceSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBar(context, attrs) {

    private var totalKm: Double = 1.0
    private var chargeKms: List<Double> = emptyList()
    private var allowUserSeeking: Boolean = false

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = context.getColor(R.color.warn)
        alpha = 230
    }

    init {
        max = 10
        progress = 0
        splitTrack = false
        progressTintList = ColorStateList.valueOf(context.getColor(R.color.good))
        progressBackgroundTintList = ColorStateList.valueOf(context.getColor(R.color.line))
        thumbTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
    }

    fun setCourse(totalKm: Double, plannedChargeKms: List<Double>) {
        this.totalKm = totalKm.coerceAtLeast(0.1)
        chargeKms = plannedChargeKms
            .filter { it > 0.05 && it < this.totalKm - 0.05 }
            .distinct()
            .sorted()
        max = (this.totalKm * 10.0).roundToInt().coerceAtLeast(1)
        progress = progress.coerceIn(0, max)
        invalidate()
    }

    fun setRouteKm(km: Double) {
        progress = (km.coerceIn(0.0, totalKm) * 10.0).roundToInt().coerceIn(0, max)
    }

    fun routeKmForProgress(value: Int = progress): Double =
        (value.coerceIn(0, max) / 10.0).coerceIn(0.0, totalKm)

    fun setUserSeekingEnabled(enabled: Boolean) {
        allowUserSeeking = enabled
        // Keep the same live appearance even when read-only. Android's disabled SeekBar becomes gray,
        // so we do not toggle View.isEnabled here.
        isClickable = enabled
        isFocusable = enabled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!allowUserSeeking) return false
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || totalKm <= 0.0) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val w = (right - left).coerceAtLeast(1f)
        val top = height * 0.16f
        val bottom = height * 0.84f
        chargeKms.forEach { km ->
            val x = left + (km / totalKm).toFloat() * w
            canvas.drawLine(x, top, x, bottom, markerPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
