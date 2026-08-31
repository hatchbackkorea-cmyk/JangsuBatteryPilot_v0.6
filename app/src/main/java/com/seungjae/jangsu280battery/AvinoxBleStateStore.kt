package com.seungjae.jangsu280battery

import android.content.Context

/**
 * Small persistent bridge between the foreground ride service and the UI.
 * This data is display/runtime state only. It is never learning data by itself.
 */
data class AvinoxBleSnapshot(
    val soc: Int?,
    val state: String,
    val updatedMs: Long,
    val address: String?
) {
    fun freshSoc(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = 30_000L): Int? =
        soc?.takeIf { updatedMs > 0L && nowMs - updatedMs in 0..maxAgeMs }
}

data class AvinoxBleRawSnapshot(
    val hex: String?,
    val updatedMs: Long
)

class AvinoxBleStateStore(context: Context) {
    companion object {
        private const val PREFS = "avinox_ble_runtime"
        private const val KEY_SOC = "soc"
        private const val KEY_STATE = "state"
        private const val KEY_UPDATED = "updated_ms"
        private const val KEY_ADDRESS = "address"
        private const val KEY_RAW_HEX = "raw_hex"
        private const val KEY_RAW_UPDATED = "raw_updated_ms"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(): AvinoxBleSnapshot {
        val soc = if (prefs.contains(KEY_SOC)) prefs.getInt(KEY_SOC, -1).takeIf { it in 0..100 } else null
        return AvinoxBleSnapshot(
            soc = soc,
            state = prefs.getString(KEY_STATE, "BLE 대기").orEmpty().ifBlank { "BLE 대기" },
            updatedMs = prefs.getLong(KEY_UPDATED, 0L),
            address = prefs.getString(KEY_ADDRESS, null)
        )
    }

    fun rawSnapshot(): AvinoxBleRawSnapshot = AvinoxBleRawSnapshot(
        hex = prefs.getString(KEY_RAW_HEX, null),
        updatedMs = prefs.getLong(KEY_RAW_UPDATED, 0L)
    )

    fun setRawNotification(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        prefs.edit().putString(KEY_RAW_HEX, hex).putLong(KEY_RAW_UPDATED, nowMs).apply()
    }

    fun setState(state: String, address: String? = snapshot().address) {
        prefs.edit()
            .putString(KEY_STATE, state)
            .apply {
                if (address.isNullOrBlank()) remove(KEY_ADDRESS) else putString(KEY_ADDRESS, address)
            }
            .apply()
    }

    fun setSoc(soc: Int, state: String = "BLE 자동 · 연결됨", address: String? = snapshot().address, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_SOC, soc.coerceIn(0, 100))
            .putString(KEY_STATE, state)
            .putLong(KEY_UPDATED, nowMs)
            .apply {
                if (address.isNullOrBlank()) remove(KEY_ADDRESS) else putString(KEY_ADDRESS, address)
            }
            .apply()
    }

    fun clearRuntime(keepAddress: Boolean = true) {
        val address = if (keepAddress) prefs.getString(KEY_ADDRESS, null) else null
        prefs.edit().clear().apply()
        if (!address.isNullOrBlank()) prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_STATE, "BLE 대기").apply()
    }
}
