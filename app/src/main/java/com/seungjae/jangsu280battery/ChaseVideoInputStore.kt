package com.seungjae.jangsu280battery

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager

enum class ChaseVideoInputMode {
    PHONE,
    USB_H264,
}

object ChaseVideoInputStore {
    private const val PREFS = "timegate_chase_video_input_v1"
    private const val KEY_MODE = "mode"

    fun get(context: Context): ChaseVideoInputMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, null)
        if (!saved.isNullOrBlank()) {
            return runCatching { ChaseVideoInputMode.valueOf(saved) }
                .getOrDefault(ChaseVideoInputMode.PHONE)
        }

        // First USB CHASE test: when a UVC camera is already attached, choose USB H.264 once and
        // persist that decision. A later cable disconnect therefore does not silently fall back to
        // the phone camera; CHASE stays offline until the external camera returns or the user
        // explicitly selects another source in a later UI revision.
        val detected = runCatching {
            val usb = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            usb.deviceList.values.any { device ->
                (0 until device.interfaceCount).any { index ->
                    device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                }
            }
        }.getOrDefault(false)
        val mode = if (detected) ChaseVideoInputMode.USB_H264 else ChaseVideoInputMode.PHONE
        set(context, mode)
        return mode
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
