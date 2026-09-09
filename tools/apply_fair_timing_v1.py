from pathlib import Path
PKG='app/src/main/java/com/seungjae/jangsu280battery/'
def rep(name,old,new,count=1):
 p=Path(PKG+name if name.endswith('.kt') and '/' not in name else name)
 s=p.read_text(); assert s.count(old)==count,(str(p),old[:100],s.count(old),count)
 p.write_text(s.replace(old,new))
rep('RaceProtocol.kt','val leaderElapsedMs: Long? = null\n)', 'val leaderElapsedMs: Long? = null,\n    val fairPolicyJson: String = ""\n)')
rep('RaceProtocol.kt','leaderElapsedMs?.let { put("leader_elapsed_ms", it) }','leaderElapsedMs?.let { put("leader_elapsed_ms", it) }\n        if (fairPolicyJson.isNotBlank()) put("fair_policy", JSONObject(fairPolicyJson))')
rep('RaceProtocol.kt','leaderElapsedMs = if (o.has("leader_elapsed_ms") && !o.isNull("leader_elapsed_ms")) o.optLong("leader_elapsed_ms") else null','leaderElapsedMs = if (o.has("leader_elapsed_ms") && !o.isNull("leader_elapsed_ms")) o.optLong("leader_elapsed_ms") else null,\n                fairPolicyJson = o.optJSONObject("fair_policy")?.toString().orEmpty()')
rep('RaceProtocol.kt','val maxOffRouteM: Double\n)', 'val maxOffRouteM: Double,\n    val timingJson: String = ""\n)')
rep('RaceProtocol.kt','put("max_gps_accuracy_m", maxGpsAccuracyM); put("max_off_route_m", maxOffRouteM)','put("max_gps_accuracy_m", maxGpsAccuracyM); put("max_off_route_m", maxOffRouteM)\n        if (timingJson.isNotBlank()) put("timing", JSONObject(timingJson))')
rep('RaceProtocol.kt','o.optDouble("max_speed_kph", 0.0), o.optDouble("max_gps_accuracy_m", 0.0), o.optDouble("max_off_route_m", 0.0)','o.optDouble("max_speed_kph", 0.0), o.optDouble("max_gps_accuracy_m", 0.0), o.optDouble("max_off_route_m", 0.0),\n                o.optJSONObject("timing")?.toString().orEmpty()')
rep('RaceDataStore.kt','val rankedCount: Int = 0, val participantCount: Int = 0','val rankedCount: Int = 0, val participantCount: Int = 0, val startedElapsedNs: Long = 0L')
rep('RaceDataStore.kt','put("state", state); put("event_code", eventCode);','put("started_elapsed_ns", startedElapsedNs)\n            put("state", state); put("event_code", eventCode);')
rep('RaceDataStore.kt','o.optInt("ranked_count", 0), o.optInt("participant_count", 0)','o.optInt("ranked_count", 0), o.optInt("participant_count", 0), o.optLong("started_elapsed_ns", 0L)')
rep('RaceDataStore.kt','put("t", location.time); put("lat", location.latitude);','put("elapsed_ns", location.elapsedRealtimeNanos)\n            put("t", location.time); put("lat", location.latitude);')
rep('RaceLiveLapDisplayInstaller.kt','import android.os.Looper','import android.os.Looper\nimport android.os.SystemClock')
rep('RaceLiveLapDisplayInstaller.kt','val usable = sessionRuns.filter { it.status != "INVALID" }','val usable = sessionRuns.filter { RaceFairTiming.usable(it) }')
rep('RaceLiveLapDisplayInstaller.kt','.filter { it.courseId == courseId && it.status != "INVALID" }','.filter { it.courseId == courseId && RaceFairTiming.usable(it) }')
rep('RaceLiveLapDisplayInstaller.kt','(System.currentTimeMillis() - s.startedAtMs).coerceAtLeast(0L)','if (s.startedElapsedNs > 0L) ((SystemClock.elapsedRealtimeNanos() - s.startedElapsedNs) / 1_000_000L).coerceAtLeast(0L) else s.elapsedMs')
rep('RaceActivity.kt','"FINISHED" -> "FINISH · ${s.validation}"','"FINISHED" -> if (s.serverStatus.contains("랩타임 확인중")) "FINISH · 랩타임 확인중" else "FINISH · 기록 저장"')
rep('RaceTimingService.kt','import android.os.PowerManager','import android.os.PowerManager\nimport android.os.SystemClock')
rep('RaceTimingService.kt','private var startUncertaintyMs = 0L','private var startUncertaintyMs: Long? = null')
rep('RaceTimingService.kt','private var timingUncertaintyMs = 0L','private var timingUncertaintyMs: Long? = null')
rep('RaceTimingService.kt','startUncertaintyMs = 0L','startUncertaintyMs = null')
rep('RaceTimingService.kt','timingUncertaintyMs = 0L','timingUncertaintyMs = null')
rep('RaceTimingService.kt','private var timingRefinementMethod = "PAIR_INTERPOLATION"','''private var timingRefinementMethod = "PAIR_INTERPOLATION"
    private var clockOffsetMs: Long? = null
    private var startAudit: JSONObject? = null
    private var timingRecovered = false
    private var lastFix: Location? = null
    private val finishTail = mutableListOf<Location>()
    private var finishDisplay: RaceDataStore.Snapshot? = null
    private var finishDisplayUntil = 0L
    private fun timingNow(): Long = (clockOffsetMs ?: (System.currentTimeMillis()-SystemClock.elapsedRealtime()))+SystemClock.elapsedRealtime()
    private fun stableLocation(raw: Location): Location {
        if (clockOffsetMs == null) clockOffsetMs = raw.time-raw.elapsedRealtimeNanos/1_000_000L
        return Location(raw).apply { time=clockOffsetMs!!+raw.elapsedRealtimeNanos/1_000_000L }
    }
''')
rep('RaceTimingService.kt','private fun arm(input: RaceEventConfig, cid: String, nextLap: Boolean = false) {\n        cancelNextLapRearm()','''private fun arm(input: RaceEventConfig, cid: String, nextLap: Boolean = false) {
        if (pendingFinishGate != null) finalizePendingFinish(rearm = false)
        if (!nextLap) { clockOffsetMs=null; finishDisplay=null }
        startAudit=null; timingRecovered=false; finishTail.clear()
        cancelNextLapRearm()''')
