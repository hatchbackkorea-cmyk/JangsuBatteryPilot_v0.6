package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class BatteryForensicsActivity : Activity() {
    companion object {
        private const val REQ_BLE = 296
        private const val PRE_WINDOW_MS = 8_000L
        private const val POST_WINDOW_MS = 20_000L
    }

    private data class ActiveCapture(
        val window: BatteryForensicsStore.CaptureWindow,
        var rawPacketCount: Int = 0,
        val lengthCounts: MutableMap<Int, Int> = linkedMapOf(),
        var gattAttempted: Int = 0,
        var gattSucceeded: Int = 0
    )

    private lateinit var store: BatteryForensicsStore
    private lateinit var tvLive: TextView
    private lateinit var tvHistory: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvUnknown: TextView
    private lateinit var tvRaw: TextView
    private lateinit var tvCaptureStatus: TextView
    private var client: AvinoxBleSocClient? = null
    private var rawCount = 0
    private val main = Handler(Looper.getMainLooper())
    private val recentRaw = ArrayDeque<BatteryForensicsStore.RawPacket>()
    private var activeCapture: ActiveCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_forensics)
        store = BatteryForensicsStore(this)
        tvLive = findViewById(R.id.tvBatteryProbeLive)
        tvHistory = findViewById(R.id.tvBatteryProbeHistory)
        tvSession = findViewById(R.id.tvBatteryProbeSession)
        tvUnknown = findViewById(R.id.tvBatteryProbeUnknown)
        tvRaw = findViewById(R.id.tvBatteryProbeRaw)
        tvCaptureStatus = findViewById(R.id.tvBatteryCaptureStatus)

        findViewById<Button>(R.id.btnBatteryProbeStartSession).setOnClickListener {
            store.startOrResumeSession(); refreshSession(); Toast.makeText(this, "테스트 세션을 시작/이어갑니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnBatteryProbeEndSession).setOnClickListener {
            if (activeCapture != null) {
                Toast.makeText(this, "현재 20초 캡처가 끝난 뒤 세션을 종료해 주세요.", Toast.LENGTH_SHORT).show()
            } else {
                store.endSession(); refreshSession(); Toast.makeText(this, "세션을 닫았습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnBatteryProbeAnalyzeProto).setOnClickListener { refreshHistory() }
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
        refreshLive()
        refreshSession()
        if (RideLogManager(this).isActive()) {
            tvLive.append("\n주행 서비스가 BLE를 수집 중이라 별도 연결은 하지 않습니다.")
        } else startProbeIfPossible()
    }

    override fun onPause() {
        activeCapture?.let { finishCapture(it, "화면 종료로 20초 창이 조기 종료됨") }
        client?.stop(); client = null
        super.onPause()
    }

    private fun bindCapture(id: Int, label: String) {
        findViewById<Button>(id).setOnClickListener { beginTimedCapture(label) }
    }

    private fun beginTimedCapture(label: String) {
        if (activeCapture != null) {
            Toast.makeText(this, "현재 캡처가 진행 중입니다. 약 20초만 기다려 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        trimRecentRaw(now)
        val pre = synchronized(recentRaw) { recentRaw.toList() }
        val window = store.beginCaptureWindow(label, pre) ?: run {
            Toast.makeText(this, "세션을 시작하지 못했습니다.", Toast.LENGTH_SHORT).show(); return
        }
        val capture = ActiveCapture(window)
        activeCapture = capture
        tvCaptureStatus.text = "● $label 수집 중 · 직전 ${PRE_WINDOW_MS / 1000}초 저장 완료 · 이후 ${POST_WINDOW_MS / 1000}초 수집 중…"
        val gattStarted = client?.requestGattReadSweep() == true
        if (!gattStarted) tvCaptureStatus.append("\nGATT READ는 연결 상태 때문에 이번 캡처에서 시작하지 못했습니다.")
        main.postDelayed({
            if (activeCapture === capture) finishCapture(capture, "정상 20초 수집 완료")
        }, POST_WINDOW_MS)
        Toast.makeText(this, "$label · 20초 전체 패킷 수집 시작", Toast.LENGTH_SHORT).show()
    }

    private fun finishCapture(capture: ActiveCapture, note: String) {
        if (activeCapture !== capture) return
        activeCapture = null
        store.finishCaptureWindow(
            window = capture.window,
            rawPacketCount = capture.rawPacketCount,
            packetLengthCounts = capture.lengthCounts,
            gattAttempted = capture.gattAttempted,
            gattSucceeded = capture.gattSucceeded,
            note = note
        )
        tvCaptureStatus.text = "✓ ${capture.window.label} 저장 완료 · 이후 FFF4 ${capture.rawPacketCount}패킷 · 길이 ${formatLengthCounts(capture.lengthCounts)} · GATT ${capture.gattSucceeded}/${capture.gattAttempted}"
        refreshSession()
        refreshLive()
        Toast.makeText(this, "${capture.window.label} 캡처 완료", Toast.LENGTH_SHORT).show()
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
                val packet = BatteryForensicsStore.RawPacket(timestampMs, bytes.copyOf(), address)
                synchronized(recentRaw) {
                    recentRaw.addLast(packet)
                    trimRecentRawLocked(timestampMs)
                }
                activeCapture?.let { capture ->
                    capture.rawPacketCount += 1
                    capture.lengthCounts[bytes.size] = (capture.lengthCounts[bytes.size] ?: 0) + 1
                    store.appendCaptureRaw(capture.window, "POST", timestampMs, bytes, address)
                }
                runOnUiThread { refreshLive() }
            }

            override fun onGattRead(
                timestampMs: Long,
                serviceUuid: UUID,
                characteristicUuid: UUID,
                properties: Int,
                status: Int,
                bytes: ByteArray,
                address: String?
            ) {
                activeCapture?.let { capture ->
                    store.appendCaptureGattRead(
                        capture.window,
                        timestampMs,
                        serviceUuid.toString(),
                        characteristicUuid.toString(),
                        properties,
                        status,
                        bytes,
                        address
                    )
                }
            }

            override fun onGattSweepFinished(timestampMs: Long, attempted: Int, succeeded: Int, address: String?) {
                activeCapture?.let { capture ->
                    capture.gattAttempted = attempted
                    capture.gattSucceeded = succeeded
                }
                runOnUiThread {
                    activeCapture?.let { tvCaptureStatus.append("\nGATT READ $succeeded/$attempted 완료") }
                }
            }
        }).also { it.start() }
    }

    private fun trimRecentRaw(nowMs: Long) = synchronized(recentRaw) { trimRecentRawLocked(nowMs) }

    private fun trimRecentRawLocked(nowMs: Long) {
        val cutoff = nowMs - PRE_WINDOW_MS
        while (recentRaw.isNotEmpty() && recentRaw.first().timestampMs < cutoff) recentRaw.removeFirst()
        while (recentRaw.size > 2000) recentRaw.removeFirst()
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
        refreshSession()
        refreshHistory()
        tvUnknown.text = "[미해독 · 실제 값 확인 전에는 숫자를 만들지 않음]\nBMS 실제 사이클 · SOH · 팩 전압/전류 · 셀별 전압/편차 · 밸런싱 · 충전 제한/보호상태 · 배터리 FW/시리얼 · 좌/우 스위치 정확한 배터리 %"
    }

    private fun refreshSession() {
        val s = store.status()
        tvSession.text = if (s.open) {
            "열린 세션 ${s.id} · 완료 캡처 ${s.snapshots}회\n" + if (s.labels.isEmpty()) "아직 상태 캡처 없음" else s.labels.joinToString(" · ")
        } else {
            val last = store.latestSessionId()
            if (last != null) "현재 열린 세션 없음 · 최근 $last\n새 세션을 시작할 수 있습니다." else "테스트 세션 없음"
        }
    }

    private fun refreshHistory() {
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
        if (activeCapture != null) {
            Toast.makeText(this, "현재 캡처가 끝난 뒤 ZIP을 공유해 주세요.", Toast.LENGTH_SHORT).show(); return
        }
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

    private fun formatLengthCounts(counts: Map<Int, Int>): String =
        if (counts.isEmpty()) "—" else counts.toSortedMap().entries.joinToString(", ") { "${it.key}B×${it.value}" }

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun two(v: Double) = String.format(Locale.US, "%.2f", v)
}
