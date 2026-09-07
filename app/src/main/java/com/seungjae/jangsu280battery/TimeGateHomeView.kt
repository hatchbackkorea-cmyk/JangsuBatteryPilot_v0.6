package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * TimeGate fixed-coordinate home screen.
 *
 * The approved 941x1672 composition is treated as one background canvas and only transparent
 * hit areas are layered above it. This keeps the visual identical across devices while the hit
 * areas scale with the same aspect ratio.
 */
class TimeGateHomeView(context: Context) : FrameLayout(context) {
    var onTimingClick: (() -> Unit)? = null
    var onWatchClick: (() -> Unit)? = null
    var onMapClick: (() -> Unit)? = null
    var onEmtbClick: (() -> Unit)? = null
    var onGranfondoClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onSettingsLongClick: (() -> Unit)? = null
    var onBrandLongClick: (() -> Unit)? = null

    private val art = HomeBackgroundView(context)

    init {
        (context as? Activity)?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setBackgroundColor(Color.WHITE)
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addHotspot(58f, 670f, 585f, 800f) { onTimingClick?.invoke() }
        addHotspot(58f, 818f, 585f, 948f) { onWatchClick?.invoke() }
        addHotspot(58f, 966f, 585f, 1096f) {
            onMapClick?.invoke() ?: context.startActivity(Intent(context, RaceTrackBuilderActivity::class.java))
        }
        addHotspot(58f, 1114f, 585f, 1244f) {
            onEmtbClick?.invoke() ?: context.startActivity(Intent(context, MainActivity::class.java))
        }
        addHotspot(58f, 1262f, 585f, 1392f) {
            onGranfondoClick?.invoke() ?: context.startActivity(Intent(context, RoadGranfondoActivity::class.java))
        }
        addHotspot(
            58f, 1410f, 585f, 1540f,
            longClick = { onSettingsLongClick?.invoke() }
        ) { onSettingsClick?.invoke() }
        addHotspot(55f, 120f, 610f, 305f, longClick = { onBrandLongClick?.invoke() }) { }

        addView(TextView(context).apply { id = R.id.tvBikeModeVersion; visibility = View.GONE }, LayoutParams(1, 1))
        addView(TextView(context).apply { id = R.id.tvBikeModeServerStatus; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeCheckUpdate; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeAdmin; visibility = View.GONE }, LayoutParams(1, 1))
    }