rep('RaceTimingService.kt','        requestGps()\n        Thread { runCatching { client.flushPending() } }.start()','        if (!nextLap) requestGps()\n        Thread { runCatching { client.flushPending() } }.start()')
rep('RaceTimingService.kt','val seed = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return','val seedRaw = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return\n        val seed = stableLocation(seedRaw)')
rep('RaceTimingService.kt','val ageMs = abs(System.currentTimeMillis() - seed.time)','val ageMs = abs(SystemClock.elapsedRealtime()-seed.elapsedRealtimeNanos/1_000_000L)')
rep('RaceTimingService.kt','override fun onLocationChanged(location: Location) {\n        val cfg = config ?: return','''override fun onLocationChanged(rawLocation: Location) {
        if (rawLocation.elapsedRealtimeNanos <= 0L) return
        val location = stableLocation(rawLocation)
        if (lastFix != null && location.elapsedRealtimeNanos <= lastFix!!.elapsedRealtimeNanos) return
        lastFix = Location(location)
        val cfg = config ?: return''')
rep('RaceTimingService.kt','        timingRefiner.add(location, m.routeM)\n        val accuracy','''        timingRefiner.add(location, m.routeM)
        if (state == "FINISHED" && pendingFinishGate != null) {
            finishTail.add(Location(location))
            store.appendRaw(runId, location, m.routeM, m.distanceM)
            return
        }
        val accuracy''')
rep('RaceTimingService.kt','startAt = crossAt.coerceAtMost(System.currentTimeMillis())','startAt = crossAt.coerceAtMost(timingNow())')
rep('RaceTimingService.kt','timingRefiner.refine(gate.routeM, preliminaryStartAt)','timingRefiner.refine(gate, preliminaryStartAt)')
rep('RaceTimingService.kt','        if (result == null) return\n        val oldStart','        if (result == null) return\n        startAudit = result.audit\n        val oldStart')
rep('RaceTimingService.kt','timingRefiner.refine(gate.routeM, preliminaryFinishAt)','timingRefiner.refine(gate, preliminaryFinishAt)')
rep('RaceTimingService.kt','val finishUncertainty = refined?.uncertaintyMs ?: 900L\n        timingUncertaintyMs = (startUncertaintyMs + finishUncertainty).coerceIn(80L, 2_000L)','val finishUncertainty = refined?.uncertaintyMs\n        timingUncertaintyMs = if (startUncertaintyMs != null && finishUncertainty != null) startUncertaintyMs!! + finishUncertainty else null')
rep('RaceTimingService.kt','    private fun finalizePendingFinish() {','    private fun finalizePendingFinish(rearm: Boolean = true) {')
rep('RaceTimingService.kt','        val reasons = validationReasons()\n        val summary','''        val reasons = validationReasons()
        val timingAudit = RaceFairTiming.audit(cfg, startAudit, refined?.audit, preliminaryStartAt, preliminaryFinishAt, reasons)
        val summary''')
