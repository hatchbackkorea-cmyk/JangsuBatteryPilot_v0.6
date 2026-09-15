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
    private const val KEY_GRANTED_AT="granted_at_ms"
    private const val KEY_SERVER="server_url"
    private const val KEY_DEVICE_ID="device_id"
    private const val CAMERA_PREFS="camera_gate_test"
    private const val CAMERA_ROLE_KEY="gate_role_v16"
    private const val CAMERA_INSTALL_ID="install_id"
    private const val ACCESS_TTL_MS=12L*60L*60L*1000L
    private const val LEGACY_LEASE_TTL_MS=36L*60L*60L*1000L
    val TIMING_ROLES=listOf("START","CP1","CP2","CP3","CP4","CP5","FINISH")
    val BROADCAST_ROLES=listOf("CHASE")+(1..12).map { "CAM$it" }
    val ROLES=TIMING_ROLES+BROADCAST_ROLES
    fun isBroadcastRole(role:String)=role.trim().uppercase(Locale.US) in BROADCAST_ROLES

    /** Use the exact same id as CameraGateRoleInstaller so admin registration and triggers identify one phone. */
    fun deviceId(context:Context):String {
        val app=context.applicationContext
        val camera=app.getSharedPreferences(CAMERA_PREFS,Context.MODE_PRIVATE)
        val cameraId=camera.getString(CAMERA_INSTALL_ID,"").orEmpty()
        if(cameraId.isNotBlank()) {
            app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_DEVICE_ID,cameraId).apply()
            return cameraId
        }
        val own=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_DEVICE_ID,"").orEmpty()
        val created=(own.ifBlank { UUID.randomUUID().toString().replace("-","").take(12) })
        camera.edit().putString(CAMERA_INSTALL_ID,created).apply()
        app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_DEVICE_ID,created).apply()
        return created
    }
    fun deviceLabel(context:Context)="${Build.MANUFACTURER} ${Build.MODEL} · ${deviceId(context).takeLast(4)}"

    fun current(context:Context,nowMs:Long=System.currentTimeMillis()):Assignment? {
        val app=context.applicationContext
        val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val rawExpires=p.getLong(KEY_EXPIRES,0L)
        var grantedAt=p.getLong(KEY_GRANTED_AT,0L)
        // v0.34.137 and earlier stored a 36-hour lease without a grant timestamp. Any entry
        // without KEY_GRANTED_AT is therefore treated as that legacy format and clamped to the
        // first 12 hours from its original registration time.
        if(grantedAt<=0L&&rawExpires>0L){
            grantedAt=(rawExpires-LEGACY_LEASE_TTL_MS).coerceAtLeast(0L)
        }
        val localExpires=if(grantedAt>0L) minOf(rawExpires,grantedAt+ACCESS_TTL_MS) else rawExpires
        val a=Assignment(
            p.getString(KEY_EVENT,"").orEmpty(),
            p.getString(KEY_ROLE,"").orEmpty().uppercase(Locale.US),
            p.getString(KEY_TOKEN,"").orEmpty(),
            localExpires,
            p.getString(KEY_SERVER,"").orEmpty()
        )
        if(!a.isValid(nowMs)){
            if(a.eventCode.isNotBlank()||a.token.isNotBlank())clear(context)
            return null
        }
        if(p.getLong(KEY_GRANTED_AT,0L)<=0L&&grantedAt>0L){
            p.edit().putLong(KEY_GRANTED_AT,grantedAt).putLong(KEY_EXPIRES,localExpires).apply()
        }
        return a
    }
    fun save(context:Context,a:Assignment){
        require(a.isValid())
        val app=context.applicationContext
        val now=System.currentTimeMillis()
        val localExpires=minOf(a.expiresAtMs,now+ACCESS_TTL_MS)
        app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(KEY_EVENT,a.eventCode.uppercase(Locale.US))
            .putString(KEY_ROLE,a.role.uppercase(Locale.US))
            .putString(KEY_TOKEN,a.token)
            .putLong(KEY_GRANTED_AT,now)
            .putLong(KEY_EXPIRES,localExpires)
            .putString(KEY_SERVER,a.serverUrl.trim().trimEnd('/'))
            .putString(KEY_DEVICE_ID,deviceId(context))
            .apply()
        // Official timing roles keep the Camera Gate relay role. Broadcast-only roles force the
        // legacy trigger relay into COMPARE mode so no START/CP/FINISH timing post can be emitted.
        val cameraMode=if(isBroadcastRole(a.role))"COMPARE" else a.role.uppercase(Locale.US)
        app.getSharedPreferences(CAMERA_PREFS,Context.MODE_PRIVATE).edit().putString(CAMERA_ROLE_KEY,cameraMode).apply()
    }
    fun clear(context:Context){
        val app=context.applicationContext
        val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val id=deviceId(context)
        p.edit().clear().putString(KEY_DEVICE_ID,id).apply()
        app.getSharedPreferences(CAMERA_PREFS,Context.MODE_PRIVATE).edit().putString(CAMERA_ROLE_KEY,"AUTO").apply()
    }

    fun buildLink(a:Assignment):String=Uri.Builder().scheme("jangsubatterypilot").authority("timing").appendPath("enroll").appendQueryParameter("event",a.eventCode.uppercase(Locale.US)).appendQueryParameter("role",a.role.uppercase(Locale.US)).appendQueryParameter("token",a.token).appendQueryParameter("exp",a.expiresAtMs.toString()).apply{if(a.serverUrl.isNotBlank())appendQueryParameter("server",a.serverUrl.trim().trimEnd('/'))}.build().toString()
    fun buildHandoffLink(h:Handoff):String=Uri.Builder().scheme("jangsubatterypilot").authority("timing").appendPath("enroll").appendQueryParameter("event",h.eventCode.uppercase(Locale.US)).appendQueryParameter("role",h.role.uppercase(Locale.US)).appendQueryParameter("token",h.token).appendQueryParameter("exp",h.expiresAtMs.toString()).appendQueryParameter("server",h.serverUrl.trim().trimEnd('/')).appendQueryParameter("handoff","1").build().toString()
    fun isHandoff(uri:Uri?)=uri?.getQueryParameter("handoff")=="1"
    fun parse(uri:Uri?,nowMs:Long=System.currentTimeMillis()):Assignment?{
        if(uri==null||uri.scheme!="jangsubatterypilot"||uri.host!="timing"||uri.pathSegments.firstOrNull()!="enroll")return null
        val a=Assignment(
            uri.getQueryParameter("event").orEmpty().trim().uppercase(Locale.US),
            uri.getQueryParameter("role").orEmpty().trim().uppercase(Locale.US),
            uri.getQueryParameter("token").orEmpty().trim(),
            uri.getQueryParameter("exp")?.toLongOrNull()?:return null,
            uri.getQueryParameter("server").orEmpty().trim().trimEnd('/')
        )
        if(!a.isValid(nowMs)||a.expiresAtMs-nowMs>48L*60L*60L*1000L)return null
        return a
    }
}
