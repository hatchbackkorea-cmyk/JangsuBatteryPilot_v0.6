package com.seungjae.jangsu280battery

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

object RaceTimingPendingStore {
    private fun pending(context: Context) = AtomicFile(
        File(context.filesDir, "race/pending_finish.json").also { it.parentFile?.mkdirs() }
    )

    fun save(context: Context, data: JSONObject) {
        val file = pending(context)
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun read(context: Context): JSONObject? = runCatching {
        JSONObject(String(pending(context).readFully(), Charsets.UTF_8))
    }.getOrNull()

    fun clear(context: Context) = pending(context).delete()

    fun markFinalized(context: Context, runId: String) {
        context.getSharedPreferences("race_lap_display", Context.MODE_PRIVATE).edit()
            .putString("run", runId)
            .putLong("at", android.os.SystemClock.elapsedRealtime())
            .apply()
    }

    fun justFinalized(context: Context): String? {
        val prefs = context.getSharedPreferences("race_lap_display", Context.MODE_PRIVATE)
        val age = android.os.SystemClock.elapsedRealtime() - prefs.getLong("at", 0L)
        return if (age in 0L..1500L) prefs.getString("run", null) else null
    }
}
