package com.seungjae.jangsu280battery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.kakao.vectormap.KakaoMapSdk
import java.security.MessageDigest

/**
 * Kakao Maps SDK bootstrap.
 *
 * The production build may inject KAKAO_NATIVE_APP_KEY through BuildConfig. For field testing,
 * an admin can also store the Native App Key locally on the phone without rebuilding the APK.
 * The Kakao key is a client app key; actual access is restricted by the Android package/key-hash
 * registration in Kakao Developers.
 */
object KakaoMapSdkGate {
    private const val PREFS = "kakao_map_sdk"
    private const val KEY_NATIVE_APP_KEY = "native_app_key"

    @Volatile
    private var initializedKey: String? = null

    fun configuredKey(context: Context): String {
        val local = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NATIVE_APP_KEY, "")
            .orEmpty()
            .trim()
        return local.ifBlank { BuildConfig.KAKAO_NATIVE_APP_KEY.trim() }
    }

    fun hasKey(context: Context): Boolean = configuredKey(context).isNotBlank()

    fun saveLocalKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NATIVE_APP_KEY, key.trim())
            .apply()
    }

    fun ensureInitialized(context: Context): Boolean {
        val key = configuredKey(context)
        if (key.isBlank()) return false
        if (initializedKey == key) return true
        synchronized(this) {
            if (initializedKey == key) return true
            return try {
                KakaoMapSdk.init(context.applicationContext, key)
                initializedKey = key
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun appKeyHash(context: Context): String {
        return runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            } ?: return@runCatching ""

            val digest = MessageDigest.getInstance("SHA-1").digest(signatureBytes)
            Base64.encodeToString(digest, Base64.NO_WRAP)
        }.getOrDefault("")
    }
}
