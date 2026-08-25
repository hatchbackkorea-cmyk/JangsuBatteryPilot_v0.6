package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
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
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Experimental BLE laboratory for Avinox / AMFLOW.
 *
 * IMPORTANT:
 * - Diagnostic only. Nothing discovered here is used by BatteryPlan or learning.
 * - Raw BLE values stay on-device unless the user explicitly exports a text log.
 * - The activity scans every BLE peripheral because Avinox device names may vary.
 */
class BleDiagnosticActivity : Activity() {
    companion object {
        private const val REQ_BLE_PERMISSIONS = 8101
        private const val REQ_ENABLE_BT = 8102
        private const val REQ_SAVE_LOG = 8103
        private const val SCAN_MS = 15_000L
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val STANDARD_BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val STANDARD_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvCandidate: TextView
    private lateinit var tvRaw: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var etBattery: EditText
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnCompare: Button
    private lateinit var btnSave: Button

    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, SeenDevice>()
    private val latestValues = ConcurrentHashMap<String, ByteArray>()
    private val seenValueHistory = LinkedHashMap<String, MutableList<ValueSnapshot>>()
    private val logLines = mutableListOf<String>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var connectedLabel: String = ""
    private var isScanning = false

    private data class SeenDevice(
        val address: String,
        var name: String,
        var rssi: Int,
        var adSummary: String
    )

    private data class ValueSnapshot(
        val atMs: Long,
        val hex: String,
        val bytes: ByteArray
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_diagnostic)

        findViewById<Button>(R.id.btnBleBack).setOnClickListener { finish() }
        tvStatus = findViewById(R.id.tvBleStatus)
        tvCandidate = findViewById(R.id.tvBleCandidate)
        tvRaw = findViewById(R.id.tvBleRaw)
        deviceList = findViewById(R.id.bleDeviceList)
        etBattery = findViewById(R.id.etBleBattery)
        btnScan = findViewById(R.id.btnBleScan)
        btnStop = findViewById(R.id.btnBleStop)
        btnDisconnect = findViewById(R.id.btnBleDisconnect)
        btnCompare = findViewById(R.id.btnBleCompare)
        btnSave = findViewById(R.id.btnBleSave)

        etBattery.inputType = InputType.TYPE_CLASS_NUMBER
        btnScan.setOnClickListener { ensureReadyAndScan() }
        btnStop.setOnClickListener { stopScan("사용자가 스캔을 중지했습니다.") }
        btnDisconnect.setOnClickListener { disconnectGatt("연결 해제") }
        btnCompare.setOnClickListener { compareWithDashboardBattery() }
        btnSave.setOnClickListener { exportLog() }

        appendLog("SESSION,${isoNow()},app=${BuildConfig.VERSION_NAME},sdk=${Build.VERSION.SDK_INT}")
        updateButtons()
    }

    override fun onDestroy() {
        stopScan(null)
        disconnectGatt(null)
        super.onDestroy()
    }

