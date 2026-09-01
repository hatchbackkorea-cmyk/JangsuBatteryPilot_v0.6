package com.seungjae.jangsu280battery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v0.31.4: Strava 인증 정보는 APK에 넣지 않고 사용자가 자신의 Client Secret을
 * 한 번 입력하면 Android Keystore로 암호화해 이 기기에만 저장한다.
 * 예전 v0.30.4와 같은 prefs/key alias를 사용해 기존 연결을 가능한 한 재사용한다.
 */
class StravaSecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("strava_secure", Context.MODE_PRIVATE)

    companion object {
        const val CLIENT_ID = "274909"
        const val REDIRECT_URI = "jangsubatterypilot://localhost/strava"
        private const val KEY_ALIAS = "jangsu_strava_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SECRET = "client_secret"
        private const val ACCESS = "access_token"
        private const val REFRESH = "refresh_token"
        private const val EXPIRES = "expires_at"
        private const val ATHLETE = "athlete_name"
        private const val SCOPE = "granted_scope"
        private const val ATHLETE_WEIGHT = "athlete_weight_kg"
        private const val ATHLETE_FTP = "athlete_ftp_w"
        private const val ATHLETE_PROFILE_AT = "athlete_profile_at"
    }

    fun saveClientSecret(value: String) = putEncrypted(SECRET, value.trim())
    fun clientSecret(): String? = getEncrypted(SECRET)

    fun saveTokens(access: String, refresh: String, expiresAt: Long, athleteName: String?, grantedScope: String? = null) {
        putEncrypted(ACCESS, access)
        putEncrypted(REFRESH, refresh)
        val editor = prefs.edit().putLong(EXPIRES, expiresAt).putString(ATHLETE, athleteName.orEmpty())
        if (grantedScope != null) editor.putString(SCOPE, grantedScope)
        editor.apply()
    }

    fun accessToken(): String? = getEncrypted(ACCESS)
    fun refreshToken(): String? = getEncrypted(REFRESH)
    fun expiresAt(): Long = prefs.getLong(EXPIRES, 0L)
    fun athleteName(): String? = prefs.getString(ATHLETE, null)?.takeIf { it.isNotBlank() }
    fun grantedScope(): String = prefs.getString(SCOPE, "").orEmpty()
    fun hasActivityRead(): Boolean = grantedScope().contains("activity:read")
    fun hasProfileRead(): Boolean = grantedScope().contains("profile:read_all")
    fun athleteWeightKg(): Double? = prefs.getString(ATHLETE_WEIGHT, null)?.toDoubleOrNull()?.takeIf { it in 30.0..200.0 }
    fun athleteFtpW(): Double? = prefs.getString(ATHLETE_FTP, null)?.toDoubleOrNull()?.takeIf { it in 50.0..600.0 }
    fun athleteProfileAtMs(): Long = prefs.getLong(ATHLETE_PROFILE_AT, 0L)
    fun saveAthleteProfile(weightKg: Double?, ftpW: Double?) {
        val e = prefs.edit()
        if (weightKg != null && weightKg in 30.0..200.0) e.putString(ATHLETE_WEIGHT, weightKg.toString()) else e.remove(ATHLETE_WEIGHT)
        if (ftpW != null && ftpW in 50.0..600.0) e.putString(ATHLETE_FTP, ftpW.toString()) else e.remove(ATHLETE_FTP)
        e.putLong(ATHLETE_PROFILE_AT, System.currentTimeMillis()).apply()
    }
    fun isConnected(): Boolean = !accessToken().isNullOrBlank() && !refreshToken().isNullOrBlank()

    fun clearTokens() {
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(ATHLETE).remove(SCOPE)
            .remove(ATHLETE_WEIGHT).remove(ATHLETE_FTP).remove(ATHLETE_PROFILE_AT).apply()
    }

    private fun putEncrypted(name: String, plain: String) {
        if (plain.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, packed).apply()
    }

    private fun getEncrypted(name: String): String? {
        val packed = prefs.getString(name, null) ?: return null
        return runCatching {
            val parts = packed.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