rep('RaceTimingService.kt','            maxAccuracyM,\n            maxOffRouteM\n        )','            maxAccuracyM,\n            maxOffRouteM,\n            timingJson = timingAudit.toString()\n        )')
rep('RaceTimingService.kt','append(" · 추정오차 ±").append(timingUncertaintyMs).append("ms")','''append(if (timingAudit.optString("quality") == "ACCEPTED") " · 계측기준 충족(시범)" else " · 계측 검토")
            if (timingUncertaintyMs == null) append(" · 여유폭 판단 불가")
            else append(" · 판정여유폭 ±").append(timingUncertaintyMs).append("ms(잠정)")''')
rep('RaceTimingService.kt','        pendingFinishRouteM = routeM\n        liveDeltaMs','''        pendingFinishRouteM = routeM
        finishTail.clear()
        prev?.let { finishTail.add(Location(it)) }
        lastFix?.let { finishTail.add(Location(it)) }
        liveDeltaMs''')
rep('RaceTimingService.kt','        updateNotification("FINISH · 랩타임 확인중")','''        updateNotification("FINISH · 랩타임 확인중")
        RaceFairTiming.savePending(this, JSONObject().apply {
            put("config", cfgJson());put("snapshot", store.snapshot().toJson())
            put("original_start", preliminaryStartAt);put("original_finish",preliminaryFinishAt)
            put("start_audit",startAudit ?: JSONObject.NULL);put("clock_offset",clockOffsetMs ?: 0L)
        })''')
rep('RaceTimingService.kt','''        finishFinalizeRunnable = null
        pendingFinishGate = null
        runCatching { locationManager.removeUpdates(this) }
        fusion.stop()
        releaseTimingWakeLock()
        updateNotification("FINISH ${formatRaceTime(elapsed)} · $validation · 정밀보정 완료")

        val task = Runnable {
            if (state != "FINISHED") return@Runnable
            arm(cfg, finishedCourseId, nextLap = true)
        }
        nextLapRunnable = task
        mainHandler.postDelayed(task, NEXT_LAP_REARM_DELAY_MS)''','''        finishFinalizeRunnable?.let(mainHandler::removeCallbacks)
        finishFinalizeRunnable = null
        pendingFinishGate = null
        RaceFairTiming.clearPending(this)
        updateNotification("FINISH ${formatRaceTime(elapsed)} · 기록 저장")
        if (rearm) {
            val tail = finishTail.map { Location(it) }
            finishDisplay = store.snapshot()
            finishDisplayUntil = SystemClock.elapsedRealtime()+NEXT_LAP_REARM_DELAY_MS
            arm(cfg, finishedCourseId, nextLap = true)
            prev=null;previousRouteM=null;lastFix=null;matcher=RaceRouteMatcher(loaded);timingRefiner.reset()
            val startGate=cfg.gates.first()
            val shared=Geo.distanceMeters(startGate.lat,startGate.lon,gate.lat,gate.lon)<=1.0 && bearingDelta(startGate.bearingDeg,gate.bearingDeg)<=15.0
            if(shared){
                beginRun(startGate,finalCrossAt)
                startRefined=true;startUncertaintyMs=finishUncertainty
                startAudit=refined?.audit?.let { JSONObject(it.toString()).put("gate",startGate.toJson()) }
            }
            tail.filter { !shared || it.time > finalCrossAt }.distinctBy { it.elapsedRealtimeNanos }.sortedBy { it.elapsedRealtimeNanos }.forEach { onLocationChanged(it) }
        }''')
rep('RaceTimingService.kt','(System.currentTimeMillis() - startAt).coerceAtLeast(0L)','(timingNow() - startAt).coerceAtLeast(0L)')
rep('RaceTimingService.kt','                startedAtMs = startAt,','                startedAtMs = startAt,\n                startedElapsedNs = if (startAt > 0L && clockOffsetMs != null) (startAt-clockOffsetMs!!)*1_000_000L else 0L,')
rep('RaceTimingService.kt','''        val cfg = config
        val previous = store.snapshot()''','''        val cfg = config
        if (state == "ARMED" && finishDisplay != null && SystemClock.elapsedRealtime()<finishDisplayUntil) {
            store.writeSnapshot(finishDisplay!!);return
        }
        val previous = store.snapshot()''')
