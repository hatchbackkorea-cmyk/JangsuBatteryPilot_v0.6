package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TimeGate home screen v0.34.31.
 *
 * The approved 941x1672 mock-up is treated as one fixed background canvas.
 * Only invisible hit areas are layered above it, so widgets can no longer drift away
 * from the approved composition. Coordinates below are taken from the approved mock-up.
 */
class TimeGateHomeView(context: Context) : FrameLayout(context) {
    var onTimingClick: (() -> Unit)? = null
    var onWatchClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onSettingsLongClick: (() -> Unit)? = null
    var onBrandLongClick: (() -> Unit)? = null

    private val art = HomeBackgroundView(context)

    init {
        setBackgroundColor(Color.WHITE)
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addHotspot(58f, 670f, 585f, 821f) { onTimingClick?.invoke() }
        addHotspot(58f, 840f, 585f, 987f) { onWatchClick?.invoke() }
        addHotspot(58f, 1007f, 574f, 1155f, longClick = { onSettingsLongClick?.invoke() }) { onSettingsClick?.invoke() }
        addHotspot(55f, 120f, 610f, 305f, longClick = { onBrandLongClick?.invoke() }) { }

        // Compatibility plumbing used by BikeModeChooserActivity. Hidden from the public home UI.
        addView(TextView(context).apply { id = R.id.tvBikeModeVersion; visibility = View.GONE }, LayoutParams(1, 1))
        addView(TextView(context).apply { id = R.id.tvBikeModeServerStatus; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeCheckUpdate; visibility = View.GONE }, LayoutParams(1, 1))
        addView(Button(context).apply { id = R.id.btnBikeModeAdmin; visibility = View.GONE }, LayoutParams(1, 1))
    }

