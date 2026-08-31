package com.seungjae.jangsu280battery

import android.Manifest
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * v0.16.3 verified Avinox SOC reader.
 *
 * Field verification (same bike, three independent runs):
 * 66% -> 0x42, 65% -> 0x41, 64% -> 0x40.
 * Packet family: FFF0 / FFF4 NOTIFY, prefix 55 4F 04 39 05 02 ... 57 09 [SOC].
 *
 * Safety:
 * - Two identical consecutive valid packets are required before accepting a SOC.
 * - No write is sent to Avinox except the standard CCCD subscription descriptor.
 * - This class does not train any model. RideService decides when an accepted SOC becomes an actual observation.
 */
class AvinoxBleSocClient(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onBleState(state: String, address: String? = null)
        fun onSoc(soc: Int, timestampMs: Long, address: String?)
        fun onRawNotification(timestampMs: Long, bytes: ByteArray, address: String?)
        fun onGattRead(
            timestampMs: Long,
            serviceUuid: UUID,
            characteristicUuid: UUID,
            properties: Int,
            status: Int,
            bytes: ByteArray,
            address: String?
        ) {}
        fun onGattSweepFinished(timestampMs: Long, attempted: Int, succeeded: Int, address: String?) {}
    }

    companion object {
        private val FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val FFF4: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val FFF0_PARCEL = ParcelUuid(FFF0)
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val RETRY_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val stateStore = AvinoxBleStateStore(app)
    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var started = false
    private var scanning = false
    private var connectedAddress: String? = null
    private var pendingSoc: Int? = null
    private var pendingCount = 0
    private var lastDeliveredSoc: Int? = null
    private val gattReadQueue = java.util.ArrayDeque<BluetoothGattCharacteristic>()
    private var gattReadAttempted = 0
    private var gattReadSucceeded = 0
    private var gattReadSweepActive = false

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            dispatchState("BLE 재검색 대기", connectedAddress)
            scheduleRetry()
        }
    }

    private val retry = Runnable {
        if (started && gatt == null && !scanning) beginScan()
    }

    private val connectTimeout = Runnable {
        if (!started || gatt == null) return@Runnable
        val old = gatt
        gatt = null
        if (old != null && hasConnectPermission()) {
            runCatching { old.disconnect() }
            runCatching { old.close() }
        }
        dispatchState("Avinox 연결 시간초과 · 재검색", connectedAddress)
        beginScan()
    }

    fun start() {
        if (started) return
        started = true
        lastDeliveredSoc = null
        pendingSoc = null
        pendingCount = 0
        gattReadQueue.clear()
        gattReadAttempted = 0
        gattReadSucceeded = 0
        gattReadSweepActive = false
        if (!hasPermissions()) {
            dispatchState("BLE 권한 필요")
            return
        }
        val adapter = adapter()
        if (adapter == null) {
            dispatchState("BLE 미지원")
            return
        }
        if (!adapter.isEnabled) {
            dispatchState("Bluetooth 꺼짐")
            return
        }
        // Try the last verified device first for a fast reconnect; fall back to scanning.
        val lastAddress = stateStore.snapshot().address
        if (!lastAddress.isNullOrBlank()) {
            val device = runCatching { adapter.getRemoteDevice(lastAddress) }.getOrNull()
            if (device != null) {
                connect(device)
                return
            }
        }
        beginScan()
    }

    fun stop() {
        started = false
        main.removeCallbacks(scanTimeout)
        main.removeCallbacks(retry)
        main.removeCallbacks(connectTimeout)
        stopScan()
        val old = gatt
        gatt = null
        if (old != null && hasConnectPermission()) {
            runCatching { old.disconnect() }
            runCatching { old.close() }
        }
        connectedAddress = null
        pendingSoc = null
        pendingCount = 0
        gattReadQueue.clear()
        gattReadSweepActive = false
        dispatchState("BLE 대기")
    }

    private fun adapter(): BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * v0.30.0 battery forensics: read every currently discovered readable GATT
     * characteristic once. This is strictly read-only and never writes a characteristic.
     * The normal CCCD notification subscription is the only BLE write used by this client.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    fun requestGattReadSweep(): Boolean {
        if (!started || !hasConnectPermission()) return false
        val currentGatt = gatt ?: return false
        if (gattReadSweepActive) return false
        val readable = currentGatt.services.flatMap { service ->
            service.characteristics.filter { ch ->
                ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            }
        }
        if (readable.isEmpty()) {
            listener.onGattSweepFinished(System.currentTimeMillis(), 0, 0, connectedAddress)
            return false
        }
        gattReadQueue.clear()
        readable.forEach { gattReadQueue.addLast(it) }
        gattReadAttempted = 0
        gattReadSucceeded = 0
        gattReadSweepActive = true
        main.post { readNextGattCharacteristic(currentGatt) }
        return true
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun readNextGattCharacteristic(currentGatt: BluetoothGatt) {
        if (!started || gatt !== currentGatt || !hasConnectPermission()) {
            finishGattReadSweep()
            return
        }
        val ch = gattReadQueue.pollFirst() ?: run {
            finishGattReadSweep()
            return
        }
        gattReadAttempted += 1
        val ok = runCatching { currentGatt.readCharacteristic(ch) }.getOrDefault(false)
        if (!ok) {
            listener.onGattRead(
                System.currentTimeMillis(),
                ch.service?.uuid ?: UUID(0L, 0L),
                ch.uuid,
                ch.properties,
                -1,
                byteArrayOf(),
                connectedAddress
            )
            main.post { readNextGattCharacteristic(currentGatt) }
        }
    }

    private fun finishGattReadSweep() {
        if (!gattReadSweepActive) return
        gattReadSweepActive = false
        gattReadQueue.clear()
        listener.onGattSweepFinished(System.currentTimeMillis(), gattReadAttempted, gattReadSucceeded, connectedAddress)
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun beginScan() {
        if (!started || scanning || !hasPermissions()) return
        val scanner = adapter()?.bluetoothLeScanner ?: run {
            dispatchState("BLE 검색 불가")
            return
        }
        dispatchState("Avinox 검색 중…")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = inspect(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::inspect)
            override fun onScanFailed(errorCode: Int) {
                main.post {
                    scanning = false
                    scannerCallback = null
                    dispatchState("BLE 검색 재시도 · $errorCode")
                    scheduleRetry()
                }
            }
        }
        scannerCallback = callback
        runCatching { scanner.startScan(callback) }.onFailure {
            scannerCallback = null
            dispatchState("BLE 검색 실패")
            scheduleRetry()
            return
        }
        scanning = true
        main.removeCallbacks(scanTimeout)
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @Suppress("MissingPermission")
    private fun inspect(result: ScanResult) {
        if (!started) return
        val record = result.scanRecord
        val name = record?.deviceName ?: if (hasConnectPermission()) runCatching { result.device.name }.getOrNull() else null
        val likely = name?.contains("avinox", ignoreCase = true) == true || record?.serviceUuids?.contains(FFF0_PARCEL) == true
        if (!likely) return
        main.post {
            if (!started || gatt != null) return@post
            stopScan()
            connect(result.device)
        }
    }

    @Suppress("MissingPermission")
    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        if (hasPermissions()) runCatching { adapter()?.bluetoothLeScanner?.stopScan(scannerCallback) }
        scanning = false
        scannerCallback = null
    }

    @Suppress("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!started || !hasConnectPermission()) return
        stopScan()
        val address = runCatching { device.address }.getOrNull()
        connectedAddress = address
        dispatchState("Avinox 연결 중…", address)
        val callback = gattCallback
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(app, false, callback)
        }
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @Suppress("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (!started) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    main.removeCallbacks(connectTimeout)
                    connectedAddress = runCatching { g.device.address }.getOrNull() ?: connectedAddress
                    dispatchState("Avinox 연결됨 · SOC 채널 준비", connectedAddress)
                    runCatching { g.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    main.removeCallbacks(connectTimeout)
                    if (gatt === g) gatt = null
                    runCatching { g.close() }
                    pendingSoc = null
                    pendingCount = 0
                    dispatchState("BLE 연결 끊김 · 재연결 중", connectedAddress)
                    scheduleRetry()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (!started || status != BluetoothGatt.GATT_SUCCESS) {
                    dispatchState("SOC 서비스 검색 실패", connectedAddress)
                    scheduleReconnect(g)
                    return@post
                }
                val ch = findSocCharacteristic(g.services)
                if (ch == null) {
                    dispatchState("Avinox SOC 채널 없음", connectedAddress)
                    scheduleReconnect(g)
                    return@post
                }
                subscribe(g, ch)
            }
        }

        @Deprecated("Deprecated callback before API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processNotification(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processNotification(characteristic, value)
        }

        @Deprecated("Deprecated callback before API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleGattRead(g, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleGattRead(g, characteristic, value, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic.uuid == FFF4) {
                main.post {
                    if (status == BluetoothGatt.GATT_SUCCESS) dispatchState("BLE 자동 · SOC 수신 대기", connectedAddress)
                    else dispatchState("SOC 구독 실패 · 재연결 중", connectedAddress)
                }
            }
        }
    }

    private fun handleGattRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) {
        if (!gattReadSweepActive) return
        if (status == BluetoothGatt.GATT_SUCCESS) gattReadSucceeded += 1
        listener.onGattRead(
            System.currentTimeMillis(),
            characteristic.service?.uuid ?: UUID(0L, 0L),
            characteristic.uuid,
            characteristic.properties,
            status,
            bytes.copyOf(),
            connectedAddress
        )
        main.post { readNextGattCharacteristic(g) }
    }

    private fun findSocCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? =
        services.firstOrNull { it.uuid == FFF0 }?.getCharacteristic(FFF4)

    @Suppress("MissingPermission", "DEPRECATION")
    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return
        val enabled = runCatching { g.setCharacteristicNotification(ch, true) }.getOrDefault(false)
        if (!enabled) {
            dispatchState("SOC 알림 활성화 실패", connectedAddress)
            scheduleReconnect(g)
            return
        }
        val descriptor = ch.getDescriptor(CCCD)
        if (descriptor == null) {
            dispatchState("SOC 구독 정보 없음", connectedAddress)
            scheduleReconnect(g)
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }
        if (!ok) {
            dispatchState("SOC 구독 시작 실패", connectedAddress)
            scheduleReconnect(g)
        }
    }

    private fun processNotification(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (characteristic.uuid != FFF4) return
        val receivedAt = System.currentTimeMillis()
        stateStore.setRawNotification(bytes, receivedAt)
        listener.onRawNotification(receivedAt, bytes.copyOf(), connectedAddress)
        val soc = parseVerifiedSoc(bytes) ?: return
        main.post {
            if (!started) return@post
            if (pendingSoc == soc) pendingCount += 1 else {
                pendingSoc = soc
                pendingCount = 1
            }
            if (pendingCount < 2) return@post
            dispatchState("BLE 자동 · 연결됨", connectedAddress)
            if (soc == lastDeliveredSoc) return@post
            lastDeliveredSoc = soc
            listener.onSoc(soc, System.currentTimeMillis(), connectedAddress)
        }
    }

    /** Accepts even a fragmented long packet because the verified SOC byte is within the first 12 bytes. */
    internal fun parseVerifiedSoc(bytes: ByteArray): Int? {
        if (bytes.size < 12) return null
        val prefix = intArrayOf(0x55, 0x4F, 0x04, 0x39, 0x05, 0x02)
        for (i in prefix.indices) if ((bytes[i].toInt() and 0xff) != prefix[i]) return null
        if ((bytes[9].toInt() and 0xff) != 0x57 || (bytes[10].toInt() and 0xff) != 0x09) return null
        return (bytes[11].toInt() and 0xff).takeIf { it in 0..100 }
    }

    @Suppress("MissingPermission")
    private fun scheduleReconnect(g: BluetoothGatt) {
        if (gatt === g) gatt = null
        if (hasConnectPermission()) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!started) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, RETRY_MS)
    }

    private fun dispatchState(state: String, address: String? = connectedAddress) {
        stateStore.setState(state, address)
        listener.onBleState(state, address)
    }
}
