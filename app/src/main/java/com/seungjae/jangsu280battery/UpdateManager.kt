package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * GitHub Releases 기반 자체 업데이트.
 * - 안정판: /releases/latest (draft/prerelease 제외)
 * - 테스트판 포함: /releases 목록의 최신 공개 릴리스
 * - 주행/FIT/학습 데이터는 전송하지 않는다. GitHub Release 메타데이터만 읽는다.
 */
data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sha256: String?,
    val prerelease: Boolean
)

enum class UpdateChannel { STABLE, BETA }

object UpdateManager {
    private const val PREFS = "app_update_v1"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check"
    private const val KEY_PENDING_APK = "pending_apk"
    private const val AUTO_CHECK_MS = 24L * 60L * 60L * 1000L

    fun repository(): String = BuildConfig.UPDATE_REPOSITORY.trim()

    fun currentVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
    } catch (_: Exception) {
        BuildConfig.VERSION_NAME
    }

    fun channel(context: Context): UpdateChannel = if (AppSettings.betaUpdates(context)) UpdateChannel.BETA else UpdateChannel.STABLE

    fun checkAsync(
        context: Context,
        channel: UpdateChannel = channel(context),
        callback: (Result<AppUpdateInfo?>) -> Unit
    ) {
        Thread {
            val result = runCatching { checkNow(context, channel) }
            (context as? Activity)?.runOnUiThread { callback(result) } ?: callback(result)
        }.start()
    }

    private fun checkNow(context: Context, channel: UpdateChannel): AppUpdateInfo? {
        val repo = repository()
        require(repo.contains('/')) {
            "업데이트 저장소가 빌드에 설정되지 않았습니다. GitHub Actions에서 -PupdateRepo=owner/repo 값을 지정하세요."
        }
        val base = "https://api.github.com/repos/$repo/releases"
        val release = when (channel) {
            UpdateChannel.STABLE -> requestJsonObject("$base/latest")
            UpdateChannel.BETA -> {
                val arr = requestJsonArray("$base?per_page=20")
                (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { !it.optBoolean("draft", false) }
                    ?: throw IllegalStateException("공개된 릴리스를 찾지 못했습니다.")
            }
        }

        val tag = release.optString("tag_name")
        val remoteVersion = normalizeVersion(tag.ifBlank { release.optString("name") })
        if (remoteVersion.isBlank() || compareVersions(remoteVersion, currentVersion(context)) <= 0) return null

        val assets = release.optJSONArray("assets") ?: JSONArray()
        val apk = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull {
                val n = it.optString("name").lowercase(Locale.US)
                n.endsWith(".apk") && !n.contains("unsigned")
            } ?: throw IllegalStateException("릴리스에 설치용 APK가 없습니다.")

        val digest = apk.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.lowercase(Locale.US)

        return AppUpdateInfo(
            versionName = remoteVersion,
            tagName = tag,
            title = release.optString("name").ifBlank { tag },
            notes = release.optString("body").trim(),
            apkUrl = apk.getString("browser_download_url"),
            apkName = apk.optString("name").ifBlank { "GPXBatteryCopilot-$tag.apk" },
            sha256 = digest,
            prerelease = release.optBoolean("prerelease", false)
        )
    }

    fun maybeCheckOnLaunch(activity: Activity) {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST_AUTO_CHECK, 0L) < AUTO_CHECK_MS) return
        p.edit().putLong(KEY_LAST_AUTO_CHECK, now).apply()
        checkAsync(activity) { result ->
            result.getOrNull()?.let { showUpdateDialog(activity, it) }
            // 자동 확인 오류/최신 버전은 조용히 처리한다.
        }
    }

    fun showUpdateDialog(activity: Activity, info: AppUpdateInfo) {
        if (activity.isFinishing) return
        val channelText = if (info.prerelease) "테스트판" else "안정판"
        val body = buildString {
            append("현재 ${currentVersion(activity)} → 새 버전 ${info.versionName} ($channelText)")
            if (info.notes.isNotBlank()) {
                append("\n\n")
                append(info.notes.take(1800))
            }
            append("\n\n다운로드 후 Android 설치 확인 화면이 열립니다. 기존 주행/학습 데이터는 그대로 유지됩니다.")
        }
        AlertDialog.Builder(activity)
            .setTitle("업데이트 ${info.versionName}")
            .setMessage(body)
            .setPositiveButton("다운로드 및 설치") { _, _ ->
                downloadAndInstall(activity, info) { msg ->
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    fun downloadAndInstall(activity: Activity, info: AppUpdateInfo, status: (String) -> Unit) {
        status("업데이트 APK를 다운로드합니다…")
        Thread {
            runCatching {
                val dir = File(activity.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val file = File(dir, safeName(info.apkName))
                download(info.apkUrl, file)
                info.sha256?.let { expected ->
                    val actual = sha256(file)
                    check(actual.equals(expected, ignoreCase = true)) {
                        "APK SHA-256 검증 실패. 설치하지 않았습니다."
                    }
                }
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PENDING_APK, file.absolutePath).apply()
                activity.runOnUiThread {
                    status("다운로드 완료 · 설치를 시작합니다.")
                    requestInstall(activity, file)
                }
            }.onFailure { e ->
                activity.runOnUiThread { status("업데이트 실패: ${e.message ?: "알 수 없는 오류"}") }
            }
        }.start()
    }

    fun resumePendingInstall(activity: Activity) {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = p.getString(KEY_PENDING_APK, null) ?: return
        val file = File(path)
        if (!file.isFile) {
            p.edit().remove(KEY_PENDING_APK).apply()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) return
        requestInstall(activity, file)
    }

    private fun requestInstall(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
            android.widget.Toast.makeText(
                activity,
                "이 앱의 '알 수 없는 앱 설치'를 허용한 뒤 돌아오면 설치를 이어갑니다.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_APK).apply()
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun requestJsonObject(url: String): JSONObject = JSONObject(requestText(url))
    private fun requestJsonArray(url: String): JSONArray = JSONArray(requestText(url))

    private fun requestText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "GPX-Battery-Copilot/${BuildConfig.VERSION_NAME}")
        }
        return c.useConnection {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("GitHub 응답 $code${if (body.isNotBlank()) ": ${body.take(180)}" else ""}")
            body
        }
    }

    private fun download(url: String, target: File) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GPX-Battery-Copilot/${BuildConfig.VERSION_NAME}")
        }
        c.connect()
        if (c.responseCode !in 200..299) throw IllegalStateException("APK 다운로드 응답 ${c.responseCode}")
        c.inputStream.use { input ->
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        }
        if (target.length() < 100_000L) throw IllegalStateException("다운로드한 APK 파일이 비정상적으로 작습니다.")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizeVersion(value: String): String = value.trim().removePrefix("v").removePrefix("V")

    /** 0.16.1 > 0.16.0, 0.16.1 > 0.16.1-beta1, 0.16.1-beta2 > beta1 */
    fun compareVersions(aRaw: String, bRaw: String): Int {
        val a = normalizeVersion(aRaw)
        val b = normalizeVersion(bRaw)
        fun split(v: String): Pair<List<Int>, String?> {
            val main = v.substringBefore('-')
            val pre = v.substringAfter('-', "").ifBlank { null }
            val nums = main.split('.').map { it.toIntOrNull() ?: 0 }
            return nums to pre
        }
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        val max = maxOf(an.size, bn.size)
        for (i in 0 until max) {
            val av = an.getOrElse(i) { 0 }
            val bv = bn.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        if (ap == null && bp != null) return 1
        if (ap != null && bp == null) return -1
        if (ap == null && bp == null) return 0
        return comparePrerelease(ap!!, bp!!)
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val rx = Regex("([A-Za-z]+)[._-]?(\\d*)")
        val am = rx.find(a)
        val bm = rx.find(b)
        val al = am?.groupValues?.getOrNull(1)?.lowercase(Locale.US) ?: a.lowercase(Locale.US)
        val bl = bm?.groupValues?.getOrNull(1)?.lowercase(Locale.US) ?: b.lowercase(Locale.US)
        fun rank(s: String) = when {
            s.startsWith("rc") -> 3
            s.startsWith("beta") -> 2
            s.startsWith("alpha") -> 1
            else -> 0
        }
        val ar = rank(al); val br = rank(bl)
        if (ar != br) return ar.compareTo(br)
        val ai = am?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val bi = bm?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (ai != bi) return ai.compareTo(bi)
        return a.compareTo(b, ignoreCase = true)
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T = try { block() } finally { disconnect() }
}
