package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.UUID

/** Admin-only event timing-device assignment screen. */
class TimingDeviceManagerActivity : Activity() {
    private lateinit var eventInput: EditText
    private lateinit var selectedText: TextView
    private lateinit var qrView: ImageView
    private lateinit var linkText: TextView
    private var currentLink: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RiderServerSync(this).isAdminDeviceCached()) {
            Toast.makeText(this, "관리자폰에서만 계측기를 배정할 수 있습니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(28))
            setBackgroundColor(Color.rgb(244, 246, 249))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "공식 계측기 관리"
            textSize = 27f
            setTextColor(Color.rgb(20, 30, 42))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "START · CP · FINISH 휴대폰을 QR로 배정합니다. 일반 사용자 화면에는 이 메뉴가 표시되지 않습니다."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(7), 0, dp(18))
        })

        root.addView(TextView(this).apply {
            text = "대회 코드"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, Typeface.BOLD)
        })
        val currentEvent = runCatching { RaceDataStore(this).lastJoined()?.config?.eventCode.orEmpty() }.getOrDefault("")
        eventInput = EditText(this).apply {
            hint = "예: 8OQY6E"
            setText(currentEvent)
            textSize = 18f
            isSingleLine = true
        }
        root.addView(eventInput, LinearLayout.LayoutParams(-1, dp(58)))

        root.addView(TextView(this).apply {
            text = "계측 위치 선택"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
        })

        val roles = TimingOperatorStore.ROLES
        var index = 0
        while (index < roles.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(2) { column ->
                val role = roles.getOrNull(index + column) ?: return@repeat
                row.addView(Button(this).apply {
                    text = role
                    textSize = 16f
                    isAllCaps = false
                    setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener { issue(role) }
                }, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    if (column == 1) leftMargin = dp(8)
                    bottomMargin = dp(8)
                })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
            index += 2
        }

        selectedText = TextView(this).apply {
            text = "역할을 선택하면 12시간 유효한 등록 QR이 생성됩니다."
            textSize = 15f
            setTextColor(Color.rgb(50, 65, 80))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(10))
        }
        root.addView(selectedText)

        qrView = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(qrView, LinearLayout.LayoutParams(-1, dp(330)).apply {
            topMargin = dp(4)
        })

        linkText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(linkText)

        root.addView(Button(this).apply {
            text = "등록 링크 복사"
            isAllCaps = false
            setOnClickListener { copyCurrentLink() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4) })

        root.addView(Button(this).apply {
            text = "이 관리자폰으로 직접 계측 테스트"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@TimingDeviceManagerActivity, CameraGateHighSpeedActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "행사 운영: 각 스태프 휴대폰으로 해당 QR을 스캔 → 역할 확인 → 계측 시작. QR을 다시 발급하면 새 링크를 사용하면 됩니다."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(16), 0, dp(8))
        })

        setContentView(scroll)
    }

    private fun issue(role: String) {
        val event = eventInput.text?.toString().orEmpty().trim().uppercase(Locale.US)
        if (event.isBlank()) {
            Toast.makeText(this, "먼저 대회 코드를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            eventInput.requestFocus()
            return
        }

        val now = System.currentTimeMillis()
        val assignment = TimingOperatorStore.Assignment(
            eventCode = event,
            role = role,
            token = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", ""),
            expiresAtMs = now + 12L * 60L * 60L * 1000L
        )
        val link = TimingOperatorStore.buildLink(assignment)
        currentLink = link
        val expires = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(assignment.expiresAtMs))
        selectedText.text = "$event · $role · $expires까지 유효"
        linkText.text = link
        qrView.setImageBitmap(makeQr(link, 900))
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 1)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        var y = 0
        while (y < size) {
            var x = 0
            while (x < size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                x++
            }
            y++
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun copyCurrentLink() {
        if (currentLink.isBlank()) {
            Toast.makeText(this, "먼저 계측 위치 QR을 발급해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TimeGate timing operator", currentLink))
        Toast.makeText(this, "등록 링크를 복사했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
