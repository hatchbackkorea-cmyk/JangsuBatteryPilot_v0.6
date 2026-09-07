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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 13))
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(18, 25, 36))
        }
        top.addView(Button(this).apply {
            text = "‹"
            textSize = 28f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(58), dp(60)))
        top.addView(TextView(this).apply {
            text = "관전하기"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(60), 1f))
        root.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(TextView(this).apply {
            text = "참가 등록 없이 현재 개설된 대회를 선택해 실시간 중계를 볼 수 있습니다."
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })

        body.addView(Button(this).apply {
            text = "새로고침"
            isAllCaps = false
            setOnClickListener { loadEvents() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })

        status = TextView(this).apply {
            text = "대회 목록을 불러오는 중…"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(8))
        }
        body.addView(status)

        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(eventsBox, LinearLayout.LayoutParams(-1, -2))
    }

    private fun loadEvents() {
        status.setTextColor(Color.LTGRAY)
        status.text = "대회 목록을 불러오는 중…"
        eventsBox.removeAllViews()
        Thread {
            val result = runCatching { client.listEvents() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { events ->
                    if (events.isEmpty()) {
                        status.text = "현재 관전 가능한 대회가 없습니다."
                    } else {
                        status.text = "관전 가능한 대회 ${events.size}개 · 서버 ${client.baseUrl()}"
                        events.forEach(::addEventCard)
                    }
                }.onFailure { e ->
                    status.setTextColor(Color.rgb(255, 140, 70))
                    status.text = "대회 목록을 불러오지 못했습니다.\n${e.message ?: "서버 연결을 확인하세요."}\n서버: ${client.baseUrl()}"
                }
            }
        }.start()
    }

    private fun addEventCard(item: RaceServerClient.EventListItem) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(24, 30, 40))
                setStroke(dp(1), Color.rgb(65, 82, 105))
            }
        }
        card.addView(TextView(this).apply {
            text = "${item.config.name} · ${item.config.eventCode}"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "${item.config.courseName} · ${"%.2f".format(item.config.distanceM / 1000.0)} km · 참가 ${item.participants}명 · ${phaseLabel(item.phase)}"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(8))
        })
        card.addView(Button(this).apply {
            text = "실시간 관전"
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                startActivity(Intent(this@RaceSpectatorActivity, RaceBroadcastActivity::class.java).apply {
                    putExtra(RaceBroadcastActivity.EXTRA_EVENT_CODE, item.config.eventCode)
                    putExtra(RaceBroadcastActivity.EXTRA_SERVER_URL, client.baseUrl())
                })
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        eventsBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun phaseLabel(phase: String): String = when (phase.uppercase()) {
        "WAITING" -> "대기중"
        "PRACTICE" -> "연습주행"
        "OFFICIAL" -> "정식계측"
        "ENDED" -> "종료"
        else -> phase
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
