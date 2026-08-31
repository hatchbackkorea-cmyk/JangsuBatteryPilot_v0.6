package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * v0.29.6 battery forensics store.
 *
 * Goals:
 * 1) Re-analyse every Avinox original proto already copied by Shizuku.
 * 2) Keep short, labelled diagnostic snapshots across days/restarts. A test does NOT need to be continuous.
 * 3) Never invent undecoded BMS values. Raw/unknown data is kept separately until validated.
 */
class BatteryForensicsStore(context: Context) {
    companion object {
        private const val PREFS = "battery_forensics_v1"
        private const val KEY_OPEN_SESSION = "open_session"
        private const val KEY_SESSION_STARTED = "session_started"
        private const val NOMINAL_WH = 800.0
    }

    data class HistorySummary(
        val protoFiles: Int,
        val parsedFiles: Int,
        val failedFiles: Int,
        val samples: Int,
        val distanceKm: Double,
        val consumedPct: Double,
        val chargedPct: Double,
        val observedChargeRises: Int,
        val minSoc: Double?,
        val maxSoc: Double?,
        val latestSoc: Double?,
        val minTempC: Double?,
        val maxTempC: Double?,
        val latestTempC: Double?,
        val latestTimestampMs: Long?,
    ) {
        val observedEquivalentCycles: Double get() = consumedPct / 100.0
        val observedDischargeWh: Double get() = consumedPct / 100.0 * NOMINAL_WH
        val observedChargeWh: Double get() = chargedPct / 100.0 * NOMINAL_WH
    }

    data class SessionStatus(
        val id: String?,
        val open: Boolean,
        val startedMs: Long,
        val snapshots: Int,
        val labels: List<String>
    )

    data class RawPacket(
        val timestampMs: Long,
        val bytes: ByteArray,
        val address: String? = null
    )

    data class CaptureWindow(
        val sessionId: String,
        val captureId: String,
        val label: String,
        val startedMs: Long
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "battery_forensics").apply { mkdirs() }

    fun analyzeExistingProto(): HistorySummary {
        val protoDir = File(app.filesDir, "avinox_proto")
        val files = protoDir.listFiles()?.filter { it.isFile && it.name.endsWith(".proto", true) }
            ?.sortedBy { it.lastModified() }.orEmpty()
        var parsed = 0
        var failed = 0
        var sampleCount = 0
        var distance = 0.0
        var consumed = 0.0
        var charged = 0.0
        var chargeRises = 0
        var minSoc: Double? = null
        var maxSoc: Double? = null
        var latestSoc: Double? = null
        var minTemp: Double? = null
        var maxTemp: Double? = null
        var latestTemp: Double? = null
        var latestTs: Long? = null

        for (file in files) {
            val ride = runCatching { AvinoxProtoParser.parse(file) }.getOrNull()
            if (ride == null) { failed += 1; continue }
            parsed += 1
            sampleCount += ride.samples.size
            distance += ride.header.distanceM / 1000.0
            consumed += ride.consumedSocPct()
            charged += ride.chargedSocPct()

            var previousSoc: Double? = null
            for (s in ride.samples) {
                val soc = s.batteryPct
                if (soc != null && soc in 0.0..100.0) {
                    minSoc = minSoc?.let { kotlin.math.min(it, soc) } ?: soc
                    maxSoc = maxSoc?.let { kotlin.math.max(it, soc) } ?: soc
                    val prev = previousSoc
                    if (prev != null && soc - prev >= 1.0) chargeRises += 1
                    previousSoc = soc
                    val ts = s.timestampMs
                    if (ts != null && (latestTs == null || ts >= latestTs!!)) {
                        latestTs = ts
                        latestSoc = soc
                    }
                }
                val temp = s.temperatureC
                if (temp != null && temp.isFinite() && temp in -30.0..100.0) {
                    minTemp = minTemp?.let { kotlin.math.min(it, temp) } ?: temp
                    maxTemp = maxTemp?.let { kotlin.math.max(it, temp) } ?: temp
                    val ts = s.timestampMs
                    if (ts != null && (latestTs == null || ts >= latestTs!!)) latestTemp = temp
                    else if (latestTemp == null) latestTemp = temp
                }
            }
        }
        return HistorySummary(
            protoFiles = files.size,
            parsedFiles = parsed,
            failedFiles = failed,
            samples = sampleCount,
            distanceKm = distance,
            consumedPct = consumed,
            chargedPct = charged,
            observedChargeRises = chargeRises,
            minSoc = minSoc,
            maxSoc = maxSoc,
            latestSoc = latestSoc,
            minTempC = minTemp,
            maxTempC = maxTemp,
            latestTempC = latestTemp,
            latestTimestampMs = latestTs
        )
    }

