from pathlib import Path
P=Path('app/src/main/java/com/seungjae/jangsu280battery')
def rep(file,old,new,count=1):
 p=P/file;s=p.read_text();assert s.count(old)==count,(file,s.count(old),old[:100]);p.write_text(s.replace(old,new))
rep('RaceTimingService.kt','''        if (state == "ARMED" && finishDisplay != null && SystemClock.elapsedRealtime()<finishDisplayUntil) {
            store.writeSnapshot(finishDisplay!!);return
        }
''','')
rep('RaceTimingService.kt','''            finishDisplay = store.snapshot()
            finishDisplayUntil = SystemClock.elapsedRealtime()+NEXT_LAP_REARM_DELAY_MS''','''            RaceFairTiming.markFinalized(this, runId)''')
rep('RaceTimingService.kt','    private var finishDisplay: RaceDataStore.Snapshot? = null\n    private var finishDisplayUntil = 0L\n','')
rep('RaceTimingService.kt','        if (!nextLap) { clockOffsetMs=null; finishDisplay=null }','        if (!nextLap) clockOffsetMs=null')
p=P/'RaceTimingService.kt';s=p.read_text();assert s.count('put("started_at_ms",startAt)')==1
s=s.replace('put("started_at_ms",startAt)','put("started_at_ms",preliminaryStartAt.takeIf { it > 0L } ?: startAt)')
assert s.count('put("started_at_ms", startAt)')==2
s=s.replace('put("started_at_ms", startAt)','put("started_at_ms", preliminaryStartAt.takeIf { it > 0L } ?: startAt)');p.write_text(s)
rep('RaceFairTiming.kt','    fun clearPending(context:Context)=pending(context).delete()','''    fun clearPending(context:Context)=pending(context).delete()
    fun markFinalized(context: Context, runId: String) {
        context.getSharedPreferences("race_fair_display",Context.MODE_PRIVATE).edit()
            .putString("run",runId).putLong("at",android.os.SystemClock.elapsedRealtime()).apply()
    }
    fun justFinalized(context: Context): String? {
        val p=context.getSharedPreferences("race_fair_display",Context.MODE_PRIVATE)
        val age=android.os.SystemClock.elapsedRealtime()-p.getLong("at",0L)
        return if (age in 0L..1500L) p.getString("run",null) else null
    }''')
rep('RaceLiveLapDisplayInstaller.kt','''        val verifying = snapshot.state == "FINISHED" && snapshot.serverStatus.contains("랩타임 확인중")''','''        val heldId = if (snapshot.state == "ARMED") RaceFairTiming.justFinalized(activity) else null
        val heldIndex = if (heldId == null) -1 else sessionRuns.indexOfFirst { it.runId == heldId }
        if (heldIndex >= 0) {
            val held = sessionRuns[heldIndex]
            setBlock(currentBlock, heldIndex + 1, held.elapsedMs)
            val before = sessionRuns.getOrNull(heldIndex - 1)
            setBlock(previousBlock, if (before == null) null else heldIndex, before?.elapsedMs)
            return
        }
        val verifying = snapshot.state == "FINISHED" && snapshot.serverStatus.contains("랩타임 확인중")''')
# Timing service remains owner while a durable finish is pending, not discovery.
rep('RaceAutoLapDiscoveryService.kt','val timingActive = existing.state == "ARMED" || existing.state == "RUNNING"','val timingActive = existing.state == "ARMED" || existing.state == "RUNNING" || RaceFairTiming.readPending(this) != null')
rep('RaceAutoLapDiscoveryService.kt','        if (runtime.state == "ARMED" || runtime.state == "RUNNING") {','        if (runtime.state == "ARMED" || runtime.state == "RUNNING" || RaceFairTiming.readPending(this) != null) {')
