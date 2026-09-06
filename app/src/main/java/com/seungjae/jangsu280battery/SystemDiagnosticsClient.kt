package com.seungjae.jangsu280battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Administrator-only one-click diagnostics for field use. Never lets a diagnostic exception crash the app. */
class SystemDiagnosticsClient(private val context: Context) {
    private val app = context.applicationContext
    private val sync = RiderServerSync(app)

    data class Result(val ok: Boolean, val title: String, val text: String, val raw: String = "")

    fun diagnoseAsync(callback: (Result) -> Unit) {
        Thread {
            val result = try {
                val local = safeLocalReport()
                val remote = runCatching { request("GET", "/api/mobile/admin/system-diagnostics") }
                remote.fold(
                    onSuccess = { json ->
                        val summary = json.optJSONObject("summary")
                        val status = summary?.optString("status", "-") ?: "-"
                        val issues = summary?.optInt("issue_count", 0) ?: 0
                        val repairable = summary?.optInt("repairable_count", 0) ?: 0
                        Result(
                            ok = status != "ERROR",
                            title = "진단 $status · 문제 ${issues}건 · 자동복구 ${repairable}건",
                            text = local + "\n\n[PC 서버]\n" + compactChecks(json),
                            raw = runCatching { json.toString(2) }.getOrDefault(json.toString())
                        )
                    },
                    onFailure = { e ->
                        Result(
                            ok = false,
                            title = "진단 주의 · PC 서버 진단 연결 실패",
                            text = local + "\n\n[PC 서버]\n진단 API 실패 · ${safeMessage(e)}"
                        )
                    }
                )
            } catch (t: Throwable) {
                Result(
                    ok = false,
                    title = "진단 실패 · 앱 내부 오류를 안전하게 잡았습니다",
                    text = "[휴대폰]\n진단 중 예외 발생 · ${safeMessage(t)}\n앱은 종료하지 않았습니다. 이 보고서를 복사해 주세요."
                )
            }
            deliver(callback, result)
        }.start()
    }

    fun safeRepairAsync(callback: (Result) -> Unit) {
        Thread {
            try {
                val remote = runCatching { request("POST", "/api/mobile/admin/system-diagnostics/repair") }
                if (remote.isFailure) {
                    deliver(callback, Result(false, "자동복구 실패", "서버 자동복구 연결 실패 · ${safeMessage(remote.exceptionOrNull())}"))
                    return@Thread
                }
                val json = remote.getOrThrow()
                val actions = json.optJSONArray("actions")
                val errors = json.optJSONArray("errors")
                val lines = mutableListOf<String>()
                for (i in 0 until (actions?.length() ?: 0)) lines += "✓ ${actions?.optString(i)}"
                for (i in 0 until (errors?.length() ?: 0)) lines += "! ${errors?.optString(i)}"

                try {
                    sync.syncAllAsync { syncResult ->
                        val prefix = buildString {
                            append(if (lines.isEmpty()) "안전 자동복구 검사 완료" else lines.joinToString("\n"))
                            append("\n\n휴대폰 동기화 · ").append(syncResult.message)
                        }
                        diagnoseAsync { after ->
                            deliver(
                                callback,
                                Result(
                                    after.ok && (errors?.length() ?: 0) == 0,
                                    "자동복구 후 · ${after.title}",
                                    prefix + "\n\n" + after.text,
                                    after.raw
                                )
                            )
                        }
                    }
                } catch (t: Throwable) {
                    deliver(callback, Result(false, "자동복구 부분 완료", (if (lines.isEmpty()) "서버 복구 검사 완료" else lines.joinToString("\n")) + "\n\n휴대폰 동기화 시작 실패 · ${safeMessage(t)}"))
                }
            } catch (t: Throwable) {
                deliver(callback, Result(false, "자동복구 실패 · 앱은 종료하지 않았습니다", safeMessage(t)))
            }
        }.start()
    }

    private fun safeLocalReport(): String = try {
        localReport()
    } catch (t: Throwable) {
        "[휴대폰]\n로컬 진단 일부 실패 · ${safeMessage(t)}"
    }

    private fun localReport(): String {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "-"
        val fine = runCatching { app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val network = runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.activeNetwork != null
        }.getOrDefault(false)
        val repo = CourseRepository(app)
        val courses = runCatching { repo.listCourses() }.getOrDefault(emptyList())
        val raceCourses = courses.count { meta ->
            val f = runCatching { repo.sourceFile(meta.id) }.getOrNull() ?: return@count false
            runCatching {
                f.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    val n = r.read(buf)
                    n > 0 && String(buf, 0, n).contains("creator=\"Ride Copilot RACE\"")
                }
            }.getOrDefault(false)
        }
        val serverUrl = runCatching { sync.serverUrl() }.getOrDefault("")
        val adminCached = runCatching { sync.isAdminDeviceCached() }.getOrDefault(false)
        val auto = runCatching { sync.autoEnabled() }.getOrDefault(false)
        val pending = runCatching { sync.pendingCount() }.getOrDefault(-1)
        return buildString {
            append("[휴대폰]\n")
            append("앱 v").append(version).append('\n')
            append("서버 주소 · ").append(serverUrl.ifBlank { "미설정" }).append('\n')
            append("관리자폰 · ").append(if (adminCached) "인증 캐시 있음" else "미등록").append('\n')
            append("자동동기화 · ").append(if (auto) "ON" else "OFF").append(" · 대기 ").append(if (pending >= 0) "$pending 건" else "확인 실패").append('\n')
            append("네트워크 · ").append(if (network) "연결" else "미연결/확인 불가").append('\n')
            append("정확한 위치 권한 · ").append(if (fine) "허용" else "필요").append('\n')
            append("저장 코스 · ").append(courses.size).append("개 · RACE 제작 ").append(raceCourses).append("개")
        }
    }

    private fun compactChecks(json: JSONObject): String {
        val checks = json.optJSONArray("checks") ?: return "진단 항목 없음"
        val lines = mutableListOf<String>()
        for (i in 0 until checks.length()) {
            val x = checks.optJSONObject(i) ?: continue
            val ok = x.optBoolean("ok", false)
            lines += "${if (ok) "✓" else if (x.optBoolean("repairable", false)) "🛠" else "!"} ${x.optString("title")} · ${x.optString("detail")}"
        }
        return lines.joinToString("\n")
    }

    private fun request(method: String, path: String): JSONObject {
        require(runCatching { sync.configured() }.getOrDefault(false)) { "Rider Control Center 서버 연결이 필요합니다." }
        require(runCatching { sync.isAdminDeviceCached() }.getOrDefault(false)) { "관리자폰에서만 진단할 수 있습니다." }
        val base = runCatching { sync.serverUrl() }.getOrDefault("").trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "서버 주소가 올바르지 않습니다: $base" }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${runCatching { sync.token() }.getOrDefault("")}")
            conn.setRequestProperty("Accept", "application/json")
            if (method != "GET") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
                error(detail.ifBlank { "HTTP $code" })
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun deliver(callback: (Result) -> Unit, result: Result) {
        try {
            callback(result)
        } catch (_: Throwable) {
            // Never let a reporting callback crash the process.
        }
    }

    private fun safeMessage(t: Throwable?): String {
        if (t == null) return "알 수 없는 오류"
        val msg = t.message?.trim().orEmpty()
        return if (msg.isNotBlank()) "${t.javaClass.simpleName}: $msg" else t.javaClass.simpleName
    }
}
