package com.seungjae.jangsu280battery

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

class TimeGateHomeView(context: Context) : FrameLayout(context) {
    var onTimingClick: (() -> Unit)? = null
    var onWatchClick: (() -> Unit)? = null
    var onMapClick: (() -> Unit)? = null
    var onGranfondoClick: (() -> Unit)? = null
    var onAvinoxClick: (() -> Unit)? = null
    var onLabClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onSettingsLongClick: (() -> Unit)? = null
    var onBrandLongClick: (() -> Unit)? = null

    private var adminUnlocked = false
    private val art = HomeArt(context)

    init {
        setBackgroundColor(Color.WHITE)
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addHotspot(58f, 660f, 600f, 762f) { onTimingClick?.invoke() }
        addHotspot(58f, 772f, 600f, 874f) { onWatchClick?.invoke() }
        addHotspot(58f, 884f, 600f, 986f) {
            onMapClick?.invoke() ?: context.startActivity(Intent(context, RaceTrackBuilderActivity::class.java))
        }
        addHotspot(58f, 996f, 600f, 1098f) {
            onGranfondoClick?.invoke() ?: context.startActivity(Intent(context, RoadGranfondoActivity::class.java))
        }
        addHotspot(58f, 1108f, 600f, 1210f, adminOnly = true) {
            onAvinoxClick?.invoke() ?: context.startActivity(Intent(context, AvinoxSystemActivity::class.java))
        }
        addHotspot(58f, 1220f, 600f, 1322f, adminOnly = true) {
            onLabClick?.invoke() ?: context.startActivity(Intent(context, TimeGateLabActivity::class.java))
        }
        addHotspot(58f, 1332f, 600f, 1434f, longClick = { onSettingsLongClick?.invoke() }) {
            onSettingsClick?.invoke() ?: context.startActivity(Intent(context, TimeGateGeneralSettingsActivity::class.java))
        }
        addHotspot(55f, 120f, 610f, 305f, longClick = { onBrandLongClick?.invoke() }) { }

        addView(TextView(context).apply { id = R.id.tvBikeModeVersion; visibility = View.GONE }, LayoutParams(1, 1))
        addView(TextView(context).apply { id = R.id.tvBikeModeServerStatus; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeCheckUpdate; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeAdmin; visibility = View.GONE }, LayoutParams(1, 1))
    }

