package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri

object TimingOperatorStore {
    data class Assignment(val eventCode:String,val role:String,val token:String,val expiresAtMs:Long,val serverUrl:String="")
    data class Handoff(val eventCode:String,val role:String,val token:String,val expiresAtMs:Long,val serverUrl:String)
    val ROLES get()=TimingOperatorStoreCore.ROLES
    fun deviceId(context:Context)=TimingOperatorStoreCore.deviceId(context)
    fun deviceLabel(context:Context)=TimingOperatorStoreCore.deviceLabel(context)
    fun current(context:Context,nowMs:Long=System.currentTimeMillis())=TimingOperatorStoreCore.current(context,nowMs)?.let{Assignment(it.eventCode,it.role,it.token,it.expiresAtMs,it.serverUrl)}
    fun save(context:Context,a:Assignment)=TimingOperatorStoreCore.save(context,TimingOperatorStoreCore.Assignment(a.eventCode,a.role,a.token,a.expiresAtMs,a.serverUrl))
    fun clear(context:Context)=TimingOperatorStoreCore.clear(context)
    fun buildLink(a:Assignment)=TimingOperatorStoreCore.buildLink(TimingOperatorStoreCore.Assignment(a.eventCode,a.role,a.token,a.expiresAtMs,a.serverUrl))
    fun buildHandoffLink(h:Handoff)=TimingOperatorStoreCore.buildHandoffLink(TimingOperatorStoreCore.Handoff(h.eventCode,h.role,h.token,h.expiresAtMs,h.serverUrl))
    fun isHandoff(uri:Uri?)=TimingOperatorStoreCore.isHandoff(uri)
    fun parse(uri:Uri?,nowMs:Long=System.currentTimeMillis())=TimingOperatorStoreCore.parse(uri,nowMs)?.let{Assignment(it.eventCode,it.role,it.token,it.expiresAtMs,it.serverUrl)}
}
