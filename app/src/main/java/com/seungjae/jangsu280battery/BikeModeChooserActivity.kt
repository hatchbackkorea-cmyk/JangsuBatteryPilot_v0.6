package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BikeModeChooserActivity : Activity() {
    private lateinit var sync: RiderServerSync
    private lateinit var btnAdmin: Button
    private lateinit var btnUpdate: Button
    private lateinit var tvVersion: TextView
    private lateinit var tvServerStatus: TextView

    private data class PcHealth(
        val ok: Boolean,
        val version: String,
        val pcEntry: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bike_mode_chooser)
        sync = RiderServerSync(this)
        btnAdmin = findViewById(R.id.btnBikeModeAdmin)
        btnUpdate = findViewById(R.id.btnBikeModeCheckUpdate)
        tvVersion = findViewById(R.id.tvBikeModeVersion)
        tvServerStatus = findViewById(R.id.tvBikeModeServerStatus)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        tvVersion.text = "Ride Copilot v$version"
        findViewById<Button>(R.id.btnBikeModeEmtb).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnBikeModeRoad).setOnClickListener {
            startActivity(Intent(this, RoadGranfondoActivity::class.java))
        }
        btnUpdate.setOnClickListener { checkPublicUpdate() }
        btnAdmin.setOnClickListener {
            if (sync.isAdminDeviceCached()) startActivity(Intent(this, AdminCenterActivity::class.java))
            else refreshAdminVisibility()
        }

        // Hidden bootstrap: non-admin phones never show an admin menu.
        tvVersion.setOnLongClickListener {
            showAdminPhonePairDialog()
            true
        }
        refreshAdminVisibility()
        refreshServerHealth()
        UpdateManager.resumePendingInstall(this)
        // 일반 사용자도 앱 실행 시 하루 1회 안정판 업데이트를 자동 확인한다.
        UpdateManager.maybeCheckOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.resumePendingInstall(this)
        refreshAdminVisibility()
        refreshServerHealth()
        if (sync.configured()) {
            sync.checkAdminStatusAsync {
                runOnUiThread { refreshAdminVisibility() }
            }
        }
        if (sync.autoEnabled() && sync.configured()) {
            sync.syncAllAsync {
                runOnUiThread { refreshServerHealth() }
            }
        }
    }

    private fun refreshServerHealth() {
        val base = sync.serverUrl().trim().trimEnd('/')
        val pending = sync.pendingCount()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            tvServerStatus.setTextColor(getColor(R.color.text_secondary))
            tvServerStatus.text = if (pending > 0) {
                "PC 서버 미연결 · 앱 단독 사용 가능 · 동기화 대기 ${pending}건"
            } else {
                "PC 서버 미연결 · 앱 단독 사용 가능"
            }
            return
        }

        tvServerStatus.setTextColor(getColor(R.color.text_secondary))
        tvServerStatus.text = "PC 서버 확인 중… · 동기화 대기 ${pending}건"

        Thread {
            val result = runCatching {
                val connection = URL("$base/api/health").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("Accept", "application/json")
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) error("HTTP $code")
                    val json = JSONObject(text)
                    PcHealth(
                        ok = json.optBoolean("ok", false),
                        version = json.optString("version", "").trim(),
                        pcEntry = json.optBoolean("pc_entry", false)
                    )
                } finally {
                    connection.disconnect()
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val health = result.getOrNull()
                val currentPending = sync.pendingCount()
                when {
                    health == null -> {
                        tvServerStatus.setTextColor(getColor(R.color.warn))
                        tvServerStatus.text = if (currentPending > 0) {
                            "● PC 서버 응답 없음 · 앱 단독 사용 가능 · 동기화 대기 ${currentPending}건"
                        } else {
                            "● PC 서버 응답 없음 · 앱 단독 사용 가능"
                        }
                    }
                    !health.ok -> {
                        tvServerStatus.setTextColor(getColor(R.color.warn))
                        tvServerStatus.text = "● PC 서버 확인 필요 · 동기화 대기 ${currentPending}건"
                    }
                    health.pcEntry -> {
                        tvServerStatus.setTextColor(getColor(R.color.good))
                        val versionText = health.version.takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty()
                        tvServerStatus.text = "● PC 서버 연결$versionText · 동기화 대기 ${currentPending}건"
                    }
                    else -> {
                        tvServerStatus.setTextColor(getColor(R.color.warn))
                        val versionText = health.version.takeIf { it.isNotBlank() }?.let { " v$it" }.orEmpty()
                        tvServerStatus.text = "● PC 서버 연결 · 구버전$versionText · 업데이트 권장 · 대기 ${currentPending}건"
                    }
                }
            }
        }.start()
    }

    private fun checkPublicUpdate() {
        btnUpdate.isEnabled = false
        btnUpdate.text = "업데이트 확인 중…"
        UpdateManager.checkAsync(this, UpdateChannel.STABLE) { result ->
            btnUpdate.isEnabled = true
            btnUpdate.text = "⬆ 앱 업데이트 확인"
            result.onSuccess { info ->
                if (info == null) {
                    Toast.makeText(this, "현재 v${UpdateManager.currentVersion(this)} · 최신 안정판입니다.", Toast.LENGTH_LONG).show()
                } else {
                    UpdateManager.showUpdateDialog(this, info)
                }
            }.onFailure {
                Toast.makeText(this, "업데이트 확인 실패: ${it.message ?: "네트워크를 확인하세요."}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshAdminVisibility() {
        btnAdmin.visibility = if (sync.isAdminDeviceCached()) View.VISIBLE else View.GONE
    }

    private fun showAdminPhonePairDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 8, 36, 0)
        }
        val server = EditText(this).apply {
            hint = "Rider Control Center HTTPS 주소"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(sync.serverUrl())
        }
        val code = EditText(this).apply {
            hint = "PC에서 발급한 8자리 관리자폰 등록 코드"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(server)
        box.addView(code)
        val dialog = AlertDialog.Builder(this)
            .setTitle("관리자폰 등록")
            .setMessage("이 화면은 일반 사용자에게 표시되지 않는 숨은 등록 화면입니다. PC 관리자에서 발급한 8자리 코드는 5분 동안만 유효합니다.")
            .setView(box)
            .setPositiveButton("등록", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                ok.isEnabled = false
                sync.pairAdminPhoneAsync(server.text.toString(), code.text.toString()) { result ->
                    runOnUiThread {
                        ok.isEnabled = true
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        if (result.ok) {
                            refreshAdminVisibility()
                            refreshServerHealth()
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
