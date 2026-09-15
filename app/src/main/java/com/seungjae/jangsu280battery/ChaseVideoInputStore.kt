package com.seungjae.jangsu280battery

import android.content.Context

enum class ChaseVideoInputMode {
    PHONE,
    USB_H264,
}

object ChaseVideoInputStore {
    private const val PREFS = "timegate_chase_video_input_v1"
    private const val KEY_MODE = "mode"

    fun get(context: Context): ChaseVideoInputMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, ChaseVideoInputMode.PHONE.name)
        return runCatching { ChaseVideoInputMode.valueOf(raw ?: ChaseVideoInputMode.PHONE.name) }
            .getOrDefault(ChaseVideoInputMode.PHONE)
    }

    fun set(context: Context, mode: ChaseVideoInputMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun label(context: Context): String = when (get(context)) {
        ChaseVideoInputMode.PHONE -> "휴대폰 카메라"
        ChaseVideoInputMode.USB_H264 -> "USB H.264 액션캠"
    }
}
