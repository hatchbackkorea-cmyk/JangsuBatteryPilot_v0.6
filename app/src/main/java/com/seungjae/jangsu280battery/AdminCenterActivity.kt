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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class AdminCenterActivity : Activity() {
    private lateinit var sync: RiderServerSync
    private lateinit var tvAuth: TextView
    private lateinit var tvUpdate: TextView
    private lateinit var tvSync: TextView
    private lateinit var etServer: EditText
    private lateinit var etToken: EditText
    private lateinit var etName: EditText
    private lateinit var etWeight: EditText
    private lateinit var etFtp: EditText
    private lateinit var switchAuto: Switch
    private lateinit var switchBeta: Switch
    private lateinit var btnSyncNow: Button
    private lateinit var btnSave: Button
    private lateinit var btnCheckUpdate: Button
    private var authenticated = false
    private var activePassword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_center)
        sync = RiderServerSync(this)
        bindViews()
        populate()
        wire()
        requireAdminLogin()
    }

    private fun bindViews() {
        findViewById<Button>(R.id.btnAdminBack).setOnClickListener { finish() }
        tvAuth = findViewById(R.id.tvAdminAuthStatus)
        tvUpdate = findViewById(R.id.tvAdminUpdateStatus)
        tvSync = findViewById(R.id.tvAdminSyncStatus)
        etServer = findViewById(R.id.etAdminServerUrl)
        etToken = findViewById(R.id.etAdminDeviceToken)
        etName = findViewById(R.id.etAdminRiderName)
        etWeight = findViewById(R.id.etAdminWeight)
        etFtp = findViewById(R.id.etAdminFtp)
        switchAuto = findViewById(R.id.switchAdminAutoSync)
        switchBeta = findViewById(R.id.switchAdminBetaUpdates)
        btnSyncNow = findViewById(R.id.btnAdminSyncNow)
        btnSave = findViewById(R.id.btnAdminSaveConnection)
        btnCheckUpdate = findViewById(R.id.btnAdminCheckUpdate)
    }

    private fun populate() {
        etServer.setText(sync.serverUrl())
        etToken.setText("")
        etName.setText(sync.riderName())
        etWeight.setText(String.format(Locale.US, "%.1f", sync.weightKg()))
        etFtp.setText(String.format(Locale.US, "%.0f", sync.ftpW()))
        switchAuto.isChecked = sync.autoEnabled()
        switchBeta.isChecked = AppSettings.betaUpdates(this)
        refreshUpdate()
        refreshSync()
        setAdminUiEnabled(false)
    }

    private fun wire() {
        switchBeta.setOnCheckedChangeListener { _, checked ->
            if (!authenticated) return@setOnCheckedChangeListener
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_BETA_UPDATES, checked).apply()
            refreshUpdate()
        }
        btnCheckUpdate.setOnClickListener { checkUpdate() }
        findViewById<Button>(R.id.btnAdminMobileRelease).setOnClickListener {
            if (authenticated) startActivity(Intent(this, ReleaseUploaderActivity::class.java))
        }
        btnSave.setOnClickListener { verifyAndSaveConnection() }
        btnSyncNow.setOnClickListener { runSync() }
        findViewById<Button>(R.id.btnAdminChangePassword).setOnClickListener { changePassword() }
    }

    private fun requireAdminLogin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 8, 36, 0)
        }
        val server = EditText(this).apply {
            hint = "Rider Server 주소"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(sync.serverUrl())
        }
        val token = EditText(this).apply {
            hint = if (sync.token().isNotBlank()) "연결 토큰 · 비우면 저장된 토큰 사용" else "PC에서 발급한 연결 토큰"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val password = EditText(this).apply {
            hint = "관리자 암호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(server); box.addView(token); box.addView(password)
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔐 관리자 인증")
            .setMessage("앱 업데이트와 Rider Control Center는 관리자만 사용할 수 있습니다.")
            .setView(box)
            .setPositiveButton("확인", null)
            .setNegativeButton("취소") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = server.text.toString().trim()
                val enteredToken = token.text.toString().trim()
                val pw = password.text.toString()
                if (url.isBlank() || (enteredToken.isBlank() && sync.token().isBlank()) || pw.isBlank()) {
                    Toast.makeText(this, "서버 주소 · 연결 토큰 · 관리자 암호를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                tvAuth.text = "관리자 인증 중…"
                sync.verifyAdminAsync(pw, url, enteredToken.ifBlank { null }) { result ->
                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (!result.ok) {
                            tvAuth.text = result.message
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        } else {
                            sync.configure(url, enteredToken, sync.riderName(), sync.weightKg(), sync.ftpW(), sync.autoEnabled())
                            activePassword = pw
                            authenticated = true
                            tvAuth.text = "관리자 인증됨 · PC 관리자 암호와 연동"
                            etServer.setText(sync.serverUrl())
                            setAdminUiEnabled(true)
                            refreshSync("인증 성공")
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setAdminUiEnabled(enabled: Boolean) {
        listOf<View>(etServer, etToken, etName, etWeight, etFtp, switchAuto, switchBeta,
            btnSyncNow, btnSave, btnCheckUpdate,
            findViewById(R.id.btnAdminMobileRelease), findViewById(R.id.btnAdminChangePassword),
            findViewById(R.id.etAdminCurrentPassword), findViewById(R.id.etAdminNewPassword), findViewById(R.id.etAdminConfirmPassword)
        ).forEach { it.isEnabled = enabled }
    }

    private fun verifyAndSaveConnection() {
        val weight = etWeight.text.toString().toDoubleOrNull()
        val ftp = etFtp.text.toString().toDoubleOrNull()
        if (weight == null || ftp == null) {
            Toast.makeText(this, "체중/FTP 숫자를 확인해 주세요.", Toast.LENGTH_SHORT).show(); return
        }
        val url = etServer.text.toString().trim()
        val enteredToken = etToken.text.toString().trim()
        btnSave.isEnabled = false
        sync.verifyAdminAsync(activePassword, url, enteredToken.ifBlank { null }) { result ->
            runOnUiThread {
                btnSave.isEnabled = true
                if (!result.ok) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show(); refreshSync(result.message); return@runOnUiThread
                }
                sync.configure(url, enteredToken, etName.text.toString(), weight, ftp, switchAuto.isChecked)
                etToken.setText("")
                refreshSync("연결 저장 + 관리자 검증 완료")
                if (sync.configured()) runSync()
            }
        }
    }

    private fun runSync() {
        if (!sync.configured()) { refreshSync("서버 주소/연결 토큰을 먼저 저장하세요."); return }
        btnSyncNow.isEnabled = false
        refreshSync("서버 동기화 중…")
        sync.syncAllAsync { result -> runOnUiThread {
            btnSyncNow.isEnabled = true
            refreshSync(result.message)
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        } }
    }

    private fun refreshSync(extra: String? = null) {
        tvSync.text = buildString {
            append(sync.statusText())
            if (!extra.isNullOrBlank()) append("\n").append(extra)
        }
    }

    private fun refreshUpdate(extra: String? = null) {
        val channel = if (AppSettings.betaUpdates(this)) "테스트판 포함" else "안정판"
        tvUpdate.text = buildString {
            append("현재 v${UpdateManager.currentVersion(this@AdminCenterActivity)} · $channel")
            UpdateManager.repository().takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            if (!extra.isNullOrBlank()) append("\n").append(extra)
        }
    }

    private fun checkUpdate() {
        if (!authenticated) return
        btnCheckUpdate.isEnabled = false
        refreshUpdate("GitHub에서 최신 릴리스 확인 중…")
        UpdateManager.checkAsync(this) { result ->
            btnCheckUpdate.isEnabled = true
            result.onSuccess { info ->
                if (info == null) refreshUpdate("최신 버전입니다.")
                else { refreshUpdate("새 버전 v${info.versionName} 사용 가능"); UpdateManager.showUpdateDialog(this, info) }
            }.onFailure { refreshUpdate("업데이트 확인 실패 · ${it.message ?: "네트워크 확인"}") }
        }
    }

    private fun changePassword() {
        val current = findViewById<EditText>(R.id.etAdminCurrentPassword).text.toString().ifBlank { activePassword }
        val fresh = findViewById<EditText>(R.id.etAdminNewPassword).text.toString()
        val confirm = findViewById<EditText>(R.id.etAdminConfirmPassword).text.toString()
        if (fresh.length < 6) { Toast.makeText(this, "새 관리자 암호는 6자 이상으로 입력해 주세요.", Toast.LENGTH_LONG).show(); return }
        if (fresh != confirm) { Toast.makeText(this, "새 암호 확인이 일치하지 않습니다.", Toast.LENGTH_LONG).show(); return }
        sync.changeAdminPasswordAsync(current, fresh) { result -> runOnUiThread {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            if (result.ok) {
                activePassword = fresh
                findViewById<EditText>(R.id.etAdminCurrentPassword).setText("")
                findViewById<EditText>(R.id.etAdminNewPassword).setText("")
                findViewById<EditText>(R.id.etAdminConfirmPassword).setText("")
                tvAuth.text = "관리자 인증됨 · 암호 변경 완료 · PC 세션 로그아웃됨"
            }
        } }
    }
}
