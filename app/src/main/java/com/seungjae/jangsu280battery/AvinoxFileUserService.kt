package com.seungjae.jangsu280battery

import java.io.File
import java.io.RandomAccessFile

/**
 * Shizuku UserService. It runs as shell (UID 2000) when Shizuku is started through ADB/wireless debugging.
 * Only the Avinox public external app-data tree is exposed to the normal app process.
 */
class AvinoxFileUserService() : IAvinoxFileService.Stub() {
    companion object {
        const val ROOT = "/sdcard/Android/data/com.avinox.ride/files/ebike/data"
        private val ROOT_CANONICAL = runCatching { File(ROOT).canonicalPath }.getOrDefault(ROOT)
        private const val MAX_CHUNK = 256 * 1024
        private val ALLOWED_NAME = Regex("^cloud_ride_rec_.+_[0-9]+\\.proto$")
    }


    override fun listProtoFiles(rootDir: String): Array<String> {
        val root = File(rootDir.ifBlank { ROOT })
        if (!isAllowedRoot(root)) return emptyArray()
        if (!root.exists() || !root.isDirectory) return emptyArray()
        val result = ArrayList<String>()
        scan(root, result, depth = 0)
        return result.sortedBy { it.substringBefore('\t') }.toTypedArray()
    }

    private fun scan(dir: File, out: MutableList<String>, depth: Int) {
        if (depth > 4) return
        val children = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (f in children) {
            if (f.isDirectory) scan(f, out, depth + 1)
            else if (f.isFile && ALLOWED_NAME.matches(f.name)) {
                out += "${f.absolutePath}\t${f.length()}\t${f.lastModified()}"
            }
        }
    }

    override fun readChunk(path: String, offset: Long, maxBytes: Int): ByteArray {
        val file = File(path)
        if (!isAllowedFile(file) || !file.exists() || !file.isFile || offset < 0L) return ByteArray(0)
        val requested = maxBytes.coerceIn(1, MAX_CHUNK)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (offset >= raf.length()) return ByteArray(0)
                raf.seek(offset)
                val size = minOf(requested.toLong(), raf.length() - offset).toInt()
                ByteArray(size).also { raf.readFully(it) }
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun isAllowedRoot(file: File): Boolean {
        val normalized = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return normalized == ROOT_CANONICAL || normalized.startsWith("$ROOT_CANONICAL/")
    }

    private fun isAllowedFile(file: File): Boolean {
        val normalized = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return normalized.startsWith("$ROOT_CANONICAL/") && ALLOWED_NAME.matches(file.name)
    }

    override fun destroy() {
        System.exit(0)
    }
}
