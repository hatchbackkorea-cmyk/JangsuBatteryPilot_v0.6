package com.seungjae.jangsu280battery

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Rider Control Center realtime group client.
 *
 * - WebSocket push: no 10-second polling delay.
 * - Uses the existing Rider Control Center device token.
 * - Reconnects automatically after Cloud Run's WebSocket timeout/network loss.
 * - OkHttp ping frames keep mobile/NAT connections alive.
 */
class GroupRideRealtimeClient(
    baseUrl: String,
    private val deviceToken: String,
    private val room: String,
    private val onSnapshot: (List<GroupRider>, JSONObject) -> Unit,
    private val onState: (String) -> Unit
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var reconnectAttempt = 0
    @Volatile private var lastSelf: GroupRider? = null
    @Volatile private var lastAccuracyM: Double = 0.0

    fun connect() {
        if (stopped) return
        val wsRoot = when {
            root.startsWith("https://", true) -> "wss://" + root.substring(8)
            root.startsWith("http://", true) -> "ws://" + root.substring(7)
            else -> root
        }
        val request = Request.Builder()
            .url("$wsRoot/ws/group/${room.trim()}")
            .header("Authorization", "Bearer $deviceToken")
            .build()
        onState("실시간 서버 연결 중…")
        socket = client.newWebSocket(request, Listener())
    }

    fun sendPosition(self: GroupRider, accuracyM: Double = 0.0) {
        lastSelf = self
        lastAccuracyM = accuracyM
        val body = JSONObject()
            .put("type", "position")
            .put("riderId", self.riderId)
            .put("nickname", self.nickname)
            .put("courseKey", self.courseKey)
            .put("routeKm", self.routeKm)
            .put("lat", self.lat)
            .put("lon", self.lon)
            .put("speedKph", self.speedKph)
            .put("accuracyM", accuracyM)
            .put("updatedMs", self.updatedMs)
            .toString()
        socket?.send(body)
    }

    fun close() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "user_stop")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        val delay = (1000L shl reconnectAttempt.coerceAtMost(3)).coerceAtMost(10_000L)
        reconnectAttempt++
        onState("실시간 연결 끊김 · ${delay / 1000}초 후 재연결 ($reason)")
        handler.postDelayed({ if (!stopped) connect() }, delay)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            onState("● 실시간 연결됨 · 주행 1초 / 정차 5초 저비용 위치 공유")
            lastSelf?.let { sendPosition(it, lastAccuracyM) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val root = JSONObject(text)
                if (root.optString("type") != "snapshot") return@runCatching
                val arr = root.optJSONArray("riders") ?: JSONArray()
                val riders = (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val o = arr.getJSONObject(i)
                        GroupRider(
                            riderId = o.optString("riderId", UUID.randomUUID().toString()),
                            nickname = o.optString("nickname", "팀원"),
                            courseKey = o.optString("courseKey", ""),
                            routeKm = o.optDouble("routeKm", 0.0),
                            lat = o.optDouble("lat", 0.0),
                            lon = o.optDouble("lon", 0.0),
                            speedKph = o.optDouble("speedKph", 0.0),
                            updatedMs = o.optLong("updatedMs", 0L)
                        )
                    }.getOrNull()
                }
                onSnapshot(riders, root)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (!stopped) scheduleReconnect("$code ${reason.take(40)}")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (!stopped) scheduleReconnect(t.message ?: "network")
        }
    }
}
