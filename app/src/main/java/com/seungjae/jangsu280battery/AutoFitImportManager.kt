package com.seungjae.jangsu280battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional one-time-folder FIT auto import.
 *
 * The user grants a folder with ACTION_OPEN_DOCUMENT_TREE once. On later app resumes,
 * newly discovered .fit files are analyzed as B-grade auxiliary learning only.
 * Battery SOC / assist-mode consumption coefficients are never changed here.
 */
class AutoFitImportManager(context: Context) {
    companion object {
        private const val PREFS = "auto_fit_import"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_PROCESSED = "processed_uris"
        private const val MAX_DEPTH = 4
        private const val MAX_FILES_PER_SCAN = 80
        private val scanning = AtomicBoolean(false)
    }

    data class ScanResult(
        val discovered: Int,
        val imported: Int,
        val samples: Int,
        val failed: Int,
        val message: String
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun folderConfigured(): Boolean = folderUri() != null

    fun setFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clearFolder() {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    fun statusText(): String {
        val uri = folderUri() ?: return "FIT 백업 폴더 미지정"
        val count = prefs.getStringSet(KEY_PROCESSED, emptySet())?.size ?: 0
        return "FIT 백업 폴더 연결됨 · 처리 기록 ${count}개\n${uri.lastPathSegment ?: uri}"
    }

    fun scanAsync(onDone: (ScanResult) -> Unit) {
        val root = folderUri()
        if (root == null) {
            onDone(ScanResult(0, 0, 0, 0, "자동 FIT 폴더가 지정되지 않았습니다."))
            return
        }
        if (!scanning.compareAndSet(false, true)) {
            onDone(ScanResult(0, 0, 0, 0, "FIT 백업 검색이 이미 진행 중입니다."))
            return
        }
        Thread {
            val result = runCatching { scanNow(root) }.getOrElse { e ->
                ScanResult(0, 0, 0, 1, "FIT 백업 검색 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
            scanning.set(false)
            onDone(result)
        }.start()
    }

    private fun scanNow(root: Uri): ScanResult {
        val allFits = mutableListOf<Uri>()
        walkTree(root, root, 0, allFits)
        val processed = (prefs.getStringSet(KEY_PROCESSED, emptySet()) ?: emptySet()).toMutableSet()
        val fresh = allFits.distinctBy { it.toString() }
            .filter { it.toString() !in processed }
            .take(MAX_FILES_PER_SCAN)
        if (fresh.isEmpty()) {
            return ScanResult(allFits.size, 0, 0, 0, "새 FIT 없음 · 자동검색 완료")
        }

        val auxStore = FitAuxLearningStore(app)
        val insightStore = RideInsightStore(app)
        var imported = 0
        var failed = 0
        var samples = 0
        fresh.forEach { uri ->
            val result = runCatching {
                val analysis = HistoricalRideImporter.analyze(app, uri, HistoricalSourceType.FIT)
                val count = auxStore.trainFit(analysis)
                insightStore.analyzeAndStore(analysis)
                count
            }
            result.onSuccess { count ->
                // Valid FITs are remembered even when no useful auxiliary samples exist,
                // preventing an endless retry loop on the same file.
                processed += uri.toString()
                if (count > 0) {
                    imported += 1
                    samples += count
                }
            }.onFailure { failed += 1 }
        }
        prefs.edit().putStringSet(KEY_PROCESSED, processed).apply()
        val msg = when {
            imported > 0 -> "새 FIT ${imported}개 자동 보조학습 · ${samples}구간 반영${if (failed > 0) " · 실패 ${failed}" else ""}"
            failed > 0 -> "새 FIT을 찾았지만 ${failed}개 분석 실패"
            else -> "새 FIT 확인 완료 · 보조학습 가능한 시계열 없음"
        }
        return ScanResult(allFits.size, imported, samples, failed, msg)
    }

    private fun walkTree(treeUri: Uri, parentUri: Uri, depth: Int, out: MutableList<Uri>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES_PER_SCAN * 3) return
        val parentId = if (parentUri == treeUri) {
            DocumentsContract.getTreeDocumentId(treeUri)
        } else {
            DocumentsContract.getDocumentId(parentUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        app.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext() && out.size < MAX_FILES_PER_SCAN * 3) {
                val id = c.getString(idCol) ?: continue
                val name = c.getString(nameCol).orEmpty()
                val mime = c.getString(mimeCol).orEmpty()
                val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(treeUri, child, depth + 1, out)
                } else if (name.endsWith(".fit", ignoreCase = true)) {
                    out += child
                }
            }
        }
    }
}