    private fun ensureReadyAndScan() {
        if (!hasBlePermissions()) {
            requestPermissions(requiredBlePermissions(), REQ_BLE_PERMISSIONS)
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            tvStatus.text = "이 휴대폰은 Bluetooth LE를 지원하지 않습니다."
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            return
        }
        startScan()
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                ensureReadyAndScan()
            } else {
                tvStatus.text = "BLE 진단에는 주변기기 검색/연결 권한이 필요합니다."
            }
        }
    }

    @Deprecated("Deprecated in Android; used for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ENABLE_BT -> if (resultCode == RESULT_OK) startScan() else tvStatus.text = "Bluetooth가 꺼져 있습니다."
            REQ_SAVE_LOG -> if (resultCode == RESULT_OK) data?.data?.let { writeLogTo(it) }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (isScanning) return
        disconnectGatt(null)
        devices.clear()
        deviceList.removeAllViews()
        latestValues.clear()
        seenValueHistory.clear()
        tvRaw.text = "Characteristic 데이터가 아직 없습니다."
        tvCandidate.text = "계기판 배터리 %를 입력한 뒤 연결 후 '현재 값과 대조'를 눌러주세요."

        val scanner = bluetoothAdapter()?.bluetoothLeScanner
        if (scanner == null) {
            tvStatus.text = "BLE 스캐너를 시작할 수 없습니다."
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleScanResult)
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    isScanning = false
                    tvStatus.text = "BLE 검색 실패 · 코드 $errorCode"
                    appendLog("SCAN_FAIL,${isoNow()},code=$errorCode")
                    updateButtons()
                }
            }
        }
        scannerCallback = callback
        scanner.startScan(callback)
        isScanning = true
        tvStatus.text = "주변 BLE 기기 검색 중… (${SCAN_MS / 1000}초)\n자전거 전원을 켜고 Avinox 앱이 연결 가능한 상태로 두세요."
        appendLog("SCAN_START,${isoNow()}")
        updateButtons()
        main.postDelayed({ if (isScanning) stopScan("검색 완료 · 아래 기기를 눌러 연결하세요.") }, SCAN_MS)
    }

    @Suppress("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName
            ?: runCatching { device.name }.getOrNull()
            ?: "이름 없음"
        val ad = adSummary(result)
        val prior = devices[device.address]
        if (prior == null) {
            devices[device.address] = SeenDevice(device.address, name, result.rssi, ad)
            appendLog("DEVICE,${isoNow()},addr=${device.address},name=${csv(name)},rssi=${result.rssi},ad=${csv(ad)}")
        } else {
            prior.name = if (name != "이름 없음") name else prior.name
            prior.rssi = result.rssi
            if (ad.isNotBlank()) prior.adSummary = ad
        }
        runOnUiThread { renderDevices() }
    }

    private fun adSummary(result: ScanResult): String {
        val record = result.scanRecord ?: return ""
        val parts = mutableListOf<String>()
        record.serviceUuids?.takeIf { it.isNotEmpty() }?.let { uuids ->
            parts += "svc=" + uuids.joinToString("|") { shortUuid(it.uuid) }
        }
        val msd = record.manufacturerSpecificData
        for (i in 0 until msd.size()) {
            parts += "mfg:${msd.keyAt(i)}=${hex(msd.valueAt(i))}"
        }
        record.serviceData?.forEach { (uuid, bytes) -> parts += "sd:${shortUuid(uuid.uuid)}=${hex(bytes)}" }
        return parts.joinToString(" ").take(600)
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        val sorted = devices.values.sortedWith(compareByDescending<SeenDevice> { if (likelyAvinox(it.name)) 1 else 0 }.thenByDescending { it.rssi })
        if (sorted.isEmpty()) {
            val t = TextView(this).apply {
                text = "아직 발견된 BLE 기기가 없습니다."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }
            deviceList.addView(t)
            return
        }
        sorted.forEach { d ->
            val button = Button(this).apply {
                text = buildString {
                    if (likelyAvinox(d.name)) append("★ ")
                    append(d.name)
                    append(" · ")
                    append(d.rssi)
                    append(" dBm\n")
                    append(d.address)
                    if (d.adSummary.isNotBlank()) append("\n").append(d.adSummary.take(130))
                }
                isAllCaps = false
                setOnClickListener { connectTo(d) }
            }
            deviceList.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
    }

    private fun likelyAvinox(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return listOf("avinox", "amflow", "dji", "dpc", "drive").any { n.contains(it) }
    }

    @Suppress("MissingPermission")
    private fun stopScan(message: String?) {
        if (isScanning && hasBlePermissions()) {
            runCatching { bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scannerCallback) }
        }
        isScanning = false
        scannerCallback = null
        main.removeCallbacksAndMessages(null)
        if (!message.isNullOrBlank()) tvStatus.text = message
        appendLog("SCAN_STOP,${isoNow()},count=${devices.size}")
        updateButtons()
    }

    @Suppress("MissingPermission")
    private fun connectTo(seen: SeenDevice) {
        if (!hasBlePermissions()) return
        stopScan(null)
        disconnectGatt(null)
        val device = bluetoothAdapter()?.getRemoteDevice(seen.address) ?: return
        connectedLabel = "${seen.name} (${seen.address})"
        tvStatus.text = "연결 중… $connectedLabel"
        appendLog("CONNECT_START,${isoNow()},addr=${seen.address},name=${csv(seen.name)}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(this, false, gattCallback)
        }
        updateButtons()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @Suppress("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            appendLog("CONNECTION,${isoNow()},status=$status,state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { tvStatus.text = "연결됨 · $connectedLabel\n서비스 검색 중…" }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvStatus.text = "연결 해제 · status=$status"
                    updateButtons()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            appendLog("SERVICES,${isoNow()},status=$status,count=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { tvStatus.text = "서비스 검색 실패 · status=$status" }
                return
            }
            prepareCharacteristics(g.services)
            runOnUiThread {
                tvStatus.text = "연결됨 · 서비스 ${g.services.size}개\nReadable 값을 읽고 Notify/Indicate 채널을 구독합니다."
                renderRawSummary()
            }
            readNext(g)
        }

        @Deprecated("Deprecated callback before API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            appendLog("READ_RESULT,${isoNow()},char=${characteristic.uuid},status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) capture(characteristic, characteristic.value ?: byteArrayOf(), "READ")
            readNext(g)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            appendLog("READ_RESULT,${isoNow()},char=${characteristic.uuid},status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) capture(characteristic, value, "READ")
            readNext(g)
        }

        @Deprecated("Deprecated callback before API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            capture(characteristic, characteristic.value ?: byteArrayOf(), "NOTIFY")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            capture(characteristic, value, "NOTIFY")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            appendLog("SUBSCRIBE,${isoNow()},char=${descriptor.characteristic.uuid},status=$status")
            subscribeNext(g)
        }
    }

    private fun prepareCharacteristics(services: List<BluetoothGattService>) {
        synchronized(readQueue) { readQueue.clear() }
        synchronized(notifyQueue) { notifyQueue.clear() }
        services.forEach { service ->
            appendLog("SERVICE,${isoNow()},uuid=${service.uuid},type=${service.type}")
            service.characteristics.forEach { ch ->
                val props = propertyText(ch.properties)
                appendLog("CHAR,${isoNow()},svc=${service.uuid},uuid=${ch.uuid},props=${csv(props)}")
                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) synchronized(readQueue) { readQueue.add(ch) }
                if ((ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                    synchronized(notifyQueue) { notifyQueue.add(ch) }
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun readNext(g: BluetoothGatt) {
        val ch = synchronized(readQueue) { if (readQueue.isEmpty()) null else readQueue.removeFirst() }
        if (ch == null) {
            subscribeNext(g)
            return
        }
        val ok = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
        if (!ok) readNext(g)
    }

    @Suppress("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = synchronized(notifyQueue) { if (notifyQueue.isEmpty()) null else notifyQueue.removeFirst() }
        if (ch == null) {
            runOnUiThread {
                tvStatus.text = "진단 수집 중 · $connectedLabel\n자전거를 켠 상태로 두고 배터리 %를 입력해 대조해보세요."
                compareWithDashboardBattery(silentIfEmpty = true)
            }
            return
        }
        val enabled = runCatching { g.setCharacteristicNotification(ch, true) }.getOrDefault(false)
        if (!enabled) {
            subscribeNext(g)
            return
        }
        val cccd = ch.getDescriptor(CLIENT_CONFIG_UUID)
        if (cccd == null) {
            subscribeNext(g)
            return
        }
        val value = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == 0
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                cccd.value = value
                g.writeDescriptor(cccd)
            }.getOrDefault(false)
        }
        if (!ok) subscribeNext(g)
    }

    private fun capture(ch: BluetoothGattCharacteristic, value: ByteArray, source: String) {
        val key = "${ch.service?.uuid ?: "?"}/${ch.uuid}"
        val copy = value.copyOf()
        latestValues[key] = copy
        synchronized(seenValueHistory) {
            val history = seenValueHistory.getOrPut(key) { mutableListOf() }
            val hx = hex(copy)
            if (history.lastOrNull()?.hex != hx || source == "READ") {
                history += ValueSnapshot(System.currentTimeMillis(), hx, copy)
                if (history.size > 80) history.removeAt(0)
            }
        }
        appendLog("VALUE,${isoNow()},source=$source,key=${csv(key)},hex=${hex(copy)},decoded=${csv(decodeHints(copy))}")
        runOnUiThread {
            renderRawSummary()
            val target = etBattery.text.toString().toIntOrNull()
            if (target != null) renderCandidateMatches(target)
        }
    }

    private fun renderRawSummary() {
        if (latestValues.isEmpty()) {
            tvRaw.text = "Characteristic 데이터가 아직 없습니다."
            return
        }
        val lines = latestValues.entries
            .sortedBy { it.key }
            .take(80)
            .map { (k, v) ->
                val mark = if (k.endsWith(STANDARD_BATTERY_LEVEL.toString(), ignoreCase = true)) " ★표준 Battery Level" else ""
                "${shortKey(k)}$mark\n  ${hex(v)}  ·  ${decodeHints(v)}"
            }
        tvRaw.text = lines.joinToString("\n")
    }

    private fun compareWithDashboardBattery(silentIfEmpty: Boolean = false) {
        val pct = etBattery.text.toString().trim().toIntOrNull()
        if (pct == null || pct !in 0..100) {
            if (!silentIfEmpty) Toast.makeText(this, "Avinox 앱/계기판의 현재 배터리 %를 0~100으로 입력하세요.", Toast.LENGTH_LONG).show()
            return
        }
        appendLog("REFERENCE_SOC,${isoNow()},pct=$pct")
        renderCandidateMatches(pct)
    }

    private fun renderCandidateMatches(pct: Int) {
        val matches = mutableListOf<Pair<Int, String>>()
        latestValues.forEach { (key, value) ->
            val reasons = matchReasons(value, pct).toMutableList()
            if (key.endsWith(STANDARD_BATTERY_LEVEL.toString(), ignoreCase = true)) reasons.add(0, "표준 Battery Level UUID")
            if (reasons.isNotEmpty()) {
                var score = 0
                for (reason in reasons) {
                    score += when {
                        reason.contains("표준") -> 100
                        reason.contains("byte=$pct") -> 40
                        reason.contains("uint16=$pct") -> 35
                        reason.contains("/100") -> 25
                        reason.contains("0~255") -> 15
                        else -> 10
                    }
                }
                matches += score to "${shortKey(key)}\n  ${hex(value)} · ${reasons.joinToString(", ")}"
            }
        }

        val adMatches = devices.values.flatMap { d ->
            val refs = mutableListOf<Pair<Int, String>>()
            extractHexPayloads(d.adSummary).forEach { payload ->
                val reasons = matchReasons(payload, pct)
                if (reasons.isNotEmpty()) refs += 15 to "광고 ${d.name} (${d.address})\n  ${hex(payload)} · ${reasons.joinToString(", ")}"
            }
            refs
        }
        matches += adMatches

        val sorted = matches.sortedByDescending { it.first }
        tvCandidate.text = if (sorted.isEmpty()) {
            "현재 기준 ${pct}%와 바로 일치하는 후보를 찾지 못했습니다.\n그래도 값 변화가 중요하므로 배터리 %가 바뀐 뒤 다시 같은 기기에 연결/대조해 주세요."
        } else {
            buildString {
                append("현재 기준 ${pct}% · 후보 ${sorted.size}개\n")
                sorted.take(15).forEachIndexed { i, (score, text) ->
                    append("\n#${i + 1} 점수 $score\n$text")
                }
            }
        }
        appendLog("COMPARE,${isoNow()},pct=$pct,candidates=${sorted.size}")
    }

    private fun matchReasons(bytes: ByteArray, pct: Int): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        bytes.forEachIndexed { i, b ->
            val u = b.toInt() and 0xff
            if (u == pct) out += "byte=$pct@index$i"
            if ((u * 100.0 / 255.0).roundToInt() == pct) out += "0~255 스케일@index$i"
        }
        for (i in 0 until bytes.size - 1) {
            val le = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            val be = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
            if (le == pct) out += "uint16=$pct LE@index$i"
            if (be == pct) out += "uint16=$pct BE@index$i"
            if (le in 0..10000 && abs(le / 100.0 - pct) < 0.01) out += "uint16/100=$pct LE@index$i"
            if (be in 0..10000 && abs(be / 100.0 - pct) < 0.01) out += "uint16/100=$pct BE@index$i"
        }
        return out.distinct()
    }

    private fun decodeHints(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        val hints = mutableListOf<String>()
        if (bytes.size == 1) hints += "u8=${bytes[0].toInt() and 0xff}"
        if (bytes.size >= 2) {
            val le = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
            val be = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
            hints += "u16LE=$le"
            hints += "u16BE=$be"
        }
        val ascii = bytes.map { it.toInt() and 0xff }.takeIf { values -> values.all { it in 32..126 } }
            ?.map { it.toChar() }?.joinToString("")
        if (!ascii.isNullOrBlank()) hints += "ascii=$ascii"
        return hints.joinToString(" · ")
    }

    @Suppress("MissingPermission")
    private fun disconnectGatt(message: String?) {
        val old = gatt
        gatt = null
        if (old != null && hasBlePermissions()) {
            runCatching { old.disconnect() }
            runCatching { old.close() }
        }
        synchronized(readQueue) { readQueue.clear() }
        synchronized(notifyQueue) { notifyQueue.clear() }
        if (!message.isNullOrBlank() && ::tvStatus.isInitialized) tvStatus.text = message
        if (::btnDisconnect.isInitialized) updateButtons()
    }

    private fun updateButtons() {
        btnScan.isEnabled = !isScanning
        btnStop.isEnabled = isScanning
        btnDisconnect.isEnabled = gatt != null
        btnCompare.isEnabled = gatt != null || latestValues.isNotEmpty() || devices.isNotEmpty()
        btnSave.isEnabled = logLines.isNotEmpty()
    }

    private fun exportLog() {
        val name = "avinox_ble_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_SAVE_LOG)
    }

    private fun writeLogTo(uri: Uri) {
        runCatching {
            val header = buildString {
                appendLine("# GPX Battery Copilot Avinox BLE Diagnostic")
                appendLine("# app=${BuildConfig.VERSION_NAME}")
                appendLine("# exported=${isoNow()}")
                appendLine("# Diagnostic only: no value below was used for battery learning.")
                appendLine()
            }
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { out ->
                out.write(header)
                synchronized(logLines) { logLines.forEach { out.appendLine(it) } }
                out.appendLine()
                out.appendLine("# LATEST VALUES")
                latestValues.entries.sortedBy { it.key }.forEach { (key, value) ->
                    out.appendLine("LATEST,${csv(key)},${hex(value)},${csv(decodeHints(value))}")
                }
            } ?: error("파일을 열 수 없습니다.")
        }.onSuccess {
            Toast.makeText(this, "BLE 진단 로그를 저장했습니다. 이 파일을 채팅에 올려주세요.", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "로그 저장 실패: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines += line
            if (logLines.size > 12_000) logLines.removeAt(0)
        }
        if (::btnSave.isInitialized) runOnUiThread { updateButtons() }
    }

    private fun propertyText(p: Int): String {
        val names = mutableListOf<String>()
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) names += "READ"
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) names += "WRITE"
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) names += "WRITE_NR"
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) names += "NOTIFY"
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) names += "INDICATE"
        if ((p and BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) names += "BROADCAST"
        return names.joinToString("|").ifBlank { "0x${p.toString(16)}" }
    }

    private fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8) else s
    }

    private fun shortKey(key: String): String {
        val parts = key.split('/')
        if (parts.size != 2) return key
        return "${shortUuid(runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return key)} / ${shortUuid(runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return key)}"
    }

    private fun hex(bytes: ByteArray?): String = bytes?.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }.orEmpty()

    private fun extractHexPayloads(summary: String): List<ByteArray> {
        if (summary.isBlank()) return emptyList()
        return Regex("(?:mfg:[^= ]+|sd:[^= ]+)=([0-9A-Fa-f]+)")
            .findAll(summary.replace(" ", ""))
            .mapNotNull { m ->
                val h = m.groupValues[1]
                if (h.length % 2 != 0) null else runCatching { h.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
            }.toList()
    }

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
