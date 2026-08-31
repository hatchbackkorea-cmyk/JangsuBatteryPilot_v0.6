package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryForensicsActivity : Activity() {
    companion object { private const val REQ_BLE = 296 }

    private lateinit var store: BatteryForensicsStore
    private lateinit var tvLive: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvUnknown: TextView
    private lateinit var tvRaw: TextView
    private var client: AvinoxBleSocClient? = null
    private var rawCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_forensics)
        store = BatteryForensicsStore(this)
        tvLive = findViewById(R.id.tvBatteryProbeLive)
        tvHistory = findViewById(R.id.tvBatteryProbeHistory)
        tvSession = findViewById(R.id.tvBatteryProbeSession)
        tvUnknown = findViewById(R.id.tvBatteryProbeUnknown)
        tvRaw = findViewById(R.id.tvBatteryProbeRaw)

        findViewById<Button>(R.id.btnBatteryProbeStartSession).setOnClickListener {
            store.startOrResumeSession(); refreshAll(); Toast.makeText(this, "테스트 세션을 시작/이어갑니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnBatteryProbeEndSession).setOnClickListener {
            store.endSession(); refreshAll(); Toast.makeText(this, "세션을 닫았습니다. 다음에 새 세션으로 시작할 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnBatteryProbeAnalyzeProto).setOnClickListener { refreshAll() }
        findViewById<Button>(R.id.btnBatteryProbeFullGatt).setOnClickListener { startActivity(Intent(this, BleDiagnosticActivity::class.java)) }
        findViewById<Button>(R.id.btnBatteryProbeExport).setOnClickListener { shareExport() }

        bindCapture(R.id.btnBatteryCaptureIdle, "대기/전원ON")
        bindCapture(R.id.btnBatteryCaptureRide, "주행/부하")
        bindCapture(R.id.btnBatteryCaptureChargePlug, "충전기 연결 직후")
        bindCapture(R.id.btnBatteryCaptureCharging, "충전 중")
        bindCapture(R.id.btnBatteryCaptureLimit, "충전 제한 변경")
        bindCapture(R.id.btnBatteryCaptureFull, "충전 종료/목표 도달")
        bindCapture(R.id.btnBatteryCaptureLeftSwitch, "왼쪽 스위치")
        bindCapture(R.id.btnBatteryCaptureRightSwitch, "오른쪽 스위치")
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        if (RideLogManager(this).isActive()) {
            tvLive.append("\n주행 서비스가 BLE를 수집 중이라 별도 연결은 하지 않습니다.")
        } else startProbeIfPossible()
    }

    override fun onPause() {
        client?.stop(); client = null
        super.onPause()
    }

    private fun bindCapture(id: Int, label: String) {
        findViewById<Button>(id).setOnClickListener {
            store.capture(label)
            refreshAll()
            Toast.makeText(this, "$label 상태 저장", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProbeIfPossible() {
        if (!hasBlePermissions()) {
            requestBlePermissions(); return
        }
        if (client != null) return
        client = AvinoxBleSocClient(this, object : AvinoxBleSocClient.Listener {
            override fun onBleState(state: String, address: String?) { runOnUiThread { refreshLive() } }
            override fun onSoc(soc: Int, timestampMs: Long, address: String?) {
                AvinoxBleStateStore(this@BatteryForensicsActivity).setSoc(soc, "BLE 정밀분석 · 연결됨", address, timestampMs)
                runOnUiThread { refreshLive() }
            }
            override fun onRawNotification(timestampMs: Long, bytes: ByteArray, address: String?) {
                rawCount += 1
                runOnUiThread { refreshLive() }
            }
        }).also { it.start() }
    }

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestBlePermissions() {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        requestPermissions(p, REQ_BLE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startProbeIfPossible()
    }

    private fun refreshAll() {
        refreshLive()
        val s = store.status()
        tvSession.text = if (s.open) {
            "열린 세션 ${s.id} · 저장 ${s.snapshots}회\n" + if (s.labels.isEmpty()) "아직 상태 캡처 없음" else s.labels.joinToString(" · ")
        } else {
            val last = store.latestSessionId()
            if (last != null) "현재 열린 세션 없음 · 최근 $last\n시간 날 때 새 세션을 시작해도 되고, 세션 종료 전까지는 여러 번 나눠 이어갈 수 있습니다." else "테스트 세션 없음"
        }
        tvUnknown.text = "[미해독 · 실제 값 확인 전에는 숫자를 만들지 않음]\nBMS 실제 사이클 · SOH · 팩 전압/전류 · 셀별 전압/편차 · 밸런싱 · 충전 제한/보호상태 · 배터리 FW/시리얼 · 좌/우 스위치 정확한 배터리 %"
        tvHistory.text = "저장된 Avinox 원본 분석 중…"
        Thread {
            val h = store.analyzeExistingProto()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvHistory.text = buildString {
                    append("저장된 Avinox 원본 ${h.protoFiles}개 · 분석 ${h.parsedFiles}개")
                    if (h.failedFiles > 0) append(" · 실패 ${h.failedFiles}")
                    append("\n샘플 ${h.samples} · 누적거리 ${one(h.distanceKm)} km")
                    append("\n관측 SOC 소비 ${one(h.consumedPct)}% · 충전 증가 ${one(h.chargedPct)}%")
                    append("\n관측 방전 환산 ${two(h.observedEquivalentCycles)}회 · 약 ${h.observedDischargeWh.toInt()} Wh")
                    append("\nSOC 범위 ${h.minSoc?.let(::one) ?: "—"}% ~ ${h.maxSoc?.let(::one) ?: "—"}%")
                    append("\n온도 범위 ${h.minTempC?.let(::one) ?: "—"}℃ ~ ${h.maxTempC?.let(::one) ?: "—"}℃")
                    append("\n※ '관측 방전 환산'은 BMS 실제 cycle count가 아니라 원본 SOC 하락 합계를 100%로 나눈 값입니다.")
                }
            }
        }.start()
    }

    private fun refreshLive() {
        val s = AvinoxBleStateStore(this).snapshot()
        val raw = AvinoxBleStateStore(this).rawSnapshot()
        val age = if (s.updatedMs > 0) ((System.currentTimeMillis() - s.updatedMs).coerceAtLeast(0L) / 1000L) else -1L
        tvLive.text = buildString {
            append("SOC ${s.soc?.let { "$it%" } ?: "—"} · ${s.state}")
            if (age >= 0) append(" · ${age}초 전")
            if (!s.address.isNullOrBlank()) append("\n장치 ${s.address}")
        }
        tvRaw.text = if (raw.hex.isNullOrBlank()) "FFF4 RAW 수신 전" else {
            val clipped = if (raw.hex!!.length > 320) raw.hex!!.take(320) + " …" else raw.hex!!
            "FFF4 RAW · 이번 화면 ${rawCount}패킷\n$clipped"
        }
    }

    private fun shareExport() {
        Toast.makeText(this, "진단 ZIP 만드는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val file = store.exportCurrentOrLatest()
            runOnUiThread {
                if (file == null) { Toast.makeText(this, "내보낼 테스트 세션이 없습니다.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "배터리 진단 로그 공유"))
            }
        }.start()
    }

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun two(v: Double) = String.format(Locale.US, "%.2f", v)
}
