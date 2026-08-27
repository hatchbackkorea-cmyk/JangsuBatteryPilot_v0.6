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
    }

    fun saveClientSecret(value: String) = putEncrypted(SECRET, value.trim())
    fun clientSecret(): String? = getEncrypted(SECRET)

    fun saveTokens(access: String, refresh: String, expiresAt: Long, athleteName: String?) {
        putEncrypted(ACCESS, access)
        putEncrypted(REFRESH, refresh)
        prefs.edit().putLong(EXPIRES, expiresAt).putString(ATHLETE, athleteName.orEmpty()).apply()
    }

    fun accessToken(): String? = getEncrypted(ACCESS)
    fun refreshToken(): String? = getEncrypted(REFRESH)
    fun expiresAt(): Long = prefs.getLong(EXPIRES, 0L)
    fun athleteName(): String? = prefs.getString(ATHLETE, null)?.takeIf { it.isNotBlank() }
    fun isConnected(): Boolean = !accessToken().isNullOrBlank() && !refreshToken().isNullOrBlank()

    fun clearTokens() {
        prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(ATHLETE).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
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
