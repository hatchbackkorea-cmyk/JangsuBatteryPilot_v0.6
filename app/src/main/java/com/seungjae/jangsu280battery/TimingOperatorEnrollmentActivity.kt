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

/** Receives a short-lived operator QR deep link and opens the non-exported Camera Gate screen. */
class TimingOperatorEnrollmentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val assignment = TimingOperatorStore.parse(intent?.data)
        if (assignment == null) {
            showInvalid()
            return
        }

        runCatching { TimingOperatorStore.save(this, assignment) }
            .onFailure {
                Toast.makeText(this, "계측기 등록 실패 · ${it.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
            setBackgroundColor(Color.rgb(14, 18, 24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "TIMEGATE OFFICIAL TIMING"
            textSize = 15f
            setTextColor(Color.rgb(120, 190, 255))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "${assignment.role} 계측기 등록"
            textSize = 31f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(22))
        })

        val expires = SimpleDateFormat("MM월 dd일 HH:mm", Locale.KOREA).format(Date(assignment.expiresAtMs))
        root.addView(info("대회", assignment.eventCode))
        root.addView(info("역할", assignment.role))
        root.addView(info("권한 만료", expires))

        root.addView(TextView(this).apply {
            text = "이 휴대폰은 QR에 지정된 역할로만 현장 계측에 사용됩니다. 역할 변경 메뉴는 운영 모드에서 잠깁니다."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(24), 0, dp(22))
        })

        root.addView(Button(this).apply {
            text = "${assignment.role} 계측 시작"
            textSize = 18f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                startActivity(Intent(this@TimingOperatorEnrollmentActivity, CameraGateHighSpeedActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(62)))

        root.addView(Button(this).apply {
            text = "등록 해제"
            isAllCaps = false
            setOnClickListener {
                TimingOperatorStore.clear(this@TimingOperatorEnrollmentActivity)
                Toast.makeText(this@TimingOperatorEnrollmentActivity, "계측기 등록을 해제했습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        setContentView(scroll)
    }

    private fun showInvalid() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Color.rgb(14, 18, 24))
        }
        root.addView(TextView(this).apply {
            text = "계측기 QR을 확인해 주세요"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "QR이 만료되었거나 올바른 TimeGate 운영자 QR이 아닙니다. 관리자폰에서 새 QR을 발급해 주세요."
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
