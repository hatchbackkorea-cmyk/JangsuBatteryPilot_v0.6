package com.seungjae.jangsu280battery

import android.content.Context

/** v0.11.0은 장수 사전 학습/이전 개인 학습을 모두 버리고 실제 데이터부터 새로 시작한다. */
object LearningMigration {
    private const val PREFS = "learning_migrations"
    private const val KEY_V0110_RESET = "v0110_fresh_learning_reset_done"

    fun ensureV0110FreshStart(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_V0110_RESET, false)) return

        BatteryLearningStore(app).clear()
        HistoricalRideStore(app).clear()
        HistoricalRideDataStore(app).clearAll()
        prefs.edit().putBoolean(KEY_V0110_RESET, true).apply()
    }
}
