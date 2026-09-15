package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Receives a timing-role or broadcast-camera QR, registers this phone, then opens Camera Gate. */
class TimingOperatorEnrollmentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        registerFromIntent()
    }

    private fun registerFromIntent() {
        val candidate = TimingOperatorStore.parse(intent?.data)
        if (candidate == null) {
            showInvalid("QR이 만료되었거나 올바른 TimeGate 운영 QR이 아닙니다.")
            return
        }
        val server = candidate.serverUrl.ifBlank { runCatching { RaceServerClient(this).baseUrl() }.getOrDefault("") }
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            showInvalid("QR에 서버 주소가 없고 앱의 RACE 서버도 연결되어 있지 않습니다.")
            return
        }
        showRegistering(candidate)
        Thread {
            val result = runCatching {
                val claimed = TimingDeviceClient.claim(this, server, candidate.eventCode, candidate.role, candidate.token)
                TimingOperatorStore.Assignment(
                    eventCode = candidate.eventCode,
                    role = candidate.role,
                    token = claimed.leaseToken,
                    expiresAtMs = claimed.leaseExpiresAtMs,
                    serverUrl = server
                )
            }
            runOnUiThread {
                result.onSuccess { assignment ->
                    runCatching { TimingOperatorStore.save(this, assignment) }
                        .onSuccess { showRegistered(assignment) }
                        .onFailure { showInvalid("운영권한 저장 실패 · ${it.message}") }
                }.onFailure { showInvalid("기기 등록 실패 · ${it.message ?: "서버 연결 확인"}") }
            }
        }.start()
    }

    private fun baseRoot(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
            setBackgroundColor(Color.rgb(14, 18, 24))
        }
        scroll.addView(root)
        return scroll to root
    }

    private fun showRegistering(a: TimingOperatorStore.Assignment) {
        val broadcast = TimingOperatorStore.isBroadcastRole(a.role)
        val (scroll, root) = baseRoot()
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(TextView(this).apply {
            text = if (broadcast) "${a.role} 중계카메라 등록 중…" else "${a.role} 계측폰 등록 중…"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = if (broadcast) {
                "${a.eventCode} · 서버에서 이 휴대폰을 중계 전용 영상소스로 등록하고 있습니다."
            } else {
                "${a.eventCode} · 서버에서 이 휴대폰을 계측기 목록에 추가하고 있습니다."
            }
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        setContentView(scroll)
    }

    private fun showRegistered(assignment: TimingOperatorStore.Assignment) {
        val broadcast = TimingOperatorStore.isBroadcastRole(assignment.role)
        val (scroll, root) = baseRoot()
        root.addView(TextView(this).apply {
            text = if (broadcast) "TIMEGATE BROADCAST CAMERA" else "TIMEGATE OFFICIAL TIMING"
            textSize = 15f
            setTextColor(Color.rgb(120, 190, 255))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = if (broadcast) "${assignment.role} 중계카메라 등록 완료" else "${assignment.role} 계측기 등록 완료"
            textSize = 29f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(22))
        })

        val expires = SimpleDateFormat("MM월 dd일 HH:mm", Locale.KOREA).format(Date(assignment.expiresAtMs))
        root.addView(info("대회", assignment.eventCode))
        root.addView(info(if (broadcast) "영상소스" else "역할", assignment.role))
        root.addView(info("기기", TimingOperatorStore.deviceLabel(this)))
        root.addView(info("권한 만료", expires))

        root.addView(TextView(this).apply {
            text = if (broadcast) {
                "이 휴대폰은 계측 기록을 만들지 않는 중계 전용 카메라입니다. 영상 품질과 P2P/폴백 송출 방식은 START·CP·FINISH 계측폰과 동일하며, 등록 후 12시간 동안 앱을 다시 실행해도 중계카메라 메뉴가 유지됩니다."
            } else {
                "같은 역할에 여러 휴대폰을 등록할 수 있습니다. 등록 후 12시간 동안 앱을 다시 실행해도 계측 메뉴가 유지되며, 시간이 지나면 권한과 메뉴가 자동으로 사라집니다. 관리자 운영툴의 ‘확인’을 누르면 이 화면에 5초 확인 팝업이 표시됩니다."
            }
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(24), 0, dp(22))
        })

        root.addView(Button(this).apply {
            text = if (broadcast) "${assignment.role} 중계 카메라 시작" else "${assignment.role} 계측 시작"
            textSize = 18f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                startActivity(Intent(this@TimingOperatorEnrollmentActivity, CameraGateHighSpeedActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(62)))

        root.addView(Button(this).apply {
            text = "이 폰의 로컬 등록 해제"
            isAllCaps = false
            setOnClickListener {
                TimingOperatorStore.clear(this@TimingOperatorEnrollmentActivity)
                Toast.makeText(this@TimingOperatorEnrollmentActivity, "이 폰의 운영 등록을 해제했습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        setContentView(scroll)
    }

    private fun showInvalid(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Color.rgb(14, 18, 24))
        }
        root.addView(TextView(this).apply {
            text = "TimeGate 운영 QR을 확인해 주세요"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(24))
        })
        root.addView(Button(this).apply {
            text = "닫기"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun info(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(this@TimingOperatorEnrollmentActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Color.GRAY)
        }, LinearLayout.LayoutParams(dp(100), -2))
        addView(TextView(this@TimingOperatorEnrollmentActivity).apply {
            text = value
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
