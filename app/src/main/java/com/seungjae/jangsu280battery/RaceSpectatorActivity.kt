package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Public spectator flow. No rider registration or race participation is required. */
class RaceSpectatorActivity : Activity() {
    private val blue = Color.rgb(12, 91, 235)
    private val red = Color.rgb(255, 18, 56)
    private val black = Color.rgb(8, 10, 13)
    private val secondary = Color.rgb(94, 105, 120)
    private val panel = Color.rgb(247, 249, 252)
    private val line = Color.rgb(215, 223, 234)

    private lateinit var client: RaceServerClient
    private lateinit var status: TextView
    private lateinit var eventsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = RaceServerClient(this)
        buildUi()
        loadEvents()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(16), 0); setBackgroundColor(Color.WHITE)
        }
        top.addView(Button(this).apply {
            text = "‹"; textSize = 28f; isAllCaps = false; setTextColor(blue); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(58), dp(60)))
        top.addView(TextView(this).apply {
            text = "관전하기"; textSize = 25f; setTextColor(black); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(60), 1f))
        top.addView(TextView(this).apply { text = "LIVE"; textSize = 14f; setTextColor(red); setTypeface(typeface, Typeface.BOLD) })
        root.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.WHITE) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(28)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(TextView(this).apply {
            text = "참가 등록 없이 현재 개설된 대회를 선택해 실시간 중계를 볼 수 있습니다.\n앱 v${UpdateManager.currentVersion(this@RaceSpectatorActivity)}"
            textSize = 13f; setTextColor(secondary)
        })
        body.addView(Button(this).apply {
            text = "새로고침"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(blue, blue); setOnClickListener { loadEvents() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })

        status = TextView(this).apply {
            text = "대회 목록을 불러오는 중…"
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(10), 0, dp(8))
            setTextIsSelectable(true)
        }
        body.addView(status)
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(eventsBox, LinearLayout.LayoutParams(-1, -2))
    }

    private fun loadEvents() {
        status.setTextColor(secondary)
        status.text = "대회 목록을 불러오는 중…\n서버: ${client.baseUrl()}"
        eventsBox.removeAllViews()
        Thread {
            val result = runCatching { client.listEvents() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { events ->
                    runCatching {
                        if (events.isEmpty()) {
                            status.text = "현재 관전 가능한 대회가 없습니다.\n서버: ${client.baseUrl()}"
                        } else {
                            status.setTextColor(secondary)
                            status.text = "관전 가능한 대회 ${events.size}개 · 서버 연결 정상\n서버: ${client.baseUrl()}"
                            events.forEach(::addEventCard)
                        }
                    }.onFailure { renderError("목록 화면 구성 실패", it) }
                }.onFailure { e -> renderError("대회 목록을 불러오지 못했습니다.", e) }
            }
        }.start()
    }

    private fun renderError(title: String, e: Throwable) {
        status.setTextColor(red)
        status.text = buildString {
            append(title).append('\n')
            append(e.javaClass.simpleName).append(" · ").append(e.message ?: "서버 연결을 확인하세요.").append('\n')
            append("서버: ").append(client.baseUrl()).append('\n')
            append("후보: ").append(client.debugCandidates()).append('\n')
            append("앱: v").append(UpdateManager.currentVersion(this@RaceSpectatorActivity))
        }
    }

    private fun addEventCard(item: RaceServerClient.EventListItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = rounded(panel, line)
        }
        card.addView(TextView(this).apply { text = "${item.config.name} · ${item.config.eventCode}"; textSize = 18f; setTextColor(black); setTypeface(typeface, Typeface.BOLD) })
        card.addView(TextView(this).apply {
            text = "${item.config.courseName} · ${"%.2f".format(item.config.distanceM / 1000.0)} km · 참가 ${item.participants}명 · ${phaseLabel(item.phase)}"
            textSize = 12f; setTextColor(secondary); setPadding(0, dp(4), 0, dp(8))
        })
        card.addView(Button(this).apply {
            text = "실시간 관전"; isAllCaps = false; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); background = rounded(red, red)
            setOnClickListener {
                startActivity(Intent(this@RaceSpectatorActivity, RaceBroadcastActivity::class.java).apply {
                    putExtra(RaceBroadcastActivity.EXTRA_EVENT_CODE, item.config.eventCode)
                    putExtra(RaceBroadcastActivity.EXTRA_SERVER_URL, client.baseUrl())
                })
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        eventsBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
    private fun phaseLabel(phase: String): String = when (phase.uppercase()) { "WAITING" -> "대기중"; "PRACTICE" -> "연습주행"; "OFFICIAL" -> "정식계측"; "ENDED" -> "종료"; else -> phase }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