rep('RaceTimingService.kt','    private fun validationReasons(): List<String> = buildList {','    private fun validationReasons(): List<String> = buildList {\n        if (timingRecovered) add("TIMING_RECOVERY")')
rep('RaceTimingService.kt','    private fun stopRace() {\n        cancelNextLapRearm()','    private fun stopRace() {\n        if (pendingFinishGate != null) finalizePendingFinish(rearm = false)\n        cancelNextLapRearm()')
rep('RaceTimingService.kt','    override fun onDestroy() {\n        cancelNextLapRearm()','    override fun onDestroy() {\n        if (pendingFinishGate != null) finalizePendingFinish(rearm = false)\n        cancelNextLapRearm()')
rep('RaceTimingService.kt','    private fun recoverIfNeeded() {\n        val snap = store.snapshot()','''    private fun cfgJson(): JSONObject = (config?.toJson() ?: JSONObject()).put("local_course_id",courseId)
    private fun recoverPendingFinish(): Boolean {
        val saved=RaceFairTiming.readPending(this) ?: return false
        val c=saved.optJSONObject("config") ?: return false
        val snap=RaceDataStore.Snapshot.fromJson(saved.getJSONObject("snapshot"))
        val cid=c.optString("local_course_id")
        val loaded=runCatching { CourseRepository(this).loadCourse(cid) }.getOrNull() ?: return false
        config=RaceGateMath.normalize(RaceEventConfig.fromJson(c),loaded);course=loaded;courseId=cid
        state="FINISHED";runId=snap.runId;runNumber=snap.runNumber;startAt=snap.startedAtMs;lastGateAt=snap.lastGateAtMs
        preliminaryStartAt=saved.optLong("original_start",startAt);preliminaryFinishAt=saved.optLong("original_finish",startAt+snap.elapsedMs)
        clockOffsetMs=saved.optLong("clock_offset");startAudit=saved.optJSONObject("start_audit");startRefined=true
        sectors.clear();sectors.addAll(snap.sectors);maxAccuracyM=snap.maxGpsAccuracyM;maxOffRouteM=snap.maxOffRouteM
        maxSpeedKph=snap.maxSpeedKph;jumpCount=snap.jumpCount;timingRecovered=true
        pendingFinishGate=config!!.gates.last();pendingFinishRouteM=snap.totalM
        finalizePendingFinish(rearm=false)
        return true
    }
    private fun recoverIfNeeded() {
        if (recoverPendingFinish()) return
        val snap = store.snapshot()''')
rep('RaceTimingService.kt','        preliminaryStartAt = startAt\n        startRefined = true','        preliminaryStartAt = startAt\n        timingRecovered = true\n        startRefined = true')
rep('RaceTimingService.kt','        startAt = snap.startedAtMs\n        preliminaryStartAt = startAt','        startAt = snap.startedAtMs\n        if (snap.startedElapsedNs > 0L) clockOffsetMs=startAt-snap.startedElapsedNs/1_000_000L\n        preliminaryStartAt = startAt')
rep('RaceTimingService.kt','        updateNotification(if (recoveredStart) "RUNNING · START 복구 · ${cfg.name}" else "RUNNING · ${cfg.name}")','''        updateNotification(if (recoveredStart) "RUNNING · START 복구 · ${cfg.name}" else "RUNNING · ${cfg.name}")
        if (cfg.eventCode != "PRACTICE") {
            store.enqueue("START",cfg.eventCode,JSONObject().apply {
                put("event_code",cfg.eventCode);put("run_id",runId);put("run_number",runNumber)
                put("started_at_ms",startAt);put("timestamp_ms",startAt);put("elapsed_ms",0)
                put("state","RUNNING");put("route_m",startGate.routeM);put("fair_policy",runCatching{JSONObject(cfg.fairPolicyJson)}.getOrDefault(JSONObject()))
            },client.baseUrl())
        }''')
rep('RaceServerClient.kt','                    "SECTOR" -> sendSector(eventCode, token, payload)','                    "START" -> sendLive(eventCode, token, payload)\n                    "SECTOR" -> sendSector(eventCode, token, payload)')
rep('VERSION.txt','0.34.57','0.34.58')
rep('app/build.gradle.kts','dependencies {','dependencies {\n    testImplementation("junit:junit:4.13.2")')