    fun startOrResumeSession(): SessionStatus {
        val current = prefs.getString(KEY_OPEN_SESSION, null)
        if (!current.isNullOrBlank()) return status()
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        sessionDir(id).mkdirs()
        prefs.edit().putString(KEY_OPEN_SESSION, id).putLong(KEY_SESSION_STARTED, now).apply()
        appendEvent(id, JSONObject().put("type", "SESSION_START").put("timestampMs", now))
        return status()
    }

    fun endSession(): SessionStatus {
        val id = prefs.getString(KEY_OPEN_SESSION, null)
        if (!id.isNullOrBlank()) appendEvent(id, JSONObject().put("type", "SESSION_END").put("timestampMs", System.currentTimeMillis()))
        prefs.edit().remove(KEY_OPEN_SESSION).remove(KEY_SESSION_STARTED).apply()
        return status()
    }

    fun status(): SessionStatus {
        val id = prefs.getString(KEY_OPEN_SESSION, null)
        val started = prefs.getLong(KEY_SESSION_STARTED, 0L)
        if (id.isNullOrBlank()) return SessionStatus(null, false, 0L, 0, emptyList())
        val file = eventFile(id)
        var snapshots = 0
        val labels = LinkedHashSet<String>()
        if (file.exists()) file.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val type = o.optString("type")
            if (type == "SNAPSHOT" || type == "CAPTURE_WINDOW_END") {
                snapshots += 1
                o.optString("label").takeIf { it.isNotBlank() }?.let(labels::add)
            }
        }
        return SessionStatus(id, true, started, snapshots, labels.toList())
    }

    fun capture(label: String, note: String = "", rawHexOverride: String? = null): SessionStatus {
        val s = startOrResumeSession()
        val id = s.id ?: return s
        val now = System.currentTimeMillis()
        val ble = AvinoxBleStateStore(app).snapshot()
        val raw = AvinoxBleStateStore(app).rawSnapshot()
        val charge = ChargingSessionStore(app).active()
        val actual = BatteryActualStore(app).latest()
        val obj = JSONObject()
            .put("type", "SNAPSHOT")
            .put("timestampMs", now)
            .put("label", label)
            .put("note", note)
            .put("soc", ble.soc ?: JSONObject.NULL)
            .put("bleState", ble.state)
            .put("bleAddress", ble.address ?: JSONObject.NULL)
            .put("bleUpdatedMs", ble.updatedMs)
            .put("rawFff4Hex", rawHexOverride ?: raw.hex ?: JSONObject.NULL)
            .put("rawFff4UpdatedMs", raw.updatedMs)
            .put("actualSoc", actual?.percent ?: JSONObject.NULL)
            .put("actualRouteKm", actual?.routeKm ?: JSONObject.NULL)
            .put("chargeActive", charge != null)
            .put("chargeArrivalPct", charge?.arrivalPct ?: JSONObject.NULL)
            .put("chargeTargetPct", charge?.targetPct ?: JSONObject.NULL)
            .put("chargeStartMs", charge?.startMs ?: JSONObject.NULL)
        appendEvent(id, obj)
        return status()
    }

    fun beginCaptureWindow(label: String, prePackets: List<RawPacket>): CaptureWindow? {
        val session = startOrResumeSession()
        val sessionId = session.id ?: return null
        val now = System.currentTimeMillis()
        val safeLabel = label.replace(Regex("[^0-9A-Za-z가-힣_-]"), "_").take(28).ifBlank { "capture" }
        val captureId = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + "_" + safeLabel
        val ble = AvinoxBleStateStore(app).snapshot()
        val charge = ChargingSessionStore(app).active()
        appendEvent(sessionId, JSONObject()
            .put("type", "CAPTURE_WINDOW_START")
            .put("captureId", captureId)
            .put("timestampMs", now)
            .put("label", label)
            .put("socStart", ble.soc ?: JSONObject.NULL)
            .put("bleState", ble.state)
            .put("bleAddress", ble.address ?: JSONObject.NULL)
            .put("chargeActiveStart", charge != null)
            .put("chargeArrivalPct", charge?.arrivalPct ?: JSONObject.NULL)
            .put("chargeTargetPct", charge?.targetPct ?: JSONObject.NULL)
            .put("prePacketCount", prePackets.size))
        prePackets.sortedBy { it.timestampMs }.forEach { appendRawPacket(sessionId, captureId, "PRE", it) }
        return CaptureWindow(sessionId, captureId, label, now)
    }

    fun appendCaptureRaw(window: CaptureWindow, phase: String, timestampMs: Long, bytes: ByteArray, address: String?) {
        appendRawPacket(window.sessionId, window.captureId, phase, RawPacket(timestampMs, bytes.copyOf(), address))
    }

    fun appendCaptureGattRead(
        window: CaptureWindow,
        timestampMs: Long,
        serviceUuid: String,
        characteristicUuid: String,
        properties: Int,
        status: Int,
        bytes: ByteArray,
        address: String?
    ) {
        val obj = JSONObject()
            .put("timestampMs", timestampMs)
            .put("serviceUuid", serviceUuid)
            .put("characteristicUuid", characteristicUuid)
            .put("properties", properties)
            .put("status", status)
            .put("length", bytes.size)
            .put("hex", bytes.toHex())
            .put("address", address ?: JSONObject.NULL)
        appendJsonLine(gattFile(window.sessionId, window.captureId), obj)
    }

    fun finishCaptureWindow(
        window: CaptureWindow,
        rawPacketCount: Int,
        packetLengthCounts: Map<Int, Int>,
        gattAttempted: Int,
        gattSucceeded: Int,
        note: String = ""
    ): SessionStatus {
        val now = System.currentTimeMillis()
        val ble = AvinoxBleStateStore(app).snapshot()
        val charge = ChargingSessionStore(app).active()
        val actual = BatteryActualStore(app).latest()
        val lengths = JSONObject()
        packetLengthCounts.toSortedMap().forEach { (length, count) -> lengths.put(length.toString(), count) }
        appendEvent(window.sessionId, JSONObject()
            .put("type", "CAPTURE_WINDOW_END")
            .put("captureId", window.captureId)
            .put("timestampMs", now)
            .put("startedMs", window.startedMs)
            .put("durationMs", (now - window.startedMs).coerceAtLeast(0L))
            .put("label", window.label)
            .put("note", note)
            .put("socEnd", ble.soc ?: JSONObject.NULL)
            .put("bleState", ble.state)
            .put("bleAddress", ble.address ?: JSONObject.NULL)
            .put("actualSoc", actual?.percent ?: JSONObject.NULL)
            .put("actualRouteKm", actual?.routeKm ?: JSONObject.NULL)
            .put("chargeActiveEnd", charge != null)
            .put("rawPacketCount", rawPacketCount)
            .put("packetLengthCounts", lengths)
            .put("gattAttempted", gattAttempted)
            .put("gattSucceeded", gattSucceeded))
        return status()
    }

    fun exportCurrentOrLatest(): File? {
        val id = prefs.getString(KEY_OPEN_SESSION, null) ?: latestSessionId() ?: return null
        val dir = sessionDir(id)
        if (!dir.exists()) return null
        writeHistorySummary(id, analyzeExistingProto())
        val outDir = File(app.cacheDir, "battery_forensics_exports").apply { mkdirs() }
        val zip = File(outDir, "battery_forensics_$id.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }

    fun latestSessionId(): String? = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("session_") }
        ?.maxByOrNull { it.lastModified() }?.name?.removePrefix("session_")

    private fun sessionDir(id: String) = File(root, "session_$id")
    private fun eventFile(id: String) = File(sessionDir(id), "snapshots.jsonl")

    @Synchronized
    private fun appendEvent(id: String, obj: JSONObject) {
        val dir = sessionDir(id).apply { mkdirs() }
        val f = File(dir, "snapshots.jsonl")
        f.appendText(obj.toString() + "\n")
        dir.setLastModified(System.currentTimeMillis())
    }

    private fun rawFile(sessionId: String, captureId: String) = File(sessionDir(sessionId), "capture_${captureId}_fff4.jsonl")
    private fun gattFile(sessionId: String, captureId: String) = File(sessionDir(sessionId), "capture_${captureId}_gatt.jsonl")

    private fun appendRawPacket(sessionId: String, captureId: String, phase: String, packet: RawPacket) {
        val obj = JSONObject()
            .put("timestampMs", packet.timestampMs)
            .put("phase", phase)
            .put("length", packet.bytes.size)
            .put("hex", packet.bytes.toHex())
            .put("address", packet.address ?: JSONObject.NULL)
        appendJsonLine(rawFile(sessionId, captureId), obj)
    }

    @Synchronized
    private fun appendJsonLine(file: File, obj: JSONObject) {
        file.parentFile?.mkdirs()
        file.appendText(obj.toString() + "\n")
        file.parentFile?.setLastModified(System.currentTimeMillis())
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun writeHistorySummary(id: String, s: HistorySummary) {
        val o = JSONObject()
            .put("generatedMs", System.currentTimeMillis())
            .put("source", "local Avinox cloud_ride_rec_*.proto already synced by Shizuku")
            .put("protoFiles", s.protoFiles)
            .put("parsedFiles", s.parsedFiles)
            .put("failedFiles", s.failedFiles)
            .put("samples", s.samples)
            .put("distanceKm", s.distanceKm)
            .put("consumedPct", s.consumedPct)
            .put("chargedPct", s.chargedPct)
            .put("observedChargeRises", s.observedChargeRises)
            .put("observedEquivalentCycles", s.observedEquivalentCycles)
            .put("observedDischargeWh800", s.observedDischargeWh)
            .put("observedChargeWh800", s.observedChargeWh)
            .put("minSoc", s.minSoc ?: JSONObject.NULL)
            .put("maxSoc", s.maxSoc ?: JSONObject.NULL)
            .put("latestSoc", s.latestSoc ?: JSONObject.NULL)
            .put("minTempC", s.minTempC ?: JSONObject.NULL)
            .put("maxTempC", s.maxTempC ?: JSONObject.NULL)
            .put("latestTempC", s.latestTempC ?: JSONObject.NULL)
            .put("latestTimestampMs", s.latestTimestampMs ?: JSONObject.NULL)
            .put("bmsCycleCount", JSONObject.NULL)
            .put("soh", JSONObject.NULL)
            .put("packVoltage", JSONObject.NULL)
            .put("packCurrent", JSONObject.NULL)
            .put("cellVoltages", JSONObject.NULL)
            .put("balancing", JSONObject.NULL)
            .put("chargeLimit", JSONObject.NULL)
            .put("protectionFlags", JSONObject.NULL)
            .put("batteryFirmware", JSONObject.NULL)
            .put("batterySerial", JSONObject.NULL)
            .put("leftSwitchBattery", JSONObject.NULL)
            .put("rightSwitchBattery", JSONObject.NULL)
        val dir = sessionDir(id).apply { mkdirs() }
        File(dir, "avinox_history_summary.json").writeText(o.toString(2))
    }
}
