package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * TimeGate launcher/home screen.
 *
 * The UI intentionally stays drawable/code based so the approved visual scales cleanly across
 * Android phones without shipping a screenshot as the interface. Colors are limited to the
 * approved white / blue / red / black palette.
 */
class TimeGateHomeView(context: Context) : FrameLayout(context) {
    var onTimingClick: (() -> Unit)? = null
    var onWatchClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onSettingsLongClick: (() -> Unit)? = null
    var onBrandLongClick: (() -> Unit)? = null

    private val blue = Color.rgb(12, 91, 235)
    private val red = Color.rgb(255, 18, 56)
    private val black = Color.rgb(8, 10, 13)
    private val white = Color.WHITE

    init {
        setBackgroundColor(white)
        build()
    }

    private fun build() {
        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val scale = (widthDp / 390f).coerceIn(0.88f, 1.12f)
        fun s(v: Int): Int = dp((v * scale).roundToInt())

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(white)
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val contentHeight = max(metrics.heightPixels - dp(24), s(820))
        val content = FrameLayout(context).apply {
            setBackgroundColor(white)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }
        scroll.addView(content)

        content.addView(
            TimeGateArtView(context),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "TimeGate"
            setOnLongClickListener {
                onBrandLongClick?.invoke()
                true
            }
        }
        header.addView(StopwatchLogoView(context), LinearLayout.LayoutParams(s(56), s(56)))

        val brandBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s(7), 0, 0, 0)
        }
        val brandRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        brandRow.addView(TextView(context).apply {
            text = "Time"
            setTextColor(blue)
            textSize = 34f * scale
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        brandRow.addView(TextView(context).apply {
            text = "Gate"
            setTextColor(red)
            textSize = 34f * scale
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        brandBox.addView(brandRow)

        val slogan = SpannableString("생동감 있는 실시간 라이브중계").apply {
            setSpan(ForegroundColorSpan(blue), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(red), 6, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        brandBox.addView(TextView(context).apply {
            text = slogan
            textSize = 12.5f * scale
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        header.addView(brandBox)
        content.addView(header, frameParams(s(292), s(74), s(22), s(22)))

        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        hero.addView(TextView(context).apply {
            text = "LIVE"
            setTextColor(red)
            textSize = 46f * scale
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        hero.addView(TextView(context).apply {
            text = "랩타이머"
            setTextColor(black)
            textSize = 45f * scale
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        content.addView(hero, frameParams(s(258), s(132), s(24), s(164)))

        val timing = makeMenuCard(
            title = "기록측정",
            subtitle = "지금 시작하세요",
            fill = blue,
            foreground = white,
            icon = MenuIcon.PLAY
        ).apply { setOnClickListener { onTimingClick?.invoke() } }
        content.addView(timing, frameParams(s(236), s(72), s(22), s(350)))

        val watch = makeMenuCard(
            title = "관전하기",
            subtitle = "실시간 기록을 확인하세요",
            fill = red,
            foreground = white,
            icon = MenuIcon.MONITOR
        ).apply { setOnClickListener { onWatchClick?.invoke() } }
        content.addView(watch, frameParams(s(236), s(72), s(22), s(432)))

        val settings = makeMenuCard(
            title = "설정",
            subtitle = "앱 설정을 관리하세요",
            fill = white,
            foreground = black,
            icon = MenuIcon.SETTINGS,
            stroke = black
        ).apply {
            setOnClickListener { onSettingsClick?.invoke() }
            setOnLongClickListener {
                onSettingsLongClick?.invoke()
                true
            }
        }
        content.addView(settings, frameParams(s(236), s(72), s(22), s(514)))

        // Compatibility plumbing: BikeModeChooserActivity keeps server/update/admin logic alive,
        // but these maintenance views no longer clutter the public TimeGate home screen.
        content.addView(TextView(context).apply {
            id = R.id.tvBikeModeVersion
            visibility = View.GONE
        }, frameParams(1, 1, 0, 0))
        content.addView(TextView(context).apply {
            id = R.id.tvBikeModeServerStatus
            visibility = View.GONE
        }, frameParams(1, 1, 0, 0))
        content.addView(Button(context).apply {
            id = R.id.btnBikeModeCheckUpdate
            visibility = View.GONE
            text = "⬆ 앱 업데이트 확인"
        }, frameParams(1, 1, 0, 0))
        content.addView(Button(context).apply {
            id = R.id.btnBikeModeAdmin
            visibility = View.GONE
            text = "관리자"
        }, frameParams(1, 1, 0, 0))
    }

    private fun makeMenuCard(
        title: String,
        subtitle: String,
        fill: Int,
        foreground: Int,
        icon: MenuIcon,
        stroke: Int? = null
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(fill)
                stroke?.let { setStroke(dp(1), it) }
            }

            addView(MenuIconView(context, icon, foreground), LinearLayout.LayoutParams(dp(48), dp(48)))

            val textBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            textBox.addView(TextView(context).apply {
                text = title
                setTextColor(foreground)
                textSize = 20f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                includeFontPadding = false
            })
            textBox.addView(TextView(context).apply {
                text = subtitle
                setTextColor(foreground)
                alpha = 0.88f
                textSize = 12f
                includeFontPadding = false
            })
            addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = "›"
                setTextColor(foreground)
                textSize = 38f
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun frameParams(w: Int, h: Int, left: Int, top: Int) = FrameLayout.LayoutParams(w, h).apply {
        leftMargin = left
        topMargin = top
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private enum class MenuIcon { PLAY, MONITOR, SETTINGS }

    private inner class StopwatchLogoView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.48f
            val cy = height * 0.55f
            val r = width * 0.34f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width * 0.075f
            paint.color = blue
            canvas.drawCircle(cx, cy, r, paint)

            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                RectF(cx - width * 0.10f, cy - r - height * 0.16f, cx + width * 0.10f, cy - r - height * 0.07f),
                width * 0.03f,
                width * 0.03f,
                paint
            )

            paint.color = red
            canvas.save()
            canvas.rotate(43f, cx + r * 0.72f, cy - r * 0.72f)
            canvas.drawRoundRect(
                RectF(cx + r * 0.57f, cy - r * 0.88f, cx + r * 0.92f, cy - r * 0.65f),
                width * 0.02f,
                width * 0.02f,
                paint
            )
            canvas.restore()

            paint.strokeWidth = width * 0.055f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx, cy, cx + r * 0.40f, cy - r * 0.42f, paint)
            canvas.drawCircle(cx, cy, width * 0.045f, paint)
        }
    }

    private inner class MenuIconView(context: Context, private val icon: MenuIcon, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width * 0.34f
            paint.color = color
            paint.strokeWidth = width * 0.07f

            when (icon) {
                MenuIcon.PLAY -> {
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                    val path = Path().apply {
                        moveTo(cx - r * 0.32f, cy - r * 0.48f)
                        lineTo(cx + r * 0.58f, cy)
                        lineTo(cx - r * 0.32f, cy + r * 0.48f)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                MenuIcon.MONITOR -> {
                    paint.style = Paint.Style.STROKE
                    val box = RectF(cx - r, cy - r * 0.72f, cx + r, cy + r * 0.45f)
                    canvas.drawRoundRect(box, width * 0.06f, width * 0.06f, paint)
                    canvas.drawLine(cx, cy + r * 0.45f, cx, cy + r * 0.82f, paint)
                    canvas.drawLine(cx - r * 0.42f, cy + r * 0.82f, cx + r * 0.42f, cy + r * 0.82f, paint)
                }
                MenuIcon.SETTINGS -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = width * 0.075f
                    canvas.drawCircle(cx, cy, r * 0.48f, paint)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val x1 = cx + cos(a).toFloat() * r * 0.68f
                        val y1 = cy + sin(a).toFloat() * r * 0.68f
                        val x2 = cx + cos(a).toFloat() * r
                        val y2 = cy + sin(a).toFloat() * r
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
            }
        }
    }

    private inner class TimeGateArtView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = blue
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            routePaint.strokeWidth = w * 0.018f
            val startX = w * 0.80f
            val startY = h * 0.135f
            val cpX = w * 0.82f
            val cpY = h * 0.325f
            val finishX = w * 0.75f
            val finishY = h * 0.545f

            val route = Path().apply {
                moveTo(startX, startY)
                cubicTo(w * 0.70f, h * 0.18f, w * 0.91f, h * 0.23f, cpX, cpY)
                cubicTo(w * 0.70f, h * 0.38f, w * 0.90f, h * 0.45f, finishX, finishY)
            }
            canvas.drawPath(route, routePaint)

            drawStart(canvas, startX, startY - h * 0.025f, w)
            drawCheckpoint(canvas, cpX, cpY, w)
            drawFinish(canvas, finishX, finishY, w)
            drawStopwatch(canvas, w, h)
        }

        private fun drawStart(canvas: Canvas, x: Float, y: Float, w: Float) {
            val r = w * 0.026f
            paint.style = Paint.Style.FILL
            paint.color = red
            canvas.drawCircle(x, y, r, paint)
            val pin = Path().apply {
                moveTo(x - r * 0.72f, y + r * 0.55f)
                lineTo(x + r * 0.72f, y + r * 0.55f)
                lineTo(x, y + r * 1.65f)
                close()
            }
            canvas.drawPath(pin, paint)
            paint.color = white
            canvas.drawCircle(x, y, r * 0.38f, paint)
            drawText(canvas, "START", x + r * 1.55f, y + r * 0.45f, w * 0.036f, black, Paint.Align.LEFT)
        }

        private fun drawCheckpoint(canvas: Canvas, x: Float, y: Float, w: Float) {
            val r = w * 0.030f
            paint.style = Paint.Style.FILL
            paint.color = blue
            canvas.drawCircle(x, y, r, paint)
            paint.color = white
            canvas.drawCircle(x, y, r * 0.70f, paint)
            paint.color = red
            canvas.drawCircle(x, y, r * 0.42f, paint)
            drawText(canvas, "CP1", x + r * 1.35f, y + r * 0.45f, w * 0.036f, black, Paint.Align.LEFT)
        }

        private fun drawFinish(canvas: Canvas, x: Float, y: Float, w: Float) {
            val flagW = w * 0.085f
            val flagH = flagW * 0.65f
            paint.style = Paint.Style.FILL
            paint.color = black
            canvas.drawRoundRect(
                RectF(x - flagW * 0.65f, y - flagH * 0.80f, x - flagW * 0.58f, y + flagH * 0.75f),
                2f,
                2f,
                paint
            )

            val left = x - flagW * 0.58f
            val top = y - flagH * 0.75f
            val cellW = flagW / 4f
            val cellH = flagH / 3f
            for (row in 0 until 3) {
                for (col in 0 until 4) {
                    paint.color = if ((row + col) % 2 == 0) black else white
                    canvas.drawRect(
                        left + col * cellW,
                        top + row * cellH,
                        left + (col + 1) * cellW,
                        top + (row + 1) * cellH,
                        paint
                    )
                }
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2f, w * 0.004f)
            paint.color = black
            canvas.drawRect(left, top, left + flagW, top + flagH, paint)

            paint.style = Paint.Style.FILL
            paint.color = red
            canvas.drawCircle(x - flagW * 0.62f, y + flagH * 0.82f, w * 0.012f, paint)
            drawText(canvas, "FINISH", x + flagW * 0.72f, y + flagH * 0.23f, w * 0.036f, black, Paint.Align.LEFT)
        }

        private fun drawStopwatch(canvas: Canvas, w: Float, h: Float) {
            val sw = w * 0.70f
            val sh = h * 0.31f
            val left = w * 0.48f
            val top = h * 0.625f
            val cx = left + sw * 0.50f
            val cy = top + sh * 0.47f

            canvas.save()
            canvas.rotate(-7f, cx, cy)

            paint.style = Paint.Style.FILL
            paint.color = blue
            canvas.drawRoundRect(RectF(left, top, left + sw, top + sh), w * 0.055f, w * 0.055f, paint)
            paint.color = black
            canvas.drawRoundRect(
                RectF(left + w * 0.018f, top + h * 0.015f, left + sw - w * 0.018f, top + sh - h * 0.015f),
                w * 0.045f,
                w * 0.045f,
                paint
            )
            paint.color = blue
            canvas.drawRoundRect(
                RectF(left + w * 0.032f, top + h * 0.028f, left + sw - w * 0.032f, top + sh - h * 0.028f),
                w * 0.040f,
                w * 0.040f,
                paint
            )
            paint.color = white
            val face = RectF(
                left + w * 0.070f,
                top + h * 0.075f,
                left + sw - w * 0.055f,
                top + sh - h * 0.038f
            )
            canvas.drawRoundRect(face, w * 0.030f, w * 0.030f, paint)

            paint.color = red
            canvas.drawRoundRect(
                RectF(left + sw * 0.29f, top - h * 0.035f, left + sw * 0.52f, top + h * 0.008f),
                w * 0.025f,
                w * 0.025f,
                paint
            )
            canvas.drawRoundRect(
                RectF(left + sw * 0.05f, top + h * 0.008f, left + sw * 0.17f, top + h * 0.055f),
                w * 0.018f,
                w * 0.018f,
                paint
            )

            drawText(canvas, "TIME", face.left + w * 0.030f, face.top + h * 0.055f, w * 0.036f, black, Paint.Align.LEFT)
            drawText(canvas, "00:12.34", face.left + w * 0.025f, face.top + h * 0.135f, w * 0.073f, black, Paint.Align.LEFT)
            drawText(canvas, "BEST", face.left + w * 0.030f, face.top + h * 0.205f, w * 0.032f, black, Paint.Align.LEFT)
            drawText(canvas, "00:09.87", face.right - w * 0.030f, face.top + h * 0.205f, w * 0.031f, black, Paint.Align.RIGHT)
            drawText(canvas, "TOTAL", face.left + w * 0.030f, face.top + h * 0.245f, w * 0.032f, black, Paint.Align.LEFT)
            drawText(canvas, "01:23.56", face.right - w * 0.030f, face.top + h * 0.245f, w * 0.031f, black, Paint.Align.RIGHT)

            canvas.restore()

            paint.color = red
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = w * 0.010f
            canvas.drawLine(w * 0.64f, h * 0.610f, w * 0.62f, h * 0.585f, paint)
            canvas.drawLine(w * 0.68f, h * 0.600f, w * 0.68f, h * 0.570f, paint)
            canvas.drawLine(w * 0.72f, h * 0.610f, w * 0.74f, h * 0.585f, paint)
        }

        private fun drawText(
            canvas: Canvas,
            value: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int,
            align: Paint.Align
        ) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = size
            paint.textAlign = align
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(value, x, y, paint)
        }
    }
}