    fun setAdminUnlocked(value: Boolean) {
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
            if (longClick != null) setOnLongClickListener { longClick(); true }
        }
        addView(v)
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> position(view, x1, y1, x2, y2) }
        post { position(v, x1, y1, x2, y2) }
    }

    private fun position(v: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        if (width <= 0 || height <= 0) return
        val s = min(width / REF_W, height / REF_H)
        val ox = (width - REF_W * s) / 2f
        val oy = (height - REF_H * s) / 2f
        v.layoutParams = LayoutParams(((x2 - x1) * s).roundToInt(), ((y2 - y1) * s).roundToInt()).apply {
            leftMargin = (ox + x1 * s).roundToInt()
            topMargin = (oy + y1 * s).roundToInt()
        }
    }

    private inner class HomeArt(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blue = Color.rgb(12, 91, 235)
        private val red = Color.rgb(255, 18, 56)
        private val black = Color.rgb(4, 5, 8)
        private val gray = Color.rgb(238, 243, 249)
        private val charcoal = Color.rgb(40, 46, 58)
        private val darkGray = Color.rgb(39, 49, 64)

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.WHITE)
            val s = min(width / REF_W, height / REF_H)
            val ox = (width - REF_W * s) / 2f
            val oy = (height - REF_H * s) / 2f
            c.save()
            c.translate(ox, oy)
            c.scale(s, s)

            drawBrand(c)
            drawHero(c)
            drawRoute(c)

            menu(c, 660f, red, Color.WHITE, "기록측정", "지금 시작하세요", Icon.PLAY)
            menu(c, 772f, blue, Color.WHITE, "관전하기", "실시간 기록을 확인하세요", Icon.MONITOR)
            menu(c, 884f, black, Color.WHITE, "맵만들기", "GPX파일 불러오기 및 제작하기", Icon.MAP)
            menu(c, 996f, red, Color.WHITE, "그란폰도", "ROAD · 페이스 코치", Icon.BIKE)
            menu(c, 1108f, black, Color.WHITE, "AVINOX SYSTEM", "eMTB · 배터리 · 학습 · 분석", Icon.BATTERY, !adminUnlocked)
            menu(c, 1220f, charcoal, Color.WHITE, "실험실", "테스트모드", Icon.LAB, !adminUnlocked)
            menu(c, 1332f, gray, black, "설정", "음성 · 화면 · 업데이트 · 버전", Icon.SETTINGS)

            c.restore()
        }

        private fun drawBrand(c: Canvas) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 9f
            p.color = blue
            c.drawCircle(112f, 194f, 48f, p)
            p.style = Paint.Style.FILL
            c.drawRoundRect(RectF(100f, 128f, 124f, 142f), 4f, 4f, p)
            p.color = red
            c.drawCircle(151f, 155f, 8f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            c.drawLine(112f, 194f, 139f, 169f, p)
            text(c, "Time", 182f, 215f, 72f, blue, true)
            text(c, "Gate", 355f, 215f, 72f, red, true)
            text(c, "생동감 있는", 185f, 262f, 27f, blue, true)
            text(c, "실시간 라이브중계", 336f, 262f, 27f, red, true)
        }

        private fun drawHero(c: Canvas) {
            text(c, "LIVE", 58f, 448f, 63f, red, true)
            text(c, "랩타이머", 56f, 555f, 112f, black, true)
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
            p.color = Color.WHITE
            c.drawCircle(720f, 224f, 12f, p)
            text(c, "START", 765f, 242f, 35f, black, true)
            p.color = blue
            c.drawCircle(746f, 540f, 31f, p)
            p.color = Color.WHITE
            c.drawCircle(746f, 540f, 22f, p)
            p.color = red
            c.drawCircle(746f, 540f, 14f, p)
            text(c, "CP1", 786f, 553f, 34f, black, true)

            p.color = black
            c.drawRoundRect(RectF(672f, 812f, 680f, 895f), 3f, 3f, p)
            val left = 679f
            val top = 820f
            val cw = 18f
            val ch = 18f
            for (r in 0..2) for (col in 0..3) {
                p.color = if ((r + col) % 2 == 0) black else Color.WHITE
                c.drawRect(left + col * cw, top + r * ch, left + (col + 1) * cw, top + (r + 1) * ch, p)
            }
            p.style = Paint.Style.STROKE
            p.color = black
            p.strokeWidth = 3f
            c.drawRect(left, top, left + 72f, top + 54f, p)
            text(c, "FINISH", 765f, 895f, 34f, black, true)
        }

        private fun menu(c: Canvas, top: Float, fill: Int, fg: Int, title: String, sub: String, icon: Icon, locked: Boolean = false) {
            val bottom = top + 102f
            p.style = Paint.Style.FILL
            p.color = fill
            c.drawRoundRect(RectF(58f, top, 600f, bottom), 24f, 24f, p)
            drawIcon(c, 132f, (top + bottom) / 2f, fg, icon)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = if (fg == black) Color.rgb(195, 205, 218) else Color.argb(120, 255, 255, 255)
            c.drawLine(210f, top + 22f, 210f, bottom - 22f, p)
            val titleSize = if (title.length > 11) 28f else 35f
            text(c, title, 232f, top + 47f, titleSize, fg, true)
            text(c, sub, 232f, top + 79f, 20f, if (fg == black) darkGray else fg, false)
            if (locked) drawLock(c, 555f, (top + bottom) / 2f, fg) else text(c, "›", 548f, top + 73f, 52f, fg, false)
        }

        private fun drawIcon(c: Canvas, x: Float, y: Float, color: Int, icon: Icon) {
            p.color = color
            p.strokeWidth = 6f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            when (icon) {
                Icon.PLAY -> {
                    p.style = Paint.Style.STROKE
                    c.drawCircle(x, y, 31f, p)
                    p.style = Paint.Style.FILL
                    c.drawPath(Path().apply { moveTo(x - 8f, y - 17f); lineTo(x + 20f, y); lineTo(x - 8f, y + 17f); close() }, p)
                }
                Icon.MONITOR -> {
                    p.style = Paint.Style.STROKE
                    c.drawRoundRect(RectF(x - 35f, y - 24f, x + 35f, y + 16f), 5f, 5f, p)
                    c.drawLine(x, y + 16f, x, y + 32f, p)
                    c.drawLine(x - 20f, y + 32f, x + 20f, y + 32f, p)
                }
                Icon.MAP -> {
                    p.style = Paint.Style.STROKE
                    c.drawRect(x - 32f, y - 25f, x + 32f, y + 25f, p)
                    c.drawLine(x - 10f, y - 25f, x - 10f, y + 25f, p)
                    c.drawLine(x + 10f, y - 25f, x + 10f, y + 25f, p)
                }
                Icon.BIKE -> {
                    p.style = Paint.Style.STROKE
                    c.drawCircle(x - 23f, y + 15f, 17f, p)
                    c.drawCircle(x + 24f, y + 15f, 17f, p)
                    c.drawLine(x - 23f, y + 15f, x - 2f, y - 10f, p)
                    c.drawLine(x - 2f, y - 10f, x + 12f, y + 15f, p)
                    c.drawLine(x + 12f, y + 15f, x - 23f, y + 15f, p)
                }
                Icon.BATTERY -> {
                    p.style = Paint.Style.STROKE
                    c.drawRoundRect(RectF(x - 32f, y - 20f, x + 26f, y + 20f), 5f, 5f, p)
                    c.drawRect(x + 26f, y - 7f, x + 34f, y + 7f, p)
                }
                Icon.LAB -> {
                    p.style = Paint.Style.STROKE
                    c.drawLine(x - 9f, y - 31f, x + 9f, y - 31f, p)
                    c.drawLine(x - 6f, y - 31f, x - 6f, y - 7f, p)
                    c.drawLine(x + 6f, y - 31f, x + 6f, y - 7f, p)
                    c.drawPath(Path().apply { moveTo(x - 6f, y - 7f); lineTo(x - 25f, y + 25f); quadTo(x, y + 35f, x + 25f, y + 25f); lineTo(x + 6f, y - 7f) }, p)
                }
                Icon.SETTINGS -> {
                    p.style = Paint.Style.STROKE
                    c.drawCircle(x, y, 17f, p)
                    c.drawCircle(x, y, 31f, p)
                }
            }
        }

        private fun drawLock(c: Canvas, x: Float, y: Float, color: Int) {
            p.color = color
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            c.drawRoundRect(RectF(x - 16f, y - 2f, x + 16f, y + 23f), 5f, 5f, p)
            c.drawArc(RectF(x - 12f, y - 23f, x + 12f, y + 4f), 190f, 160f, false, p)
        }

        private fun text(c: Canvas, v: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
            p.style = Paint.Style.FILL
            p.color = color
            p.textSize = size
            p.textAlign = Paint.Align.LEFT
            p.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            c.drawText(v, x, y, p)
        }
    }

    private enum class Icon { PLAY, MONITOR, MAP, BIKE, BATTERY, LAB, SETTINGS }

    companion object {
        private const val REF_W = 941f
        private const val REF_H = 1672f
    }
}
