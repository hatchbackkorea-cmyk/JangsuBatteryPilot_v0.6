package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


data class PublicGroupRoom(
    val room: String,
    val title: String,
    val riderCount: Int,
    val connectedCount: Int,
    val maxRiders: Int,
    val courseName: String,
    val courseDistanceKm: Double,
    val hasCourse: Boolean
)

data class GuestGroupSession(
    val serverUrl: String,
    val room: String,
    val nickname: String,
    val token: String,
    val expiresIn: Long
)

/**
 * Zero-config group-room bootstrap.
 *
 * Resolution order:
 * 1) same-LAN UDP discovery / HTTP scan (prefers the user's PC server)
 * 2) current rcc-server.json pointer stored in the same GitHub repository as the APK
 * 3) cached public Funnel URL
 * 4) configured/cached fallback server
 */
class GroupRoomDiscovery(private val context: Context) {
    companion object {
        const val EXTRA_GUEST_SERVER = "guest_group_server"
        const val EXTRA_GUEST_ROOM = "guest_group_room"
        const val EXTRA_GUEST_NICK = "guest_group_nick"
        const val EXTRA_GUEST_TOKEN = "guest_group_token"
        private const val DISCOVERY_PORT = 17832
        private const val PREFS = "group_guest_bootstrap_v1"
        private const val KEY_LAST_SERVER = "last_server"
        private const val KEY_PUBLIC_SERVER = "public_server"
        private const val KEY_NICK = "last_nick"
        private const val KEY_DEVICE = "guest_device_key"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastNickname(): String = prefs.getString(KEY_NICK, "").orEmpty()

    fun rememberNickname(name: String) {
        if (name.isNotBlank()) prefs.edit().putString(KEY_NICK, name.trim()).apply()
    }

    fun deviceKey(): String {
        return prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }
    }

    fun resolveServer(): String? {
        (discoverLan() ?: scanLanHttp())?.let { found ->
            val local = found.optString("url", "").trim().trimEnd('/')
            val public = found.optString("publicUrl", "").trim().trimEnd('/')
            val edit = prefs.edit()
            if (local.startsWith("http")) edit.putString(KEY_LAST_SERVER, local)
            if (public.startsWith("https")) edit.putString(KEY_PUBLIC_SERVER, public)
            edit.apply()
            if (local.startsWith("http")) return local
        }

        // Prefer the current published pointer over a previously cached Funnel URL.
        // This lets an app recover automatically after the PC server address changes.
        githubBootstrapUrl()?.let { url ->
            prefs.edit().putString(KEY_PUBLIC_SERVER, url).putString(KEY_LAST_SERVER, url).apply()
            return url
        }

        val cachedPublic = prefs.getString(KEY_PUBLIC_SERVER, "").orEmpty().trim().trimEnd('/')
        if (cachedPublic.startsWith("https")) return cachedPublic

        val configured = RiderServerSync(context).serverUrl().trim().trimEnd('/')
        if (configured.startsWith("http")) return configured
        val cached = prefs.getString(KEY_LAST_SERVER, "").orEmpty().trim().trimEnd('/')
        if (cached.startsWith("http")) return cached
        return null
    }

    fun fetchRooms(serverUrl: String): List<PublicGroupRoom> {
        val root = serverUrl.trim().trimEnd('/')
        val conn = (URL("$root/api/public/live/rooms").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            conn.disconnect()
            error("공개 그룹방 조회 실패 HTTP $code ${msg.take(100)}")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                PublicGroupRoom(
                    room = o.optString("room", ""),
                    title = o.optString("title", "그룹방"),
                    riderCount = o.optInt("riderCount", 0),
                    connectedCount = o.optInt("connectedCount", 0),
                    maxRiders = o.optInt("maxRiders", 20),
                    courseName = o.optString("courseName", ""),
                    courseDistanceKm = o.optDouble("courseDistanceKm", 0.0),
                    hasCourse = o.optBoolean("hasCourse", false)
                )
            }.getOrNull()
        }.filter { it.room.isNotBlank() }
    }

    fun joinRoom(serverUrl: String, room: String, nickname: String): GuestGroupSession {
        val root = serverUrl.trim().trimEnd('/')
        val conn = (URL("$root/api/public/live/rooms/${room.trim()}/join").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject()
            .put("nickname", nickname.trim())
            .put("deviceKey", deviceKey())
            .toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
            error(detail.ifBlank { "그룹방 참가 실패 HTTP $code" })
        }
        val o = JSONObject(text)
        val token = o.optString("guestToken", "")
        require(token.isNotBlank()) { "게스트 참가 토큰 발급 실패" }
        rememberNickname(nickname)
        prefs.edit().putString(KEY_LAST_SERVER, root).apply()
        return GuestGroupSession(
            serverUrl = root,
            room = o.optString("room", room),
            nickname = o.optString("nickname", nickname),
            token = token,
            expiresIn = o.optLong("expiresIn", 43200L)
        )
    }

    private fun discoverLan(): JSONObject? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 900
            }
            val payload = "RCC_DISCOVER_V1".toByteArray(Charsets.UTF_8)
            val targets = listOf("255.255.255.255")
            targets.forEach { host ->
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), DISCOVERY_PORT))
                }
            }
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val o = JSONObject(text)
            if (o.optString("service") == "RCC") o else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun scanLanHttp(): JSONObject? {
        val prefixes = mutableSetOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val parts = a.hostAddress?.split('.') ?: continue
                        if (parts.size == 4) prefixes += parts.take(3).joinToString(".")
                    }
                }
            }
        }
        if (prefixes.isEmpty()) return null
        val found = AtomicReference<JSONObject?>(null)
        val pool = Executors.newFixedThreadPool(32)
        for (prefix in prefixes.take(3)) {
            for (i in 1..254) {
                pool.submit {
                    if (found.get() != null) return@submit
                    val root = "http://$prefix.$i:8000"
                    runCatching {
                        val c = (URL("$root/api/health").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 180
                            readTimeout = 250
                            setRequestProperty("Accept", "application/json")
                        }
                        if (c.responseCode in 200..299) {
                            val text = c.inputStream.bufferedReader().use { it.readText() }
                            val o = JSONObject(text)
                            if (o.optBoolean("guest_room_join", false)) {
                                val pub = o.optString("public_url", "")
                                found.compareAndSet(null, JSONObject().put("service", "RCC").put("url", root).put("publicUrl", pub))
                            }
                        }
                        c.disconnect()
                    }
                }
            }
        }
        pool.shutdown()
        runCatching { pool.awaitTermination(1800, TimeUnit.MILLISECONDS) }
        pool.shutdownNow()
        return found.get()
    }

    private fun githubBootstrapUrl(): String? {
        val repo = BuildConfig.UPDATE_REPOSITORY.trim()
        if (!repo.contains('/')) return null
        val url = "https://raw.githubusercontent.com/$repo/main/rcc-server.json"
        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (c.responseCode !in 200..299) {
                c.disconnect(); return@runCatching null
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            JSONObject(text).optString("url", "").trim().trimEnd('/').takeIf { it.startsWith("http") }
        }.getOrNull()
    }
}
