package com.seungjae.jangsu280battery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

object UsbH264ChaseRuntime {
    @Volatile var streaming: Boolean = false
    @Volatile var status: String = "USB H.264 대기"
}

/**
 * Direct UVC H.264 CHASE source.
 *
 * The Action camera performs AVC encoding. TimeGate only removes UVC payload headers, preserves the
 * compressed AVC access units, extracts SPS/PPS/IDR markers and forwards those encoded bytes into
 * the existing CameraGate WebRTC transport. There is no decode -> re-encode step.
 */
class UsbH264ChaseSource(context: Context) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var receiverRegistered = false
    private var currentDeviceName: String? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var reader: UsbH264NativeReader? = null
    private var profile: H264Profile? = null
    private var latestSps: ByteArray? = null
    private var latestPps: ByteArray? = null
    @Volatile private var lastFrameElapsedMs = 0L
    @Volatile private var onlineAnnounced = false

    private val permissionAction = app.packageName + ".USB_H264_CHASE_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                permissionAction -> {
                    val device = usbDevice(intent) ?: return
                    if (usb.hasPermission(device)) startDevice(device)
                    else publishStatus("USB 카메라 권한이 허용되지 않았습니다.", toast = true)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (running.get()) main.postDelayed({ connectIfPresent() }, 300L)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = usbDevice(intent)
                    if (device != null && device.deviceName == currentDeviceName) {
                        publishStatus("USB 액션캠 분리 · CHASE 제외", toast = true)
                        stopStream(markOffline = true)
                    }
                }
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        registerReceiver()
        CameraGateBroadcastBridge.setExternalSourceRequired(true)
        CameraGateBroadcastBridge.setExternalAvailable(false)
        connectIfPresent()
        main.post(watchdog)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        main.removeCallbacks(watchdog)
        stopStream(markOffline = true)
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        worker.shutdownNow()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (onlineAnnounced && lastFrameElapsedMs > 0L && SystemClock.elapsedRealtime() - lastFrameElapsedMs > 2_200L) {
                publishStatus("USB H.264 프레임 중단 · CHASE 제외", toast = true)
                stopStream(markOffline = true)
            }
            if (running.get()) main.postDelayed(this, 900L)
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun connectIfPresent() {
        if (!running.get() || connection != null) return
        val device = usb.deviceList.values
            .filter(::isUvcDevice)
            .sortedWith(compareByDescending<UsbDevice> { looksLikeDji(it) }.thenBy { it.deviceName })
            .firstOrNull()
        if (device == null) {
            publishStatus("USB H.264 액션캠 연결 대기")
            return
        }
        currentDeviceName = device.deviceName
        if (!usb.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(app, 5140, Intent(permissionAction).setPackage(app.packageName), flags)
            usb.requestPermission(device, pi)
            publishStatus("USB 액션캠 권한 확인 중…")
            return
        }
        startDevice(device)
    }

    private fun startDevice(device: UsbDevice) {
        if (!running.get()) return
        worker.execute {
            runCatching { openAndStream(device) }
                .onFailure { error ->
                    main.post {
                        if (!running.get()) return@post
                        publishStatus("USB H.264 연결 실패 · ${error.message ?: error.javaClass.simpleName}", toast = true)
                        stopStream(markOffline = true)
                    }
                }
        }
    }

    private fun openAndStream(device: UsbDevice) {
        if (!running.get() || connection != null) return
        val conn = usb.openDevice(device) ?: error("USB 장치를 열 수 없습니다")
        val parsed = parseH264Profiles(conn.rawDescriptors)
        val chosen = chooseProfile(parsed.profiles) ?: run {
            conn.close()
            error("UVC H.264 720p/1080p 프로필을 찾지 못했습니다")
        }
        val base = interfaces(device).firstOrNull { it.id == chosen.interfaceId && it.alternateSetting == 0 }
            ?: run { conn.close(); error("VideoStreaming 인터페이스를 찾지 못했습니다") }
        check(conn.claimInterface(base, true)) { "VideoStreaming 인터페이스 claim 실패" }

        val probe = negotiate(conn, chosen)
        val maxPayload = if (probe.size >= 26) le32(probe, 22).toInt().coerceAtLeast(0) else 0
        val streamAlt = chooseStreamingAlt(device, parsed, chosen, maxPayload)
            ?: run {
                conn.releaseInterface(base)
                conn.close()
                error("H.264 IN endpoint를 찾지 못했습니다")
            }
        check(conn.setInterface(streamAlt.iface)) { "USB alternate setting 전환 실패" }

        val nativeReader = UsbH264NativeReader(
            onFrame = ::onNativeFrame,
            onState = ::onNativeState,
        )
        val transferBytes = when (streamAlt.endpoint.type) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> maxPayload.takeIf { it > 0 } ?: 64 * 1024
            else -> streamAlt.capacity
        }.coerceIn(1024, 1024 * 1024)
        val packetSize = streamAlt.capacity.coerceAtLeast(streamAlt.endpoint.maxPacketSize).coerceAtLeast(256)
        check(
            nativeReader.start(
                fd = conn.fileDescriptor,
                endpointAddress = streamAlt.endpoint.address,
                endpointType = streamAlt.endpoint.type,
                packetSize = packetSize,
                transferBytes = transferBytes,
                packetsPerUrb = 32,
            )
        ) { "Native USB H.264 reader 시작 실패" }

        connection = conn
        claimedInterface = base
        reader = nativeReader
        profile = chosen
        currentDeviceName = device.deviceName
        latestSps = null
        latestPps = null
        onlineAnnounced = false
        lastFrameElapsedMs = 0L
        publishStatus(
            "USB H.264 준비 · ${chosen.width}×${chosen.height} ${chosen.fps}fps · " +
                if (streamAlt.endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) "ISO" else "BULK"
        )
    }

    private fun negotiate(conn: UsbDeviceConnection, p: H264Profile): ByteArray {
        val lenBuf = ByteArray(2)
        val gotLen = conn.controlTransfer(REQ_IN, GET_LEN, VS_PROBE shl 8, p.interfaceId, lenBuf, 2, 1200)
        val controlLength = if (gotLen >= 2) le16(lenBuf, 0).coerceIn(26, 64) else 34
        val probe = ByteArray(controlLength)
        conn.controlTransfer(REQ_IN, GET_CUR, VS_PROBE shl 8, p.interfaceId, probe, probe.size, 1200)
        putLe16(probe, 0, 1)
        probe[2] = p.formatIndex.toByte()
        probe[3] = p.frameIndex.toByte()
        putLe32(probe, 4, p.interval100ns)
        check(conn.controlTransfer(REQ_OUT, SET_CUR, VS_PROBE shl 8, p.interfaceId, probe, probe.size, 1500) >= 0) {
            "UVC PROBE SET 실패"
        }
        val negotiated = ByteArray(controlLength)
        check(conn.controlTransfer(REQ_IN, GET_CUR, VS_PROBE shl 8, p.interfaceId, negotiated, negotiated.size, 1500) >= 0) {
            "UVC PROBE GET 실패"
        }
        check(conn.controlTransfer(REQ_OUT, SET_CUR, VS_COMMIT shl 8, p.interfaceId, negotiated, negotiated.size, 1500) >= 0) {
            "UVC COMMIT 실패"
        }
        return negotiated
    }

    private fun chooseStreamingAlt(
        device: UsbDevice,
        parsed: DescriptorResult,
        profile: H264Profile,
        maxPayload: Int,
    ): StreamAlt? {
        val endpointAddress = profile.endpointAddress
        val candidates = interfaces(device).mapNotNull { iface ->
            if (iface.id != profile.interfaceId) return@mapNotNull null
            val endpoint = (0 until iface.endpointCount).map(iface::getEndpoint).firstOrNull { ep ->
                ep.direction == UsbConstants.USB_DIR_IN &&
                    (endpointAddress == 0 || ep.address == endpointAddress) &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
            } ?: return@mapNotNull null
            val rawCapacity = parsed.endpointCapacity[EndpointKey(iface.id, iface.alternateSetting, endpoint.address)]
            StreamAlt(iface, endpoint, rawCapacity ?: endpoint.maxPacketSize)
        }
        candidates.firstOrNull { it.endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK }?.let { return it }
        val iso = candidates.filter { it.endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC }
        if (iso.isEmpty()) return null
        if (maxPayload > 0) iso.filter { it.capacity >= maxPayload }.minByOrNull { it.capacity }?.let { return it }
        return iso.maxByOrNull { it.capacity }
    }

    private fun onNativeState(code: Int, message: String) {
        if (code == 1) {
            publishStatus(message)
            return
        }
        if (code < 0 && running.get()) {
            main.post {
                if (!running.get()) return@post
                publishStatus("$message · CHASE 제외", toast = true)
                stopStream(markOffline = true)
            }
        }
    }

    private fun onNativeFrame(raw: ByteArray, ptsUs: Long, sequence: Int) {
        if (!running.get()) return
        val p = profile ?: return
        val frame = normalizeAvc(raw) ?: return
        val nals = annexBNals(frame)
        if (nals.isEmpty()) return

        nals.firstOrNull { it.type == 7 }?.bytes?.let { sps ->
            if (!sps.contentEquals(latestSps)) {
                latestSps = sps
                CameraGateBroadcastBridge.offerExternal(codecPacket(CameraGateBroadcastStreamer.KIND_CSD0, sps, p))
            }
        }
        nals.firstOrNull { it.type == 8 }?.bytes?.let { pps ->
            if (!pps.contentEquals(latestPps)) {
                latestPps = pps
                CameraGateBroadcastBridge.offerExternal(codecPacket(CameraGateBroadcastStreamer.KIND_CSD1, pps, p))
            }
        }
        val key = nals.any { it.type == 5 }
        lastFrameElapsedMs = SystemClock.elapsedRealtime()
        CameraGateBroadcastBridge.offerExternal(
            CameraGateBroadcastPacket(
                kind = CameraGateBroadcastStreamer.KIND_FRAME,
                ptsUs = ptsUs,
                flags = if (key) CameraGateBroadcastStreamer.KEY_FRAME_FLAG else 0,
                data = frame,
                width = p.width,
                height = p.height,
                mime = "video/avc",
                fps = p.fps,
                bitrate = p.maxBitrate.coerceAtLeast(4_000_000),
            )
        )
        if (!onlineAnnounced && key && latestSps != null && latestPps != null) {
            onlineAnnounced = true
            UsbH264ChaseRuntime.streaming = true
            CameraGateBroadcastBridge.setExternalAvailable(true)
            publishStatus("USB H.264 LIVE · ${p.width}×${p.height} ${p.fps}fps", toast = true)
        }
    }

    private fun codecPacket(kind: Int, bytes: ByteArray, p: H264Profile) = CameraGateBroadcastPacket(
        kind = kind,
        ptsUs = 0L,
        flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
        data = bytes,
        width = p.width,
        height = p.height,
        mime = "video/avc",
        fps = p.fps,
        bitrate = p.maxBitrate.coerceAtLeast(4_000_000),
    )

    private fun stopStream(markOffline: Boolean) {
        reader?.stop()
        reader = null
        val conn = connection
        claimedInterface?.let { iface -> runCatching { conn?.releaseInterface(iface) } }
        claimedInterface = null
        runCatching { conn?.close() }
        connection = null
        profile = null
        latestSps = null
        latestPps = null
        lastFrameElapsedMs = 0L
        onlineAnnounced = false
        if (markOffline) {
            UsbH264ChaseRuntime.streaming = false
            CameraGateBroadcastBridge.setExternalAvailable(false)
        }
    }

    private fun publishStatus(value: String, toast: Boolean = false) {
        UsbH264ChaseRuntime.status = value
        if (toast) main.post { Toast.makeText(app, value, Toast.LENGTH_LONG).show() }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean = interfaces(device).any {
        it.interfaceClass == UsbConstants.USB_CLASS_VIDEO
    }

    private fun looksLikeDji(device: UsbDevice): Boolean {
        val name = runCatching { "${device.manufacturerName} ${device.productName}" }.getOrDefault("")
        return name.contains("DJI", ignoreCase = true) || name.contains("Osmo", ignoreCase = true)
    }

    private fun interfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map(device::getInterface)

    private data class Nal(val type: Int, val bytes: ByteArray)

    private fun normalizeAvc(input: ByteArray): ByteArray? {
        if (input.size < 5) return null
        if (startCodeAt(input, 0) > 0 || findStartCode(input, 0) >= 0) return input
        var pos = 0
        val out = ArrayList<Byte>(input.size + 64)
        while (pos + 4 <= input.size) {
            val n = ((input[pos].toInt() and 0xff) shl 24) or
                ((input[pos + 1].toInt() and 0xff) shl 16) or
                ((input[pos + 2].toInt() and 0xff) shl 8) or
                (input[pos + 3].toInt() and 0xff)
            pos += 4
            if (n <= 0 || pos + n > input.size) return null
            out.add(0); out.add(0); out.add(0); out.add(1)
            for (i in 0 until n) out.add(input[pos + i])
            pos += n
        }
        if (pos != input.size || out.isEmpty()) return null
        return ByteArray(out.size) { out[it] }
    }

    private fun annexBNals(data: ByteArray): List<Nal> {
        val result = ArrayList<Nal>()
        var start = findStartCode(data, 0)
        while (start >= 0) {
            val sc = startCodeAt(data, start)
            if (sc == 0 || start + sc >= data.size) break
            val next = findStartCode(data, start + sc)
            val end = if (next >= 0) next else data.size
            val type = data[start + sc].toInt() and 0x1f
            if (end > start + sc) result += Nal(type, data.copyOfRange(start, end))
            start = next
        }
        return result
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i + 3 < data.size) {
            if (startCodeAt(data, i) > 0) return i
            i++
        }
        return -1
    }

    private fun startCodeAt(data: ByteArray, i: Int): Int {
        if (i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return 4
        if (i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return 3
        return 0
    }

    private data class H264Profile(
        val interfaceId: Int,
        val endpointAddress: Int,
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val interval100ns: Int,
        val fps: Int,
        val maxBitrate: Int,
    )

    private data class EndpointKey(val interfaceId: Int, val alt: Int, val address: Int)
    private data class DescriptorResult(
        val profiles: List<H264Profile>,
        val endpointCapacity: Map<EndpointKey, Int>,
    )
    private data class StreamAlt(val iface: UsbInterface, val endpoint: UsbEndpoint, val capacity: Int)

    private fun parseH264Profiles(raw: ByteArray): DescriptorResult {
        val profiles = ArrayList<H264Profile>()
        val caps = HashMap<EndpointKey, Int>()
        val endpointByInterface = HashMap<Int, Int>()
        var offset = 0
        var iface = -1
        var alt = 0
        var ifaceClass = -1
        var ifaceSubClass = -1
        var h264Format = -1

        while (offset + 2 <= raw.size) {
            val len = raw[offset].toInt() and 0xff
            if (len < 2 || offset + len > raw.size) break
            val type = raw[offset + 1].toInt() and 0xff
            when (type) {
                0x04 -> if (len >= 9) {
                    iface = raw[offset + 2].toInt() and 0xff
                    alt = raw[offset + 3].toInt() and 0xff
                    ifaceClass = raw[offset + 5].toInt() and 0xff
                    ifaceSubClass = raw[offset + 6].toInt() and 0xff
                    h264Format = -1
                }
                0x05 -> if (len >= 7 && iface >= 0) {
                    val address = raw[offset + 2].toInt() and 0xff
                    val wMax = le16(raw, offset + 4)
                    val base = wMax and 0x7ff
                    val transactions = ((wMax shr 11) and 0x3) + 1
                    caps[EndpointKey(iface, alt, address)] = base * transactions
                }
                0x24 -> if (ifaceClass == UsbConstants.USB_CLASS_VIDEO && ifaceSubClass == 2 && alt == 0 && len >= 4) {
                    val subType = raw[offset + 2].toInt() and 0xff
                    when (subType) {
                        0x01 -> if (len >= 7) endpointByInterface[iface] = raw[offset + 6].toInt() and 0xff
                        0x13 -> h264Format = raw[offset + 3].toInt() and 0xff
                        0x10 -> {
                            h264Format = if (len >= 28 && isH264Guid(raw, offset + 5)) raw[offset + 3].toInt() and 0xff else -1
                        }
                        0x04, 0x06, 0x0a, 0x0c, 0x12, 0x15, 0x16 -> h264Format = -1
                        0x11, 0x14 -> if (h264Format > 0 && len >= 26) {
                            val frameIndex = raw[offset + 3].toInt() and 0xff
                            val width = le16(raw, offset + 5)
                            val height = le16(raw, offset + 7)
                            val maxBitrate = le32(raw, offset + 13).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            val defaultInterval = le32(raw, offset + 17).toInt()
                            val intervalCount = raw[offset + 21].toInt() and 0xff
                            val intervals = ArrayList<Int>()
                            if (intervalCount > 0 && len >= 26 + intervalCount * 4) {
                                for (i in 0 until intervalCount) {
                                    val v = le32(raw, offset + 26 + i * 4).toInt()
                                    if (v > 0) intervals += v
                                }
                            }
                            if (intervals.isEmpty() && defaultInterval > 0) intervals += defaultInterval
                            for (interval in intervals) {
                                val fps = (10_000_000.0 / interval.toDouble()).roundToInt().coerceAtLeast(1)
                                profiles += H264Profile(
                                    interfaceId = iface,
                                    endpointAddress = endpointByInterface[iface] ?: 0,
                                    formatIndex = h264Format,
                                    frameIndex = frameIndex,
                                    width = width,
                                    height = height,
                                    interval100ns = interval,
                                    fps = fps,
                                    maxBitrate = maxBitrate,
                                )
                            }
                        }
                    }
                }
            }
            offset += len
        }
        return DescriptorResult(profiles.distinct(), caps)
    }

    private fun chooseProfile(items: List<H264Profile>): H264Profile? {
        if (items.isEmpty()) return null
        return items.minByOrNull { p ->
            val resolutionPenalty = (abs(p.width - 1280) + abs(p.height - 720)) * 100_000L
            val fpsPenalty = abs(p.fps - 30) * 1_000L
            resolutionPenalty + fpsPenalty
        }
    }

    private fun isH264Guid(data: ByteArray, offset: Int): Boolean {
        if (offset + H264_GUID.size > data.size) return false
        for (i in H264_GUID.indices) if (data[offset + i] != H264_GUID[i]) return false
        return true
    }

    private fun usbDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun le16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun le32(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return (data[offset].toLong() and 0xffL) or
            ((data[offset + 1].toLong() and 0xffL) shl 8) or
            ((data[offset + 2].toLong() and 0xffL) shl 16) or
            ((data[offset + 3].toLong() and 0xffL) shl 24)
    }

    private fun putLe16(data: ByteArray, offset: Int, value: Int) {
        if (offset + 1 >= data.size) return
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putLe32(data: ByteArray, offset: Int, value: Int) {
        if (offset + 3 >= data.size) return
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value ushr 8) and 0xff).toByte()
        data[offset + 2] = ((value ushr 16) and 0xff).toByte()
        data[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    companion object {
        private const val REQ_OUT = 0x21
        private const val REQ_IN = 0xA1
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_LEN = 0x85
        private const val VS_PROBE = 0x01
        private const val VS_COMMIT = 0x02
        private val H264_GUID = byteArrayOf(
            0x48, 0x32, 0x36, 0x34,
            0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(),
            0x00, 0x38, 0x9B.toByte(), 0x71,
        )
    }
}
