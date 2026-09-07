package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
    private var lastServerRepairAttemptMs = 0L
    private var explicitExitHandled = false

    private data class PcHealth(
        val ok: Boolean,
        val version: String,
        val pcEntry: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR }

        sync = RiderServerSync(this)
        val home = TimeGateHomeView(this).apply {
            onTimingClick = {
                startActivity(Intent(this@BikeModeChooserActivity, RaceActivity::class.java))
            }
            onWatchClick = {
                startActivity(Intent(this@BikeModeChooserActivity, RaceBroadcastActivity::class.java))
            }
            onSettingsClick = {
                startActivity(Intent(this@BikeModeChooserActivity, SettingsActivity::class.java))
            }
            onSettingsLongClick = { showLegacyModeDialog() }
            onBrandLongClick = {
                if (sync.isAdminDeviceCached()) {
                    startActivity(Intent(this@BikeModeChooserActivity, AdminCenterActivity::class.java))
                } else {
                    showAdminPhonePairDialog()
                }
            }
        }
        setContentView(home)

        btnAdmin = findViewById(R.id.btnBikeModeAdmin)
        btnUpdate = findViewById(R.id.btnBikeModeCheckUpdate)
        tvVersion = findViewById(R.id.tvBikeModeVersion)
        tvServerStatus = findViewById(R.id.tvBikeModeServerStatus)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        tvVersion.text = "TimeGate v$version"
        btnUpdate.setOnClickListener { checkPublicUpdate() }
        btnAdmin.setOnClickListener {
            if (sync.isAdminDeviceCached()) startActivity(Intent(this, AdminCenterActivity::class.java))
            else refreshAdminVisibility()
        }

        refreshAdminVisibility()
        refreshServerHealth()
        repairPreferredPcServerIfNeeded(force = true)
        UpdateManager.resumePendingInstall(this)
        UpdateManager.maybeCheckOnLaunch(this)
    }

    private fun showLegacyModeDialog() {
        val options = arrayOf(
            "⚡ eMTB · 배터리 코파일럿",
            "🚴 ROAD · 그란폰도 페이스 코치",
            "⬆ 앱 업데이트 확인"
        )
        AlertDialog.Builder(this)
            .setTitle("기존 Ride Copilot 기능")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MainActivity::class.java))
                    1 -> startActivity(Intent(this, RoadGranfondoActivity::class.java))
                    2 -> checkPublicUpdate()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // v0.33.5: do NOT automatically revive an old ride service.
        // Explicit app/task close means the next launch starts from a clean ride state.
        explicitExitHandled = false
        UpdateManager.resumePendingInstall(this)
        refreshAdminVisibility()
        refreshServerHealth()
        repairPreferredPcServerIfNeeded()
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

    override fun onStop() {
        if (!isChangingConfigurations && isFinishing) handleExplicitExit()
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && isFinishing) handleExplicitExit()
        super.onDestroy()
    }

    private fun handleExplicitExit() {
        if (explicitExitHandled) return
        explicitExitHandled = true
        RideTaskExitPolicy.stopEverything(this)
    }

    private fun repairPreferredPcServerIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastServerRepairAttemptMs < 30_000L) return
        lastServerRepairAttemptMs = now

        Thread {
            val current = sync.serverUrl().trim().trimEnd('/')
            val currentHealth = if (current.startsWith("http")) {
                runCatching { probePcHealth(current) }.getOrNull()
            } else null
            if (currentHealth?.ok == true && currentHealth.pcEntry) return@Thread

            val candidate = runCatching { fetchPublishedPcServer() }.getOrNull().orEmpty().trim().trimEnd('/')
            if (!candidate.startsWith("http") || candidate == current) return@Thread

            val candidateHealth = runCatching { probePcHealth(candidate) }.getOrNull() ?: return@Thread
            if (!candidateHealth.ok || !candidateHealth.pcEntry) return@Thread

            val wasAdmin = sync.isAdminDeviceCached()
            sync.configure(
                url = candidate,
                deviceToken = sync.token(),
                name = sync.riderName(),
                weightKg = sync.weightKg(),
                ftpW = sync.ftpW(),
                auto = sync.autoEnabled()
            )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val v = candidateHealth.version.takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty()
                tvServerStatus.setTextColor(getColor(R.color.good))
                tvServerStatus.text = "● 새 PC 서버 자동 연결$v · 동기화 대기 ${sync.pendingCount()}건"
                Toast.makeText(this, "새 Rider Control Center PC 서버로 자동 전환했습니다.", Toast.LENGTH_SHORT).show()
            }

            if (wasAdmin) {
                sync.checkAdminStatusAsync { auth ->
                    runOnUiThread {
                        refreshAdminVisibility()
                        if (!auth.ok && !isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this,
                                "새 PC 서버에서 관리자폰 재등록이 필요할 수 있습니다. TimeGate 로고를 길게 눌러 등록할 수 있습니다.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            if (sync.autoEnabled() && sync.configured()) {
                sync.syncAllAsync {
                    runOnUiThread { refreshServerHealth() }
                }
            }
        }.start()
    }

    private fun fetchPublishedPcServer(): String? {
        val repo = BuildConfig.UPDATE_REPOSITORY.trim()
        if (!repo.contains('/')) return null
        val connection = URL("https://raw.githubusercontent.com/$repo/main/rcc-server.json")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text).optString("url", "").trim().trimEnd('/').takeIf { it.startsWith("http") }
        } finally {
            connection.disconnect()
        }
    }

    private fun probePcHealth(base: String): PcHealth {
        val connection = URL("${base.trim().trimEnd('/')}/api/health").openConnection() as HttpURLConnection
        return try {
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

    private fun refreshServerHealth() {
        val base = sync.serverUrl().trim().trimEnd('/')
        val pending = sync.pendingCount()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            tvServerStatus.text = if (pending > 0) {
                "PC 서버 미연결 · 앱 단독 사용 가능 · 동기화 대기 ${pending}건"
            } else {
                "PC 서버 미연결 · 앱 단독 사용 가능"
            }
            return
        }

        tvServerStatus.text = "PC 서버 확인 중… · 동기화 대기 ${pending}건"

        Thread {
            val result = runCatching { probePcHealth(base) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val health = result.getOrNull()
                val currentPending = sync.pendingCount()
                tvServerStatus.text = when {
                    health == null -> if (currentPending > 0) {
                        "● PC 서버 응답 없음 · 앱 단독 사용 가능 · 동기화 대기 ${currentPending}건"
                    } else {
                        "● PC 서버 응답 없음 · 앱 단독 사용 가능"
                    }
                    !health.ok -> "● PC 서버 확인 필요 · 동기화 대기 ${currentPending}건"
                    health.pcEntry -> {
                        val versionText = health.version.takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty()
                        "● PC 서버 연결$versionText · 동기화 대기 ${currentPending}건"
                    }
                    else -> {
                        val versionText = health.version.takeIf { it.isNotBlank() }?.let { " v$it" }.orEmpty()
                        "● PC 서버 연결 · 구버전$versionText · 업데이트 권장 · 대기 ${currentPending}건"
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
        // Main screen stays visually identical for normal/admin users. Admin Center is hidden behind a logo long-press.
        btnAdmin.visibility = View.GONE
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
            .setMessage("PC 관리자에서 발급한 8자리 코드는 5분 동안만 유효합니다.")
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
