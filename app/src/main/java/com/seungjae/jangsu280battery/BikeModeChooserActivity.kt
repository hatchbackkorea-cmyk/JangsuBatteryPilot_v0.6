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

class BikeModeChooserActivity : Activity() {
    private lateinit var sync: RiderServerSync
    private lateinit var btnAdmin: Button
    private lateinit var tvVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bike_mode_chooser)
        sync = RiderServerSync(this)
        btnAdmin = findViewById(R.id.btnBikeModeAdmin)
        tvVersion = findViewById(R.id.tvBikeModeVersion)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        tvVersion.text = "Ride Copilot v$version"
        findViewById<Button>(R.id.btnBikeModeEmtb).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnBikeModeRoad).setOnClickListener {
            startActivity(Intent(this, RoadGranfondoActivity::class.java))
        }
        btnAdmin.setOnClickListener {
            if (sync.isAdminDeviceCached()) startActivity(Intent(this, AdminCenterActivity::class.java))
            else refreshAdminVisibility()
        }

        // Hidden bootstrap: non-admin phones never show an admin menu.
        // The owner can long-press the version text and enter the 8-digit code issued from the PC admin page.
        tvVersion.setOnLongClickListener {
            showAdminPhonePairDialog()
            true
        }
        refreshAdminVisibility()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminVisibility()
        if (sync.configured()) {
            sync.checkAdminStatusAsync {
                runOnUiThread { refreshAdminVisibility() }
            }
        }
        if (sync.autoEnabled() && sync.configured()) sync.syncAllAsync()
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
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
