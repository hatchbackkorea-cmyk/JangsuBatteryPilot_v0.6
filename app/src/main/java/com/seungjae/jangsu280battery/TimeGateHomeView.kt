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
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * TimeGate fixed-coordinate home screen.
 * The approved composition is rendered as one canvas and transparent hit areas are layered above it.
 */
class TimeGateHomeView(context: Context) : FrameLayout(context) {
    var onTimingClick: (() -> Unit)? = null
    var onWatchClick: (() -> Unit)? = null
    var onMapClick: (() -> Unit)? = null
    var onEmtbClick: (() -> Unit)? = null
    var onGranfondoClick: (() -> Unit)? = null
    var onAvinoxClick: (() -> Unit)? = null
    var onLabClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onSettingsLongClick: (() -> Unit)? = null
    var onBrandLongClick: (() -> Unit)? = null

    private var adminUnlocked = false
    private val art = HomeBackgroundView(context)

    init {
        (context as? Activity)?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setBackgroundColor(Color.WHITE)
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Eight compact menu rows. Coordinates are based on the same 941x1672 design canvas.
        addHotspot(58f, 650f, 600f, 752f) { onTimingClick?.invoke() }
        addHotspot(58f, 762f, 600f, 864f) { onWatchClick?.invoke() }
        addHotspot(58f, 874f, 600f, 976f) {
            onMapClick?.invoke() ?: context.startActivity(Intent(context, RaceTrackBuilderActivity::class.java))
        }
        addHotspot(58f, 986f, 600f, 1088f) {
            onEmtbClick?.invoke() ?: context.startActivity(Intent(context, MainActivity::class.java))
        }
        addHotspot(58f, 1098f, 600f, 1200f) {
            onGranfondoClick?.invoke() ?: context.startActivity(Intent(context, RoadGranfondoActivity::class.java))
        }
        addHotspot(58f, 1210f, 600f, 1312f, adminOnly = true) {
            onAvinoxClick?.invoke() ?: context.startActivity(Intent(context, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_AVINOX)
            })
        }
        addHotspot(58f, 1322f, 600f, 1424f, adminOnly = true) {
            onLabClick?.invoke() ?: context.startActivity(Intent(context, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_LAB)
            })
        }
        addHotspot(
            58f, 1434f, 600f, 1536f,
            longClick = { onSettingsLongClick?.invoke() }
        ) {
            onSettingsClick?.invoke() ?: context.startActivity(Intent(context, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_GENERAL)
            })
        }
        addHotspot(55f, 120f, 610f, 305f, longClick = { onBrandLongClick?.invoke() }) { }

        // Compatibility plumbing used by BikeModeChooserActivity. Hidden from the public home UI.
        addView(TextView(context).apply { id = R.id.tvBikeModeVersion; visibility = View.GONE }, LayoutParams(1, 1))
        addView(TextView(context).apply { id = R.id.tvBikeModeServerStatus; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeCheckUpdate; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeAdmin; visibility = View.GONE }, LayoutParams(1, 1))
    }

    fun setAdminUnlocked(value: Boolean) {
        if (adminUnlocked == value) return
        adminUnlocked = value
        art.invalidate()
    }

    private fun addHotspot(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        adminOnly: Boolean = false,
        longClick: (() -> Unit)? = null,
        click: () -> Unit
    ) {
        val v = View(context).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (adminOnly && !adminUnlocked) {
                    Toast.makeText(context, "관리자 핸드폰에서만 열 수 있는 메뉴입니다.", Toast.LENGTH_SHORT).show()
                } else {
                    click()
                }
            }
            if (longClick != null) {
                setOnLongClickListener {
                    longClick()
                    true
                }
            }
        }
        addView(v)
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> positionHotspot(view, x1, y1, x2, y2) }
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
        private val charcoal = Color.rgb(40, 46, 58)

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

            drawMenu(canvas, 58f, 650f, 600f, 752f, red, Color.WHITE, "기록측정", "지금 시작하세요", 0)
            drawMenu(canvas, 58f, 762f, 600f, 864f, blue, Color.WHITE, "관전하기", "실시간 기록을 확인하세요", 1)
            drawMenu(canvas, 58f, 874f, 600f, 976f, black, Color.WHITE, "맵만들기", "GPX파일 불러오기 및 제작하기", 2)
            drawMenu(canvas, 58f, 986f, 600f, 1088f, blue, Color.WHITE, "eMTB", "배터리 코파일럿", 3)
            drawMenu(canvas, 58f, 1098f, 600f, 1200f, red, Color.WHITE, "그란폰도", "ROAD · 페이스 코치", 4)
            drawMenu(canvas, 58f, 1210f, 600f, 1312f, black, Color.WHITE, "AVINOX SYSTEM", "배터리 · 학습 · 라이더 분석", 6, locked = !adminUnlocked)
            drawMenu(canvas, 58f, 1322f, 600f, 1424f, charcoal, Color.WHITE, "실험실", "테스트모드 · 모바일 소스배포", 7, locked = !adminUnlocked)
            drawMenu(canvas, 58f, 1434f, 600f, 1536f, gray, black, "설정", "음성 · 화면 · 업데이트 · 버전", 5)

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
            icon: Int,
            locked: Boolean = false
        ) {
            p.style = Paint.Style.FILL
            p.color = fill
            c.drawRoundRect(RectF(l, t, r, b), 24f, 24f, p)

            val ix = 132f
            val iy = (t + b) / 2f
            when (icon) {
                0 -> drawPlayIcon(c, ix, iy, fg)
                1 -> drawMonitorIcon(c, ix, iy, fg)
                2 -> drawMapIcon(c, ix, iy, fg)
                3 -> drawLightningIcon(c, ix, iy, fg)
                4 -> drawBikeIcon(c, ix, iy, fg)
                5 -> drawSettingsIcon(c, ix, iy, fg)
                6 -> drawBatteryIcon(c, ix, iy, fg)
                7 -> drawFlaskIcon(c, ix, iy, fg)
            }

            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = if (fg == black) Color.rgb(195, 205, 218) else Color.argb(120, 255, 255, 255)
            c.drawLine(210f, t + 22f, 210f, b - 22f, p)

            val titleSize = if (title.length > 11) 28f else 35f
            text(c, title, 232f, t + 47f, titleSize, fg, Paint.Align.LEFT, true)
            text(c, sub, 232f, t + 79f, 20f, if (fg == black) darkGray else fg, Paint.Align.LEFT, false)
            if (locked) drawLockIcon(c, 555f, (t + b) / 2f, fg)
            else text(c, "›", 557f, t + 70f, 52f, fg, Paint.Align.CENTER, false)
        }

        private fun drawPlayIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            c.drawCircle(x, y, 32f, p)
            p.style = Paint.Style.FILL
            val q = Path().apply {
                moveTo(x - 8f, y - 18f)
                lineTo(x + 21f, y)
                lineTo(x - 8f, y + 18f)
                close()
            }
            c.drawPath(q, p)
        }

        private fun drawMonitorIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            c.drawRoundRect(RectF(x - 36f, y - 25f, x + 36f, y + 17f), 5f, 5f, p)
            c.drawLine(x, y + 17f, x, y + 34f, p)
            c.drawLine(x - 21f, y + 34f, x + 21f, y + 34f, p)
        }

        private fun drawMapIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            val q = Path().apply {
                moveTo(x - 35f, y - 24f)
                lineTo(x - 12f, y - 32f)
                lineTo(x + 12f, y - 24f)
                lineTo(x + 35f, y - 32f)
                lineTo(x + 35f, y + 24f)
                lineTo(x + 12f, y + 32f)
                lineTo(x - 12f, y + 24f)
                lineTo(x - 35f, y + 32f)
                close()
            }
            c.drawPath(q, p)
            c.drawLine(x - 12f, y - 32f, x - 12f, y + 24f, p)
            c.drawLine(x + 12f, y - 24f, x + 12f, y + 32f, p)
        }

        private fun drawLightningIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.FILL
            val q = Path().apply {
                moveTo(x + 7f, y - 35f)
                lineTo(x - 24f, y + 4f)
                lineTo(x - 2f, y + 4f)
                lineTo(x - 10f, y + 35f)
                lineTo(x + 26f, y - 7f)
                lineTo(x + 4f, y - 7f)
                close()
            }
            c.drawPath(q, p)
        }

        private fun drawBikeIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            c.drawCircle(x - 24f, y + 16f, 18f, p)
            c.drawCircle(x + 26f, y + 16f, 18f, p)
            c.drawLine(x - 24f, y + 16f, x - 3f, y - 11f, p)
            c.drawLine(x - 3f, y - 11f, x + 13f, y + 16f, p)
            c.drawLine(x + 13f, y + 16f, x - 24f, y + 16f, p)
            c.drawLine(x - 3f, y - 11f, x + 20f, y - 11f, p)
            c.drawLine(x + 20f, y - 11f, x + 26f, y + 16f, p)
        }

        private fun drawSettingsIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            c.drawCircle(x, y, 17f, p)
            for (k in 0..7) {
                val a = Math.toRadians(k * 45.0)
                val x1 = x + cos(a).toFloat() * 24f
                val y1 = y + sin(a).toFloat() * 24f
                val x2 = x + cos(a).toFloat() * 34f
                val y2 = y + sin(a).toFloat() * 34f
                c.drawLine(x1, y1, x2, y2, p)
            }
        }

        private fun drawBatteryIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            c.drawRoundRect(RectF(x - 34f, y - 22f, x + 28f, y + 22f), 6f, 6f, p)
            c.drawRoundRect(RectF(x + 28f, y - 8f, x + 36f, y + 8f), 2f, 2f, p)
            p.style = Paint.Style.FILL
            val bolt = Path().apply {
                moveTo(x + 2f, y - 18f)
                lineTo(x - 14f, y + 3f)
                lineTo(x - 2f, y + 3f)
                lineTo(x - 7f, y + 18f)
                lineTo(x + 13f, y - 5f)
                lineTo(x + 2f, y - 5f)
                close()
            }
            c.drawPath(bolt, p)
        }

        private fun drawFlaskIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            c.drawLine(x - 10f, y - 34f, x + 10f, y - 34f, p)
            c.drawLine(x - 7f, y - 34f, x - 7f, y - 8f, p)
            c.drawLine(x + 7f, y - 34f, x + 7f, y - 8f, p)
            val q = Path().apply {
                moveTo(x - 7f, y - 8f)
                lineTo(x - 29f, y + 27f)
                quadraticTo(x, y + 39f, x + 29f, y + 27f)
                lineTo(x + 7f, y - 8f)
            }
            c.drawPath(q, p)
            c.drawLine(x - 20f, y + 16f, x + 20f, y + 16f, p)
        }

        private fun drawLockIcon(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            c.drawRoundRect(RectF(x - 17f, y - 3f, x + 17f, y + 24f), 5f, 5f, p)
            c.drawArc(RectF(x - 13f, y - 24f, x + 13f, y + 5f), 190f, 160f, false, p)
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
