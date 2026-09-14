package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import android.os.Build
import java.util.Locale
import java.util.UUID

object TimingOperatorStoreCore {
    data class Assignment(val eventCode:String,val role:String,val token:String,val expiresAtMs:Long,val serverUrl:String="") {
        fun isValid(nowMs:Long=System.currentTimeMillis())=eventCode.isNotBlank()&&role in ROLES&&token.length>=20&&expiresAtMs>nowMs
    }
    data class Handoff(val eventCode:String,val role:String,val token:String,val expiresAtMs:Long,val serverUrl:String) {
        fun isValid(nowMs:Long=System.currentTimeMillis())=eventCode.isNotBlank()&&role in ROLES&&token.length>=20&&expiresAtMs>nowMs&&serverUrl.startsWith("http")
    }

    private const val PREFS="timegate_timing_operator_v1"
    private const val KEY_EVENT="event_code"
    private const val KEY_ROLE="role"
    private const val KEY_TOKEN="operator_token"
    private const val KEY_EXPIRES="expires_at_ms"
    private const val KEY_SERVER="server_url"
    private const val KEY_DEVICE_ID="device_id"
    private const val CAMERA_PREFS="camera_gate_test"
    private const val CAMERA_ROLE_KEY="gate_role_v16"
    val ROLES=listOf("START","CP1","CP2","CP3","CP4","CP5","FINISH")

    fun deviceId(context:Context):String {
        val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val old=p.getString(KEY_DEVICE_ID,"").orEmpty(); if(old.isNotBlank()) return old
        val created=UUID.randomUUID().toString().replace("-","").take(16);p.edit().putString(KEY_DEVICE_ID,created).apply();return created
    }
    fun deviceLabel(context:Context)="${Build.MANUFACTURER} ${Build.MODEL} · ${deviceId(context).takeLast(4)}"

    fun current(context:Context,nowMs:Long=System.currentTimeMillis()):Assignment? {
        val p=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val a=Assignment(p.getString(KEY_EVENT,"").orEmpty(),p.getString(KEY_ROLE,"").orEmpty().uppercase(Locale.US),p.getString(KEY_TOKEN,"").orEmpty(),p.getLong(KEY_EXPIRES,0L),p.getString(KEY_SERVER,"").orEmpty())
        if(!a.isValid(nowMs)){if(a.eventCode.isNotBlank()||a.token.isNotBlank())clear(context);return null};return a
    }
    fun save(context:Context,a:Assignment){require(a.isValid());val app=context.applicationContext
        app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_EVENT,a.eventCode.uppercase(Locale.US)).putString(KEY_ROLE,a.role.uppercase(Locale.US)).putString(KEY_TOKEN,a.token).putLong(KEY_EXPIRES,a.expiresAtMs).putString(KEY_SERVER,a.serverUrl.trim().trimEnd('/')).apply()
        app.getSharedPreferences(CAMERA_PREFS,Context.MODE_PRIVATE).edit().putString(CAMERA_ROLE_KEY,a.role.uppercase(Locale.US)).apply()
    }
    fun clear(context:Context){val app=context.applicationContext;val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val id=p.getString(KEY_DEVICE_ID,"").orEmpty();p.edit().clear().putString(KEY_DEVICE_ID,id).apply();app.getSharedPreferences(CAMERA_PREFS,Context.MODE_PRIVATE).edit().putString(CAMERA_ROLE_KEY,"AUTO").apply()}

    fun buildLink(a:Assignment):String=Uri.Builder().scheme("jangsubatterypilot").authority("timing").appendPath("enroll").appendQueryParameter("event",a.eventCode.uppercase(Locale.US)).appendQueryParameter("role",a.role.uppercase(Locale.US)).appendQueryParameter("token",a.token).appendQueryParameter("exp",a.expiresAtMs.toString()).apply{if(a.serverUrl.isNotBlank())appendQueryParameter("server",a.serverUrl.trim().trimEnd('/'))}.build().toString()
    fun buildHandoffLink(h:Handoff):String=Uri.Builder().scheme("jangsubatterypilot").authority("timing").appendPath("enroll").appendQueryParameter("event",h.eventCode.uppercase(Locale.US)).appendQueryParameter("role",h.role.uppercase(Locale.US)).appendQueryParameter("token",h.token).appendQueryParameter("exp",h.expiresAtMs.toString()).appendQueryParameter("server",h.serverUrl.trim().trimEnd('/')).appendQueryParameter("handoff","1").build().toString()
    fun isHandoff(uri:Uri?)=uri?.getQueryParameter("handoff")=="1"
    fun parse(uri:Uri?,nowMs:Long=System.currentTimeMillis()):Assignment?{if(uri==null||uri.scheme!="jangsubatterypilot"||uri.host!="timing"||uri.pathSegments.firstOrNull()!="enroll")return null;val a=Assignment(uri.getQueryParameter("event").orEmpty().trim().uppercase(Locale.US),uri.getQueryParameter("role").orEmpty().trim().uppercase(Locale.US),uri.getQueryParameter("token").orEmpty().trim(),uri.getQueryParameter("exp")?.toLongOrNull()?:return null,uri.getQueryParameter("server").orEmpty().trim().trimEnd('/'));if(!a.isValid(nowMs)||a.expiresAtMs-nowMs>48L*60L*60L*1000L)return null;return a}
}
