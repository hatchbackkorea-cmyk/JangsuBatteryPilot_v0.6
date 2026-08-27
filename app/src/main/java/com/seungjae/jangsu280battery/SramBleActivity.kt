package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Read-only SRAM AXS GATT laboratory. No characteristic WRITE is ever issued here. */
class SramBleActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 9401
        private const val REQ_BT = 9402
        private const val REQ_SAVE = 9403
        private const val SCAN_MS = 12_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvGatt: TextView
    private lateinit var tvCandidates: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnCapture: Button
    private lateinit var btnFinish: Button
    private lateinit var btnSave: Button

    private val handler = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, Seen>()
    private val latest = ConcurrentHashMap<String, ByteArray>()
    private val baseline = ConcurrentHashMap<String, ByteArray>()
    private val changeCounts = ConcurrentHashMap<String, Int>()
    private val log = mutableListOf<String>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val readable = mutableListOf<BluetoothGattCharacteristic>()
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var ready = false
    private var capturing = false
    private var analyzeAfterRead = false
    private var connectedName = ""

    private data class Seen(val address: String, var name: String, var rssi: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sram_ble)
        applyInsets()
        tvStatus = findViewById(R.id.tvSramStatus)
        tvBattery = findViewById(R.id.tvSramBattery)
        tvGatt = findViewById(R.id.tvSramGatt)
        tvCandidates = findViewById(R.id.tvSramCandidates)
        deviceList = findViewById(R.id.llSramDevices)
        btnScan = findViewById(R.id.btnSramScan)
        btnDisconnect = findViewById(R.id.btnSramDisconnect)
        btnCapture = findViewById(R.id.btnSramCapture)
        btnFinish = findViewById(R.id.btnSramFinish)
        btnSave = findViewById(R.id.btnSramSave)
        findViewById<Button>(R.id.btnSramBack).setOnClickListener { finish() }
        btnScan.setOnClickListener { ensureAndScan() }
        btnDisconnect.setOnClickListener { disconnect("연결 해제") }
        btnCapture.setOnClickListener { startCapture() }
        btnFinish.setOnClickListener { finishCapture() }
        btnSave.setOnClickListener { exportLog() }
        append("SESSION,${now()},app=${BuildConfig.VERSION_NAME},mode=SRAM_READ_ONLY")
        updateButtons()
    }

    override fun onDestroy() { stopScan(); disconnect(null); super.onDestroy() }

    private fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun ensureAndScan() {
        if (!hasPermissions()) { requestPermissions(permissions(), REQ_PERMS); return }
        val a = adapter() ?: run { tvStatus.text = "Bluetooth LE를 지원하지 않습니다."; return }
        if (!a.isEnabled) { @Suppress("DEPRECATION") startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); return }
        startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) ensureAndScan()
    }

    @Deprecated("compat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BT -> if (resultCode == RESULT_OK) startScan()
            REQ_SAVE -> if (resultCode == RESULT_OK) data?.data?.let(::writeLog)
        }
    }

    private fun adapter(): BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Suppress("MissingPermission")
    private fun startScan() {
        stopScan(); disconnect(null)
        devices.clear(); deviceList.removeAllViews(); latest.clear(); baseline.clear(); changeCounts.clear(); ready = false; capturing = false
        tvBattery.text = "SRAM 배터리 —"; tvGatt.text = "SRAM 장치를 검색 중…"; tvCandidates.text = "후보가 여기에 표시됩니다."
        val scanner = adapter()?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { onSeen(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::onSeen) }
            override fun onScanFailed(errorCode: Int) { runOnUiThread { scanning = false; tvStatus.text = "검색 실패 · $errorCode"; updateButtons() } }
        }
        scanCallback = cb; scanner.startScan(cb); scanning = true
        tvStatus.text = "SRAM BLE 검색 중… 드레일러를 변속 버튼으로 깨워두세요."
        append("SCAN_START,${now()}"); updateButtons()
        handler.postDelayed({ if (scanning) { stopScan(); tvStatus.text = "검색 완료 · SRAM 장치를 눌러 연결하세요." } }, SCAN_MS)
    }

    @Suppress("MissingPermission")
    private fun onSeen(result: ScanResult) {
        val d = result.device ?: return
        val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: "이름 없음"
        val prior = devices[d.address]
        if (prior == null) {
            devices[d.address] = Seen(d.address, name, result.rssi)
            append("DEVICE,${now()},name=${csv(name)},addr=${d.address},rssi=${result.rssi}")
        } else { prior.name = if (name != "이름 없음") name else prior.name; prior.rssi = result.rssi }
        runOnUiThread { renderDevices() }
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        val items = devices.values.sortedWith(compareByDescending<Seen> { isSram(it.name) }.thenByDescending { it.rssi })
        items.take(12).forEach { s ->
            deviceList.addView(Button(this).apply {
                isAllCaps = false
                text = "${if (isSram(s.name)) "★ " else ""}${s.name} · ${s.rssi} dBm\n${s.address}"
                setOnClickListener { connect(s) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun isSram(name: String) = name.lowercase(Locale.US).contains("sram") || name.lowercase(Locale.US).contains("axs")

    @Suppress("MissingPermission")
    private fun stopScan() {
        if (scanning && hasPermissions()) runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false; scanCallback = null; handler.removeCallbacksAndMessages(null); updateButtons()
    }

    @Suppress("MissingPermission")
    private fun connect(s: Seen) {
        stopScan(); disconnect(null); ready = false
        connectedName = "${s.name} (${s.address})"; tvStatus.text = "연결 중… $connectedName"; append("CONNECT_START,${now()},name=${csv(s.name)},addr=${s.address}")
        val device = adapter()?.getRemoteDevice(s.address) ?: return
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(this, false, callback)
        updateButtons()
    }

    private val callback = object : BluetoothGattCallback() {
        @Suppress("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            append("CONNECTION,${now()},status=$status,state=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { tvStatus.text = "연결됨 · 서비스 검색 중…" }; g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false; runOnUiThread { tvStatus.text = "연결 끊김 · status=$status"; updateButtons() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            append("SERVICES,${now()},status=$status,count=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            prepare(g.services); runOnUiThread { tvStatus.text = "서비스 ${g.services.size}개 발견 · READ 수집 중…"; renderGatt() }; readNext(g)
        }

        @Deprecated("pre33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) capture(ch, ch.value ?: byteArrayOf(), "READ")
            readNext(g)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) capture(ch, value, "READ")
            readNext(g)
        }
        @Deprecated("pre33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { capture(ch, ch.value ?: byteArrayOf(), "NOTIFY") }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) { capture(ch, value, "NOTIFY") }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            append("SUBSCRIBE,${now()},char=${descriptor.characteristic.uuid},status=$status"); subscribeNext(g)
        }
    }

    private fun prepare(services: List<BluetoothGattService>) {
        synchronized(readQueue) { readQueue.clear() }; synchronized(notifyQueue) { notifyQueue.clear() }; readable.clear()
        services.forEach { svc ->
            append("SERVICE,${now()},uuid=${svc.uuid}")
            svc.characteristics.forEach { ch ->
                val p = props(ch.properties); append("CHAR,${now()},svc=${svc.uuid},uuid=${ch.uuid},props=${csv(p)}")
                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) { readable += ch; readQueue.add(ch) }
                if ((ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notifyQueue.add(ch)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun readNext(g: BluetoothGatt) {
        val ch = synchronized(readQueue) { if (readQueue.isEmpty()) null else readQueue.removeFirst() }
        if (ch == null) {
            if (analyzeAfterRead) { analyzeAfterRead = false; runOnUiThread { analyzeCandidates(); updateButtons() }; return }
            subscribeNext(g); return
        }
        if (!runCatching { g.readCharacteristic(ch) }.getOrDefault(false)) readNext(g)
    }

    @Suppress("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = synchronized(notifyQueue) { if (notifyQueue.isEmpty()) null else notifyQueue.removeFirst() }
        if (ch == null) {
            ready = true
            runOnUiThread { tvStatus.text = "준비 완료 · READ ${readable.size}개 + Notify 자동구독\n‘캡처 시작’ 후 여러 단 변속하세요."; renderGatt(); updateButtons() }
            append("READY,${now()},readable=${readable.size},values=${latest.size}"); return
        }
        if (!runCatching { g.setCharacteristicNotification(ch, true) }.getOrDefault(false)) { subscribeNext(g); return }
        val d = ch.getDescriptor(CCCD) ?: run { subscribeNext(g); return }
        val value = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == 0
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                d.value = value
                g.writeDescriptor(d)
            }.getOrDefault(false)
        }
        if (!ok) subscribeNext(g)
    }

    private fun capture(ch: BluetoothGattCharacteristic, value: ByteArray, source: String) {
        val key = "${ch.service?.uuid ?: "?"}/${ch.uuid}"
        val copy = value.copyOf(); val old = latest.put(key, copy)
        if (capturing && old != null && !old.contentEquals(copy)) changeCounts[key] = (changeCounts[key] ?: 0) + 1
        if (ch.uuid == BATTERY_LEVEL && copy.isNotEmpty()) runOnUiThread { tvBattery.text = "SRAM 배터리 ${copy[0].toInt() and 0xff}%" }
        append("VALUE,${now()},source=$source,key=${csv(key)},hex=${hex(copy)}")
        runOnUiThread { renderGatt() }
    }

    private fun startCapture() {
        if (!ready) { Toast.makeText(this, "SRAM 연결 준비가 끝날 때까지 기다려주세요.", Toast.LENGTH_SHORT).show(); return }
        baseline.clear(); latest.forEach { (k, v) -> baseline[k] = v.copyOf() }; changeCounts.clear(); capturing = true
        tvCandidates.text = "● 캡처 중…\nSRAM 버튼으로 가볍게/무겁게 여러 단을 천천히 변속하세요. 끝나면 ‘종료 · 분석’."
        append("CAPTURE_START,${now()},baseline=${baseline.size}"); updateButtons()
    }

    private fun finishCapture() {
        if (!capturing) { Toast.makeText(this, "먼저 캡처 시작을 눌러주세요.", Toast.LENGTH_SHORT).show(); return }
        capturing = false; append("CAPTURE_STOP,${now()},notifyChanged=${changeCounts.size}")
        val g = gatt ?: return
        synchronized(readQueue) { readQueue.clear(); readable.forEach { readQueue.add(it) } }
        analyzeAfterRead = true; tvStatus.text = "변속 후 전체 READ 재검사 중…"; readNext(g); updateButtons()
    }

    private fun analyzeCandidates() {
        data class Candidate(val key: String, val score: Int, val before: ByteArray?, val after: ByteArray?, val notifyChanges: Int)
        val keys = (baseline.keys + latest.keys + changeCounts.keys).toSet()
        val list = keys.mapNotNull { k ->
            val b = baseline[k]; val a = latest[k]; val n = changeCounts[k] ?: 0
            val different = b != null && a != null && !b.contentEquals(a)
            var score = n * 25 + if (different) 80 else 0
            if (k.endsWith(BATTERY_LEVEL.toString(), true)) score -= 100
            if (score <= 0) null else Candidate(k, score, b, a, n)
        }.sortedByDescending { it.score }
        tvCandidates.text = if (list.isEmpty()) {
            "변속과 함께 바뀐 BLE characteristic을 찾지 못했습니다.\n현재 기어 정보가 ANT+로만 나가거나, SRAM 보안 세션 뒤에서만 보일 가능성이 있습니다. 로그를 저장해서 보내주세요."
        } else buildString {
            append("변속 후보 ${list.size}개 · 점수순\n")
            list.take(12).forEachIndexed { i, c ->
                append("\n#${i + 1} score ${c.score} · notify변화 ${c.notifyChanges}\n")
                append(shortKey(c.key)).append("\n")
                append("전 ").append(hex(c.before)).append("\n후 ").append(hex(c.after)).append("\n")
                val diff = diffIndexes(c.before, c.after)
                if (diff.isNotEmpty()) append("변경 byte: ").append(diff.joinToString(",")).append("\n")
            }
        }
        append("ANALYZE,${now()},candidates=${list.size}")
        tvStatus.text = "분석 완료 · 후보 ${list.size}개"
    }

    private fun renderGatt() {
        if (latest.isEmpty()) { tvGatt.text = "아직 GATT 값이 없습니다."; return }
        tvGatt.text = latest.entries.sortedBy { it.key }.take(40).joinToString("\n") { (k, v) -> "${shortKey(k)}  ${hex(v).take(80)}" }
    }

    @Suppress("MissingPermission")
    private fun disconnect(message: String?) {
        val old = gatt; gatt = null; ready = false; capturing = false; analyzeAfterRead = false
        if (old != null && hasPermissions()) { runCatching { old.disconnect() }; runCatching { old.close() } }
        synchronized(readQueue) { readQueue.clear() }; synchronized(notifyQueue) { notifyQueue.clear() }; readable.clear()
        if (!message.isNullOrBlank() && ::tvStatus.isInitialized) tvStatus.text = message
        if (::btnScan.isInitialized) updateButtons()
    }

    private fun updateButtons() {
        btnScan.isEnabled = !scanning
        btnDisconnect.isEnabled = gatt != null
        btnCapture.isEnabled = ready && !capturing
        btnFinish.isEnabled = ready && capturing
        btnSave.isEnabled = log.isNotEmpty()
    }

    private fun exportLog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "sram_ble_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt") }
        @Suppress("DEPRECATION") startActivityForResult(intent, REQ_SAVE)
    }

    private fun writeLog(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { out ->
                out.appendLine("# SRAM AXS BLE read-only diagnostic")
                out.appendLine("# app=${BuildConfig.VERSION_NAME} exported=${now()}")
                out.appendLine("# No characteristic WRITE command is issued by this activity.")
                synchronized(log) { log.forEach { out.appendLine(it) } }
                out.appendLine("# LATEST")
                latest.entries.sortedBy { it.key }.forEach { out.appendLine("LATEST,${csv(it.key)},${hex(it.value)}") }
            } ?: error("파일을 열 수 없습니다.")
        }.onSuccess { Toast.makeText(this, "SRAM 로그 저장 완료 · 이 TXT를 채팅에 올려주세요.", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(this, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun append(line: String) { synchronized(log) { log += line; if (log.size > 20000) log.removeAt(0) }; if (::btnSave.isInitialized) runOnUiThread { updateButtons() } }
    private fun props(p: Int): String { val x = mutableListOf<String>(); if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) x += "READ"; if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) x += "NOTIFY"; if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) x += "INDICATE"; if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) x += "WRITE"; if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) x += "WRITE_NR"; return x.joinToString("|") }
    private fun hex(v: ByteArray?): String = v?.joinToString("-") { "%02X".format(it.toInt() and 0xff) } ?: "—"
    private fun diffIndexes(a: ByteArray?, b: ByteArray?): List<Int> { if (a == null || b == null) return emptyList(); val n = maxOf(a.size, b.size); return (0 until n).filter { i -> a.getOrNull(i) != b.getOrNull(i) } }
    private fun shortKey(key: String): String = key.split('/').joinToString(" / ") { part -> val s = part.lowercase(Locale.US); if (s.startsWith("d905")) s.substringBefore("-90aa") else if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s.take(12) }
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    private fun csv(s: String): String = "\"${s.replace("\"", "\"\"")}\""

    private fun applyInsets() {
        val root = findViewById<View>(R.id.rootSramBle); val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins -> val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom); ins }
        ViewCompat.requestApplyInsets(root)
    }
}