    private fun addHotspot(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        longClick: (() -> Unit)? = null,
        click: () -> Unit
    ) {
        val v = View(context).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { click() }
            if (longClick != null) {
                setOnLongClickListener {
                    longClick()
                    true
                }
            }
        }
        addView(v)
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            positionHotspot(view, x1, y1, x2, y2)
        }
        post { positionHotspot(v, x1, y1, x2, y2) }
    }

    private fun positionHotspot(v: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        if (width <= 0 || height <= 0) return
        val s = min(width / REF_W, height / REF_H)
        val drawnW = REF_W * s
        val drawnH = REF_H * s
        val ox = (width - drawnW) / 2f
        val oy = (height - drawnH) / 2f
        v.layoutParams = LayoutParams(
            ((x2 - x1) * s).roundToInt(),
            ((y2 - y1) * s).roundToInt()
        ).apply {
            leftMargin = (ox + x1 * s).roundToInt()
            topMargin = (oy + y1 * s).roundToInt()
        }
    }

    private inner class HomeBackgroundView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blue = Color.rgb(12, 91, 235)
        private val red = Color.rgb(255, 18, 56)
        private val black = Color.rgb(4, 5, 8)
        private val gray = Color.rgb(238, 243, 249)
        private val darkGray = Color.rgb(39, 49, 64)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.WHITE)
            val s = min(width / REF_W, height / REF_H)
            val ox = (width - REF_W * s) / 2f
            val oy = (height - REF_H * s) / 2f
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(s, s)

            drawBrand(canvas)
            drawHero(canvas)
            drawRoute(canvas)

            drawMenu(canvas, 58f, 670f, 585f, 800f, red, Color.WHITE, "기록측정", "지금 시작하세요", 0)
            drawMenu(canvas, 58f, 818f, 585f, 948f, blue, Color.WHITE, "관전하기", "실시간 기록을 확인하세요", 1)
            drawMenu(canvas, 58f, 966f, 585f, 1096f, black, Color.WHITE, "맵만들기", "GPX파일 불러오기 및 제작하기", 2)
            drawMenu(canvas, 58f, 1114f, 585f, 1244f, blue, Color.WHITE, "eMTB", "배터리 코파일럿", 3)
            drawMenu(canvas, 58f, 1262f, 585f, 1392f, red, Color.WHITE, "그란폰도", "ROAD · 페이스 코치", 4)
            drawMenu(canvas, 58f, 1410f, 585f, 1540f, gray, black, "설정", "앱 설정을 관리하세요", 5)

            canvas.restore()
        }

        private fun drawBrand(c: Canvas) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 9f
            p.color = blue
            c.drawCircle(112f, 194f, 48f, p)
            p.style = Paint.Style.FILL
            c.drawRoundRect(RectF(100f, 128f, 124f, 142f), 4f, 4f, p)
            p.color = red
            c.save()
            c.rotate(43f, 157f, 157f)
            c.drawRoundRect(RectF(150f, 148f, 165f, 161f), 2f, 2f, p)
            c.restore()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(112f, 194f, 139f, 169f, p)

            text(c, "Time", 182f, 215f, 72f, blue, Paint.Align.LEFT, true)
            text(c, "Gate", 355f, 215f, 72f, red, Paint.Align.LEFT, true)
            text(c, "생동감 있는", 185f, 262f, 27f, blue, Paint.Align.LEFT, true)
            text(c, "실시간 라이브중계", 336f, 262f, 27f, red, Paint.Align.LEFT, true)
        }

        private fun drawHero(c: Canvas) {
            text(c, "LIVE", 58f, 455f, 126f, red, Paint.Align.LEFT, true)
            text(c, "랩타이머", 56f, 625f, 112f, black, Paint.Align.LEFT, true)
        }

        private fun drawRoute(c: Canvas) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 16f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = blue
            val path = Path().apply {
                moveTo(720f, 276f)
                cubicTo(661f, 327f, 766f, 387f, 747f, 482f)
                cubicTo(731f, 557f, 675f, 576f, 743f, 648f)
                cubicTo(786f, 696f, 692f, 744f, 711f, 813f)
            }
            c.drawPath(path, p)

            p.style = Paint.Style.FILL
            p.color = red
            c.drawCircle(720f, 224f, 32f, p)
            val pin = Path().apply {
                moveTo(696f, 240f)
                lineTo(744f, 240f)
                lineTo(720f, 280f)
                close()
            }
            c.drawPath(pin, p)
            p.color = Color.WHITE
            c.drawCircle(720f, 224f, 12f, p)
            text(c, "START", 765f, 242f, 35f, black, Paint.Align.LEFT, true)

            p.color = blue
            c.drawCircle(746f, 540f, 31f, p)
            p.color = Color.WHITE
            c.drawCircle(746f, 540f, 22f, p)
            p.color = red
            c.drawCircle(746f, 540f, 14f, p)
            text(c, "CP1", 786f, 553f, 34f, black, Paint.Align.LEFT, true)

            p.color = red
            c.drawCircle(674f, 900f, 14f, p)
            p.color = black
            c.drawRoundRect(RectF(672f, 812f, 680f, 895f), 3f, 3f, p)
            val left = 679f
            val top = 820f
            val cw = 18f
            val ch = 18f
            for (r in 0..2) {
                for (col in 0..3) {
                    p.color = if ((r + col) % 2 == 0) black else Color.WHITE
                    c.drawRect(left + col * cw, top + r * ch, left + (col + 1) * cw, top + (r + 1) * ch, p)
                }
            }
            p.style = Paint.Style.STROKE
            p.color = black
            p.strokeWidth = 3f
            c.drawRect(left, top, left + 72f, top + 54f, p)
            text(c, "FINISH", 765f, 895f, 34f, black, Paint.Align.LEFT, true)
        }

        private fun drawMenu(
            c: Canvas,
            l: Float,
            t: Float,
            r: Float,
            b: Float,
            fill: Int,
            fg: Int,
            title: String,
            sub: String,
            icon: Int
        ) {
            p.style = Paint.Style.FILL
            p.color = fill
            c.drawRoundRect(RectF(l, t, r, b), 27f, 27f, p)

            val ix = 143f
            val iy = (t + b) / 2f
            when (icon) {
                0 -> drawPlayIcon(c, ix, iy, fg)
                1 -> drawMonitorIcon(c, ix, iy, fg)
                2 -> drawMapIcon(c, ix, iy, fg)
                3 -> drawLightningIcon(c, ix, iy, fg)
                4 -> drawBikeIcon(c, ix, iy, fg)
                5 -> drawSettingsIcon(c, ix, iy, fg)
            }

            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = if (fg == black) Color.rgb(195, 205, 218) else Color.argb(120, 255, 255, 255)
            c.drawLine(226f, t + 29f, 226f, b - 29f, p)

            text(c, title, 250f, t + 62f, 41f, fg, Paint.Align.LEFT, true)
            text(c, sub, 250f, t + 101f, 23f, if (fg == black) darkGray else fg, Paint.Align.LEFT, false)
            text(c, "›", 538f, t + 88f, 62f, fg, Paint.Align.CENTER, false)
        }

        private fun drawPlayIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 8f
            c.drawCircle(x, y, 38f, p)
            p.style = Paint.Style.FILL
            val q = Path().apply {
                moveTo(x - 10f, y - 21f)
                lineTo(x + 24f, y)
                lineTo(x - 10f, y + 21f)
                close()
            }
            c.drawPath(q, p)
        }

        private fun drawMonitorIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 8f
            c.drawRoundRect(RectF(x - 42f, y - 30f, x + 42f, y + 20f), 5f, 5f, p)
            c.drawLine(x, y + 20f, x, y + 40f, p)
            c.drawLine(x - 25f, y + 40f, x + 25f, y + 40f, p)
        }

        private fun drawMapIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            val q = Path().apply {
                moveTo(x - 42f, y - 29f)
                lineTo(x - 14f, y - 38f)
                lineTo(x + 14f, y - 29f)
                lineTo(x + 42f, y - 38f)
                lineTo(x + 42f, y + 29f)
                lineTo(x + 14f, y + 38f)
                lineTo(x - 14f, y + 29f)
                lineTo(x - 42f, y + 38f)
                close()
            }
            c.drawPath(q, p)
            c.drawLine(x - 14f, y - 38f, x - 14f, y + 29f, p)
            c.drawLine(x + 14f, y - 29f, x + 14f, y + 38f, p)
        }

        private fun drawLightningIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.FILL
            val q = Path().apply {
                moveTo(x + 8f, y - 43f)
                lineTo(x - 29f, y + 5f)
                lineTo(x - 3f, y + 5f)
                lineTo(x - 12f, y + 43f)
                lineTo(x + 31f, y - 9f)
                lineTo(x + 5f, y - 9f)
                close()
            }
            c.drawPath(q, p)
        }

        private fun drawBikeIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            c.drawCircle(x - 29f, y + 19f, 22f, p)
            c.drawCircle(x + 31f, y + 19f, 22f, p)
            c.drawLine(x - 29f, y + 19f, x - 4f, y - 13f, p)
            c.drawLine(x - 4f, y - 13f, x + 15f, y + 19f, p)
            c.drawLine(x + 15f, y + 19f, x - 29f, y + 19f, p)
            c.drawLine(x - 4f, y - 13f, x + 24f, y - 13f, p)
            c.drawLine(x + 24f, y - 13f, x + 31f, y + 19f, p)
            c.drawLine(x - 13f, y - 24f, x + 2f, y - 24f, p)
        }

        private fun drawSettingsIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            c.drawCircle(x, y, 20f, p)
            for (k in 0..7) {
                val a = Math.toRadians(k * 45.0)
                val x1 = x + cos(a).toFloat() * 29f
                val y1 = y + sin(a).toFloat() * 29f
                val x2 = x + cos(a).toFloat() * 41f
                val y2 = y + sin(a).toFloat() * 41f
                c.drawLine(x1, y1, x2, y2, p)
            }
        }

        private fun text(
            c: Canvas,
            value: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int,
            align: Paint.Align,
            bold: Boolean
        ) {
            p.style = Paint.Style.FILL
            p.color = color
            p.textSize = size
            p.textAlign = align
            p.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            c.drawText(value, x, y, p)
        }
    }

    companion object {
        private const val REF_W = 941f
        private const val REF_H = 1672f
    }
}