    private fun addHotspot(
        x1: Float, y1: Float, x2: Float, y2: Float,
        longClick: (() -> Unit)? = null,
        click: () -> Unit
    ) {
        val v = View(context).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { click() }
            if (longClick != null) {
                setOnLongClickListener { longClick(); true }
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
        v.layoutParams = LayoutParams(((x2 - x1) * s).roundToInt(), ((y2 - y1) * s).roundToInt()).apply {
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

            drawStatus(canvas)
            drawBrand(canvas)
            drawHero(canvas)
            drawRoute(canvas)
            drawMenu(canvas, 58f, 670f, 585f, 821f, red, Color.WHITE, "기록측정", "지금 시작하세요", 0)
            drawMenu(canvas, 58f, 840f, 585f, 987f, blue, Color.WHITE, "관전하기", "실시간 기록을 확인하세요", 1)
            drawMenu(canvas, 58f, 1007f, 574f, 1155f, gray, black, "설정", "앱 설정을 관리하세요", 2)
            drawStopwatch(canvas)
            canvas.restore()
        }

        private fun drawStatus(c: Canvas) {
            text(c, "9:41", 70f, 62f, 39f, black, Paint.Align.LEFT, true)
            p.color = black; p.style = Paint.Style.FILL
            for (i in 0..3) c.drawRoundRect(RectF(691f + i*13f, 52f-i*7f, 701f+i*13f, 67f), 4f, 4f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(754f, 37f, 812f, 86f), 215f, 110f, false, p)
            c.drawArc(RectF(765f, 50f, 801f, 83f), 215f, 110f, false, p)
            p.style = Paint.Style.FILL; c.drawCircle(783f, 76f, 5f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f
            c.drawRoundRect(RectF(817f, 37f, 869f, 67f), 6f, 6f, p)
            p.style = Paint.Style.FILL; c.drawRoundRect(RectF(823f, 42f, 862f, 62f), 3f, 3f, p); c.drawRect(870f, 45f, 875f, 59f, p)
        }

        private fun drawBrand(c: Canvas) {
            // Stopwatch logo
            p.style = Paint.Style.STROKE; p.strokeWidth = 9f; p.color = blue
            c.drawCircle(112f, 194f, 48f, p)
            p.style = Paint.Style.FILL; c.drawRoundRect(RectF(100f, 128f, 124f, 142f), 4f, 4f, p)
            p.color = red; c.save(); c.rotate(43f, 157f, 157f); c.drawRoundRect(RectF(150f, 148f, 165f, 161f), 2f, 2f, p); c.restore()
            p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeCap = Paint.Cap.ROUND; c.drawLine(112f,194f,139f,169f,p)
            text(c, "Time", 182f, 215f, 72f, blue, Paint.Align.LEFT, true)
            text(c, "Gate", 355f, 215f, 72f, red, Paint.Align.LEFT, true)
            text(c, "생동감 있는", 185f, 262f, 27f, blue, Paint.Align.LEFT, true)
            text(c, "실시간 라이브중계", 336f, 262f, 27f, red, Paint.Align.LEFT, true)
            p.style = Paint.Style.STROKE; p.color = red; p.strokeWidth = 3f
            val under = Path().apply { moveTo(335f, 284f); cubicTo(390f, 264f, 505f, 272f, 568f, 281f) }
            c.drawPath(under, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f
            c.drawArc(RectF(570f,210f,609f,256f),-70f,140f,false,p); c.drawArc(RectF(580f,202f,623f,264f),-70f,140f,false,p)
        }

        private fun drawHero(c: Canvas) {
            text(c, "LIVE", 58f, 455f, 126f, red, Paint.Align.LEFT, true)
            text(c, "랩타이머", 56f, 625f, 112f, black, Paint.Align.LEFT, true)
        }

        private fun drawRoute(c: Canvas) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 16f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = blue
            val path = Path().apply {
                moveTo(720f, 276f)
                cubicTo(661f,327f,766f,387f,747f,482f)
                cubicTo(731f,557f,675f,576f,743f,648f)
                cubicTo(786f,696f,692f,744f,711f,813f)
            }
            c.drawPath(path,p)

            // Start pin
            p.style = Paint.Style.FILL; p.color = red
            c.drawCircle(720f,224f,32f,p)
            val pin=Path().apply{moveTo(696f,240f);lineTo(744f,240f);lineTo(720f,280f);close()};c.drawPath(pin,p)
            p.color=Color.WHITE;c.drawCircle(720f,224f,12f,p)
            text(c,"START",765f,242f,35f,black,Paint.Align.LEFT,true)

            // CP1
            p.color=blue;c.drawCircle(746f,540f,31f,p);p.color=Color.WHITE;c.drawCircle(746f,540f,22f,p);p.color=red;c.drawCircle(746f,540f,14f,p)
            text(c,"CP1",786f,553f,34f,black,Paint.Align.LEFT,true)

            // Finish marker + flag
            p.color=red;c.drawCircle(674f,900f,14f,p)
            p.color=black;c.drawRoundRect(RectF(672f,812f,680f,895f),3f,3f,p)
            val left=679f;val top=820f;val cw=18f;val ch=18f
            for(r in 0..2)for(col in 0..3){p.color=if((r+col)%2==0)black else Color.WHITE;c.drawRect(left+col*cw,top+r*ch,left+(col+1)*cw,top+(r+1)*ch,p)}
            p.style=Paint.Style.STROKE;p.color=black;p.strokeWidth=3f;c.drawRect(left,top,left+72f,top+54f,p)
            text(c,"FINISH",765f,895f,34f,black,Paint.Align.LEFT,true)
        }

        private fun drawMenu(c: Canvas,l:Float,t:Float,r:Float,b:Float,fill:Int,fg:Int,title:String,sub:String,icon:Int){
            p.style=Paint.Style.FILL;p.color=fill;c.drawRoundRect(RectF(l,t,r,b),27f,27f,p)
            val ix=143f;val iy=(t+b)/2f
            p.color=fg;p.style=Paint.Style.STROKE;p.strokeWidth=9f;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
            when(icon){
                0->{c.drawCircle(ix,iy,42f,p);p.style=Paint.Style.FILL;val q=Path().apply{moveTo(ix-12f,iy-22f);lineTo(ix+25f,iy);lineTo(ix-12f,iy+22f);close()};c.drawPath(q,p)}
                1->{c.drawRoundRect(RectF(ix-43f,iy-31f,ix+43f,iy+22f),5f,5f,p);c.drawLine(ix,iy+22f,ix,iy+43f,p);c.drawLine(ix-26f,iy+43f,ix+26f,iy+43f,p)}
                2->{c.drawCircle(ix,iy,21f,p);for(k in 0..7){val a=Math.toRadians(k*45.0);val x1=(ix+kotlin.math.cos(a).toFloat()*30f);val y1=(iy+kotlin.math.sin(a).toFloat()*30f);val x2=(ix+kotlin.math.cos(a).toFloat()*43f);val y2=(iy+kotlin.math.sin(a).toFloat()*43f);c.drawLine(x1,y1,x2,y2,p)}}
            }
            p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=if(icon==2)Color.rgb(195,205,218) else Color.argb(120,255,255,255);c.drawLine(226f,t+35f,226f,b-35f,p)
            text(c,title,250f,t+73f,43f,fg,Paint.Align.LEFT,true);text(c,sub,250f,t+119f,25f,if(icon==2)darkGray else fg,Paint.Align.LEFT,false)
            text(c,"›",538f,t+102f,66f,fg,Paint.Align.CENTER,false)
        }

        private fun drawStopwatch(c: Canvas){
            // Approved mock-up: large chunky blue digital stopwatch, cropped at right/bottom.
            c.save();c.rotate(-8f,760f,1375f)
            p.style=Paint.Style.FILL
            p.color=Color.rgb(3,77,205);c.drawRoundRect(RectF(390f,1040f,1085f,1715f),90f,90f,p)
            p.color=Color.rgb(0,55,170);c.drawRoundRect(RectF(425f,1085f,1050f,1680f),74f,74f,p)
            p.color=Color.rgb(9,92,238);c.drawRoundRect(RectF(452f,1116f,1030f,1650f),66f,66f,p)
            // red top controls
            p.color=red;c.drawRoundRect(RectF(600f,1010f,760f,1070f),28f,28f,p);c.drawRoundRect(RectF(405f,1110f,495f,1190f),22f,22f,p)
            p.color=Color.rgb(0,54,150);c.drawRoundRect(RectF(615f,1060f,748f,1080f),8f,8f,p);c.drawRoundRect(RectF(435f,1170f,492f,1190f),8f,8f,p)
            // screen
            p.color=Color.rgb(238,247,255);c.drawRoundRect(RectF(520f,1200f,1000f,1605f),58f,58f,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=Color.rgb(15,69,139);c.drawLine(565f,1480f,960f,1480f,p)
            text(c,"TIME",570f,1323f,37f,Color.rgb(12,69,139),Paint.Align.LEFT,true)
            text(c,"00:12",570f,1445f,87f,black,Paint.Align.LEFT,false)
            text(c,".34",848f,1445f,63f,black,Paint.Align.LEFT,false)
            text(c,"BEST",580f,1535f,32f,Color.rgb(12,69,139),Paint.Align.LEFT,true)
            text(c,"00:09.87",945f,1535f,30f,Color.rgb(12,69,139),Paint.Align.RIGHT,true)
            text(c,"TOTAL",580f,1580f,32f,Color.rgb(12,69,139),Paint.Align.LEFT,true)
            text(c,"01:23.56",945f,1580f,30f,Color.rgb(12,69,139),Paint.Align.RIGHT,true)
            // battery icon
            p.style=Paint.Style.STROKE;p.strokeWidth=5f;p.color=Color.rgb(12,69,139);c.drawRoundRect(RectF(895f,1240f,955f,1275f),5f,5f,p);c.drawRect(957f,1249f,964f,1266f,p)
            c.restore()
            p.style=Paint.Style.STROKE;p.strokeWidth=10f;p.color=red;p.strokeCap=Paint.Cap.ROUND;c.drawLine(579f,1005f,559f,975f,p);c.drawLine(608f,991f,604f,953f,p);c.drawLine(550f,1028f,520f,1010f,p)
        }

        private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,align:Paint.Align,bold:Boolean){
            p.style=Paint.Style.FILL;p.color=color;p.textSize=size;p.textAlign=align;p.typeface=if(bold)Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.DEFAULT;p.isSubpixelText=true;c.drawText(s,x,y,p)
        }
    }

    companion object { const val REF_W = 941f; const val REF_H = 1672f }
}
