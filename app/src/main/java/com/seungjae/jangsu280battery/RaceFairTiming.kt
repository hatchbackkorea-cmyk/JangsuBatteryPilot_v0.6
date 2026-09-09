package com.seungjae.jangsu280battery

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RaceFairTiming {
    const val RULE = "TG-FAIR-1"
    fun margin(policy: String): Long = runCatching { JSONObject(policy).optLong("max_margin_ms",3000L) }.getOrDefault(3000L)
    fun audit(config: RaceEventConfig, start: JSONObject?, finish: JSONObject?, originalStart: Long, originalFinish: Long, runReasons: List<String>): JSONObject {
        val reasons=mutableListOf<String>()
        fun addGate(label: String, g: JSONObject?) {
            if(g==null){reasons+="${label}_EVIDENCE_MISSING";return}
            val xs=g.optJSONArray("reasons")?:JSONArray()
            for(i in 0 until xs.length())reasons+="${label}_${xs.optString(i)}"
            if(!g.optBoolean("usable",false)&&xs.length()==0)reasons+="${label}_UNRESOLVED"
        }
        addGate("START",start);addGate("FINISH",finish)
        val known=start!=null&&finish!=null&&!start.isNull("margin_ms")&&!finish.isNull("margin_ms")
        val m=if(known)start!!.optLong("margin_ms")+finish!!.optLong("margin_ms") else null
        if(m!=null&&m>margin(config.fairPolicyJson))reasons+="MARGIN_POLICY_EXCEEDED"
        if(runReasons.any{it in setOf("START_RECOVERED","CP_SKIPPED","TIMING_RECOVERY")})reasons+="GATE_EVIDENCE_INCOMPLETE"
        return JSONObject().apply {
            put("rule_version",RULE);put("algorithm",GateTimingMath.ALGORITHM)
            put("policy",runCatching{JSONObject(config.fairPolicyJson)}.getOrDefault(JSONObject()))
            put("margin_kind","OPERATIONAL_ESTIMATE_NOT_CALIBRATED");put("margin_ms",m?:JSONObject.NULL)
            put("quality",if(m==null)"UNKNOWN" else if(reasons.isEmpty())"ACCEPTED" else "REVIEW")
            put("reasons",JSONArray(reasons.distinct()));put("start",start?:JSONObject.NULL);put("finish",finish?:JSONObject.NULL)
            put("original_started_at_ms",originalStart);put("original_finished_at_ms",originalFinish)
            put("original_elapsed_ms",(originalFinish-originalStart).coerceAtLeast(0L));put("app_version",BuildConfig.VERSION_NAME)
        }
    }
    fun usable(summary: RaceRunSummary): Boolean = summary.status=="VALID" && runCatching{
        JSONObject(summary.timingJson).optString("quality")=="ACCEPTED"
    }.getOrDefault(false)
    private fun pending(context:Context)=AtomicFile(File(context.filesDir,"race/pending_fair_finish.json").also{it.parentFile?.mkdirs()})
    fun savePending(context:Context,data:JSONObject){
        val file=pending(context);val stream=file.startWrite()
        try{stream.write(data.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
    }
    fun readPending(context:Context):JSONObject?=runCatching{JSONObject(String(pending(context).readFully(),Charsets.UTF_8))}.getOrNull()
    fun clearPending(context:Context)=pending(context).delete()
    fun markFinalized(context: Context, runId: String) {
        context.getSharedPreferences("race_fair_display",Context.MODE_PRIVATE).edit()
            .putString("run",runId).putLong("at",android.os.SystemClock.elapsedRealtime()).apply()
    }
    fun justFinalized(context: Context): String? {
        val p=context.getSharedPreferences("race_fair_display",Context.MODE_PRIVATE)
        val age=android.os.SystemClock.elapsedRealtime()-p.getLong("at",0L)
        return if (age in 0L..1500L) p.getString("run",null) else null
    }
}
