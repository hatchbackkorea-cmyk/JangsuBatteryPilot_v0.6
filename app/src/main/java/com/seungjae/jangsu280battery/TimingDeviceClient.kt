package com.seungjae.jangsu280battery

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TimingDeviceClient {
    data class ClaimResult(val leaseToken:String,val leaseExpiresAtMs:Long)
    data class PollResult(val active:Boolean,val pingSeq:Int,val pingRequestedAtMs:Long)

    private val http=OkHttpClient.Builder()
        .connectTimeout(4,TimeUnit.SECONDS)
        .readTimeout(4,TimeUnit.SECONDS)
        .writeTimeout(4,TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun claim(context:Context,serverUrl:String,eventCode:String,role:String,enrollToken:String):ClaimResult {
        val base=serverUrl.trim().trimEnd('/')
        require(base.startsWith("http://")||base.startsWith("https://")){"서버 주소가 올바르지 않습니다."}
        val body=JSONObject().apply {
            put("event_code",eventCode.uppercase())
            put("role",role.uppercase())
            put("enroll_token",enrollToken)
            put("device_id",TimingOperatorStore.deviceId(context))
            put("device_label",TimingOperatorStore.deviceLabel(context))
        }
        val json=post(base,"/api/race/timing-device/claim",body)
        return ClaimResult(json.getString("lease_token"),json.getLong("lease_expires_at_ms"))
    }

    fun poll(context:Context,assignment:TimingOperatorStore.Assignment):PollResult {
        val base=assignment.serverUrl.trim().trimEnd('/').ifBlank { RaceServerClient(context).baseUrl().trim().trimEnd('/') }
        require(base.startsWith("http://")||base.startsWith("https://")){"서버 주소가 올바르지 않습니다."}
        val body=JSONObject().apply {
            put("event_code",assignment.eventCode)
            put("role",assignment.role)
            put("lease_token",assignment.token)
            put("device_id",TimingOperatorStore.deviceId(context))
        }
        val json=post(base,"/api/race/timing-device/poll",body)
        return PollResult(json.optBoolean("active",false),json.optInt("ping_seq",0),json.optLong("ping_requested_at_ms",0L))
    }

    private fun post(base:String,path:String,body:JSONObject):JSONObject {
        val req=Request.Builder()
            .url(base+path)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Cache-Control","no-cache")
            .build()
        http.newCall(req).execute().use { r ->
            val raw=r.body?.string().orEmpty()
            if(!r.isSuccessful){
                val detail=runCatching { JSONObject(raw).optString("detail") }.getOrDefault("")
                error(detail.ifBlank { "HTTP ${r.code}" })
            }
            return JSONObject(raw)
        }
    }
}
