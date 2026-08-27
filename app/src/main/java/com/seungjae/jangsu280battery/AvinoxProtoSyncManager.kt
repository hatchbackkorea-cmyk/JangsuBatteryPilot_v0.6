package com.seungjae.jangsu280battery

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class AvinoxProtoSyncManager(context: Context) {
    companion object {
        const val PERMISSION_REQUEST = 8260
        private const val PREFS = "avinox_proto_sync_v1"
        private const val KEY_SEEN = "seen"
        private const val ROOT = AvinoxFileUserService.ROOT
        private const val MAX_COPY_BYTES = 64L * 1024L * 1024L
        private const val CHUNK = 192 * 1024
        private val syncing = AtomicBoolean(false)
    }

    data class SyncResult(val discovered: Int, val imported: Int, val samples: Int, val skipped: Int, val failed: Int, val message: String)
    private data class RemoteFile(val path: String, val size: Long, val modified: Long) {
        val key: String get() = "$path|$size"
        val name: String get() = path.substringAfterLast('/')
        val rideId: Long get() = name.substringAfterLast('_').substringBefore(".proto").toLongOrNull() ?: 0L
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun binderReady(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun permissionGranted(): Boolean = binderReady() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun canAutoSync(): Boolean = permissionGranted()
    fun requestPermission(): Boolean {
        if (!binderReady()) return false
        if (permissionGranted()) return true
        return runCatching { Shizuku.requestPermission(PERMISSION_REQUEST); true }.getOrDefault(false)
    }

    fun statusText(): String {
        val protoCount = HistoricalRideStore(app).records().count { it.sourceType == HistoricalSourceType.PROTO }
        return when {
            !binderReady() -> "Avinox 원본 자동동기화 · Shizuku가 실행되지 않음"
            !permissionGranted() -> "Avinox 원본 자동동기화 · Shizuku 권한 필요"
            else -> "Avinox 원본 자동동기화 준비됨 · A+ 원본 ${protoCount}개 학습"
        }
    }

    fun syncAsync(maxFiles: Int = 8, onDone: (SyncResult) -> Unit) {
        if (!permissionGranted()) {
            onDone(SyncResult(0,0,0,0,1,"Shizuku 실행/권한을 확인해 주세요.")); return
        }
        if (!syncing.compareAndSet(false, true)) {
            onDone(SyncResult(0,0,0,0,0,"Avinox 원본 동기화가 이미 진행 중입니다.")); return
        }
        val args = Shizuku.UserServiceArgs(ComponentName(app, AvinoxFileUserService::class.java))
            .processNameSuffix("avinox_proto").daemon(false).tag("avinox_proto_v1").version(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Thread {
                    val result = runCatching { syncNow(IAvinoxFileService.Stub.asInterface(service), maxFiles) }
                        .getOrElse { e -> SyncResult(0,0,0,0,1,"Avinox 원본 동기화 실패 · ${e.message ?: e.javaClass.simpleName}") }
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    syncing.set(false)
                    onDone(result)
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { e ->
            syncing.set(false); onDone(SyncResult(0,0,0,0,1,"Shizuku 서비스 연결 실패 · ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun syncNow(service: IAvinoxFileService, maxFiles: Int): SyncResult {
        val remote = service.listProtoFiles(ROOT).mapNotNull { line ->
            val p = line.split('\t'); if (p.size < 3) null else RemoteFile(p[0], p[1].toLongOrNull() ?: return@mapNotNull null, p[2].toLongOrNull() ?: 0L)
        }.filter { it.size in 256..MAX_COPY_BYTES }.sortedBy { it.rideId }
        val seen = (prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()).toMutableSet()
        val fresh = remote.filter { it.key !in seen }.takeLast(maxFiles.coerceAtLeast(1))
        if (fresh.isEmpty()) return SyncResult(remote.size,0,0,remote.size,0,"새 Avinox 원본 없음 · 동기화 완료")

        val dir = File(app.filesDir, "avinox_proto").apply { mkdirs() }
        val learning = BatteryLearningStore(app)
        val records = HistoricalRideStore(app)
        var imported=0; var learned=0; var failed=0
        for (rf in fresh) {
            val ok = runCatching {
                val part = File(dir, rf.name + ".part")
                val dest = File(dir, rf.name)
                FileOutputStream(part, false).use { out ->
                    var offset=0L
                    while (offset < rf.size) {
                        val data = service.readChunk(rf.path, offset, minOf(CHUNK.toLong(), rf.size-offset).toInt())
                        require(data.isNotEmpty()) { "원본 읽기가 중단됨: ${rf.name}" }
                        out.write(data); offset += data.size
                    }
                }
                require(part.length() == rf.size) { "원본 크기 검증 실패" }
                if (dest.exists()) dest.delete()
                require(part.renameTo(dest)) { "원본 저장 실패" }
                val hash = sha256(dest)
                val ride = AvinoxProtoParser.parse(dest)
                val entries = ride.batteryEntries()
                val windows = ride.assistWindows()
                val telemetry = ride.telemetry()
                val course = ride.course()
                val sessionId = "proto_${ride.header.rideId}_${ride.header.startUnixSec}"
                val count = if (learning.hasSession(sessionId)) 0 else learning.trainModeSeparatedRide(sessionId, course, entries, telemetry, windows, ride.qualityScore)
                val duration = (ride.header.endUnixSec - ride.header.startUnixSec).coerceAtLeast(0)
                val avgSpeed = duration.takeIf { it > 0 }?.let { course.totalKm / (it / 3600.0) }
                records.add(HistoricalRideRecord(
                    id=sessionId, fileHash=hash, fileName=rf.name, sourceType=HistoricalSourceType.PROTO,
                    importedAtMs=System.currentTimeMillis(), distanceKm=course.totalKm, ascentM=ride.header.ascentM,
                    descentM=ride.header.descentM, durationSec=duration, usedBatteryPct=ride.consumedSocPct(),
                    avgSpeedKph=avgSpeed, sampleCount=count, telemetryPointCount=telemetry.size,
                    dataQualityScore=ride.qualityScore, originalStored=true
                ))
                RideInsightStore(app).analyzeAndStore(HistoricalRideAnalysis(
                    sourceType=HistoricalSourceType.PROTO, displayName=rf.name, fileHash=hash, course=course,
                    durationSec=duration, avgSpeedKph=avgSpeed, telemetry=telemetry, dataQualityScore=ride.qualityScore,
                    warnings=ride.warnings, timestampMs=ride.header.startUnixSec * 1000L
                ))
                learned += count; imported += 1; seen += rf.key
            }.isSuccess
            if (!ok) failed++
        }
        prefs.edit().putStringSet(KEY_SEEN, seen.toList().takeLast(500).toSet()).apply()
        val msg = when {
            imported>0 -> "Avinox 원본 ${imported}개 A+ 동기화 · 학습 ${learned}구간${if(failed>0) " · 실패 $failed" else ""}"
            failed>0 -> "Avinox 원본 ${failed}개 분석 실패 · 원본 형식/권한 확인 필요"
            else -> "새 Avinox 원본 없음"
        }
        return SyncResult(remote.size, imported, learned, remote.size-fresh.size, failed, msg)
    }

    fun clearHistory() {
        prefs.edit().clear().apply()
        runCatching { File(app.filesDir, "avinox_proto").deleteRecursively() }
    }

    private fun sha256(file: File): String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf=ByteArray(64*1024); while(true){ val n=input.read(buf); if(n<=0) break; md.update(buf,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
