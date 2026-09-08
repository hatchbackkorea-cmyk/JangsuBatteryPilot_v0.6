package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Fingerprint registry prevents downloading the same released GPX over and over. */
class PublicCourseRegistry(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("timegate_public_courses_v1", Context.MODE_PRIVATE)

    fun localCourseId(serverCourseId: Long, sha256: String): String? =
        prefs.getString(key(serverCourseId, sha256), null)?.takeIf { id -> CourseRepository(app).listCourses().any { it.id == id } }

    fun remember(serverCourseId: Long, sha256: String, localCourseId: String) {
        if (serverCourseId <= 0 || sha256.isBlank() || localCourseId.isBlank()) return
        prefs.edit().putString(key(serverCourseId, sha256), localCourseId).apply()
    }

    fun resolveExisting(course: PublicCourseClient.NearbyCourse, repo: CourseRepository): String? {
        localCourseId(course.serverCourseId, course.sha256)?.let { return it }
        if (course.sha256.isBlank()) return null
        val found = repo.listCourses().firstOrNull { meta ->
            val file = repo.sourceFile(meta.id) ?: return@firstOrNull false
            sha256(file).equals(course.sha256, ignoreCase = true)
        }?.id
        if (found != null) remember(course.serverCourseId, course.sha256, found)
        return found
    }

    fun cacheNearby(items: List<PublicCourseClient.NearbyCourse>) {
        val a = JSONArray().apply { items.forEach { put(it.toJson()) } }
        prefs.edit().putString("last_nearby", a.toString()).putLong("last_nearby_at", System.currentTimeMillis()).apply()
    }

    fun cachedNearby(maxAgeMs: Long = 15 * 60_000L): List<PublicCourseClient.NearbyCourse> {
        val at = prefs.getLong("last_nearby_at", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > maxAgeMs) return emptyList()
        val a = runCatching { JSONArray(prefs.getString("last_nearby", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PublicCourseClient.NearbyCourse::fromJson) }
    }

    fun shouldNotify(signature: String, quietMs: Long = 30 * 60_000L): Boolean {
        val lastSig = prefs.getString("last_notice_sig", "").orEmpty()
        val lastAt = prefs.getLong("last_notice_at", 0L)
        return signature != lastSig || System.currentTimeMillis() - lastAt > quietMs
    }

    fun markNotified(signature: String) {
        prefs.edit().putString("last_notice_sig", signature).putLong("last_notice_at", System.currentTimeMillis()).apply()
    }

    private fun key(serverCourseId: Long, sha: String) = "course_${serverCourseId}_${sha.lowercase()}"

    private fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}