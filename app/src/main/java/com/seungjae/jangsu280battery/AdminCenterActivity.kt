package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    private lateinit var tvWkg: TextView
    private lateinit var switchAuto: Switch
    private lateinit var switchBeta: Switch
    private lateinit var btnSyncNow: Button
    private lateinit var btnSave: Button
    private lateinit var btnCheckUpdate: Button
    private var authenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_center)
        sync = RiderServerSync(this)
        bindViews()
        populate()
        wire()
        checkAdminPhone()
    }

    override fun onResume() {
        super.onResume()
        if (authenticated) {
            sync.checkAdminStatusAsync { result ->
                runOnUiThread {
                    if (!result.ok && !sync.isAdminDeviceCached()) {
                        Toast.makeText(this, "관리자폰 권한이 해제되었습니다.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
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
        tvWkg = findViewById(R.id.tvAdminWkg)
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
        refreshWkg()
        refreshUpdate()
        refreshSync()
        setAdminUiEnabled(false)
        tvAuth.text = "관리자폰 확인 중…"
    }

    private fun wire() {
        switchBeta.setOnCheckedChangeListener { _, checked ->
            if (!authenticated) return@setOnCheckedChangeListener
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_BETA_UPDATES, checked).apply()
            refreshUpdate()
        }
        btnCheckUpdate.setOnClickListener { checkUpdate() }
        val metricWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshWkg()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }
        etWeight.addTextChangedListener(metricWatcher)
        etFtp.addTextChangedListener(metricWatcher)
        findViewById<Button>(R.id.btnAdminMobileRelease).setOnClickListener {
            if (authenticated) startActivity(Intent(this, ReleaseUploaderActivity::class.java))
        }
        btnSave.setOnClickListener { saveAdminSettings() }
        btnSyncNow.setOnClickListener { runSync() }
    }

    private fun checkAdminPhone() {
        if (!sync.isAdminDeviceCached() || !sync.configured()) {
            Toast.makeText(this, "이 휴대폰은 관리자폰으로 등록되어 있지 않습니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        sync.checkAdminStatusAsync { result ->
            runOnUiThread {
                if (result.ok && sync.isAdminDeviceCached()) {
                    authenticated = true
                    tvAuth.text = "관리자폰 인증됨 · 비밀번호 입력 없음"
                    etServer.setText(sync.serverUrl())
                    setAdminUiEnabled(true)
                    refreshSync("관리자폰 연결 정상")
                } else if (!sync.isAdminDeviceCached()) {
                    Toast.makeText(this, "관리자폰 권한이 없거나 해제되었습니다.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    // Offline: cached role can keep the local update menu visible; network actions will fail safely.
                    authenticated = true
                    tvAuth.text = "관리자폰 · 서버 상태 확인 보류"
                    setAdminUiEnabled(true)
                    refreshSync(result.message)
                }
            }
        }
    }

    private fun setAdminUiEnabled(enabled: Boolean) {
        listOf<View>(etName, etWeight, etFtp, switchAuto, switchBeta,
            btnSyncNow, btnSave, btnCheckUpdate, findViewById(R.id.btnAdminMobileRelease)
        ).forEach { it.isEnabled = enabled }
        // Server address/token are assigned only by the hidden one-time admin-phone pairing flow.
        etServer.isEnabled = false
        etToken.visibility = View.GONE
    }

    private fun refreshWkg() {
        val w = etWeight.text.toString().toDoubleOrNull()
        val f = etFtp.text.toString().toDoubleOrNull()
        tvWkg.text = if (w != null && w > 0.0 && f != null) {
            "W/kg ${String.format(Locale.US, "%.2f", f / w)} · 기준 ${sync.profileSource()}"
        } else "W/kg -"
    }

    private fun saveAdminSettings() {
        if (!authenticated) return
        val weight = etWeight.text.toString().toDoubleOrNull()
        val ftp = etFtp.text.toString().toDoubleOrNull()
        if (weight == null || ftp == null) {
            Toast.makeText(this, "체중/FTP 숫자를 확인해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        sync.configure(sync.serverUrl(), "", etName.text.toString(), weight, ftp, switchAuto.isChecked)
        refreshSync("설정 저장 완료")
        Toast.makeText(this, "관리자폰 설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
        if (sync.configured()) runSync()
    }

    private fun runSync() {
        if (!sync.configured()) {
            refreshSync("관리자폰 등록이 필요합니다.")
            return
        }
        btnSyncNow.isEnabled = false
        refreshSync("서버 동기화 중…")
        sync.syncAllAsync { result ->
            runOnUiThread {
                btnSyncNow.isEnabled = true
                refreshSync(result.message)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
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
            runOnUiThread {
                btnCheckUpdate.isEnabled = true
                result.onSuccess { info ->
                    if (info == null) refreshUpdate("최신 버전입니다.")
                    else {
                        refreshUpdate("새 버전 v${info.versionName} 사용 가능")
                        UpdateManager.showUpdateDialog(this, info)
                    }
                }.onFailure { refreshUpdate("업데이트 확인 실패 · ${it.message ?: "네트워크 확인"}") }
            }
        }
    }
}
