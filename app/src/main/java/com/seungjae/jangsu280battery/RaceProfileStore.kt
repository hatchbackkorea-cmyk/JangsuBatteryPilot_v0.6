package com.seungjae.jangsu280battery

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * RACE 공개 등록 정보는 아이디/닉네임/배번 세 항목만 받는다.
 * 기존 서버 호환을 위해 아이디 값은 프로토콜의 name 필드로 전송한다.
 *
 * 선수의 내부 profileId는 이름/닉네임/배번과 분리된 기기 신원이다.
 * 이름/닉네임/배번을 수정해도 profileId는 바뀌지 않아 기존 랩 기록이 유지된다.
 * 반대로 Android 백업/폰 복원으로 앱 설정이 복제되어도 서로 다른 폰은 서로 다른
 * profileId를 가져야 하므로 SharedPreferences UUID를 신원으로 사용하지 않는다.
 */
object RaceProfileStore {
    private const val PREF = "race_profile_v1"
    private const val KEY_NAME = "name"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_BIB = "bib"
    private const val LEGACY_KEY_PROFILE_ID = "profile_id"
    private const val FALLBACK_ID_FILE = "race_device_profile_id_v2"
    private const val PROFILE_ID_PREFIX = "tgdev2-"

    data class Profile(
        val name: String,
        val nickname: String,
        val bib: String,
        val profileId: String
    ) {
        val isReady: Boolean get() = name.isNotBlank() && nickname.isNotBlank() && bib.isNotBlank()
        val displayName: String get() = when {
            nickname.isNotBlank() && name.isNotBlank() -> "$nickname ($name)"
            nickname.isNotBlank() -> nickname
            else -> name
        }
    }

    fun profile(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = deviceScopedProfileId(context)

        // v1 stored a random UUID inside backup-able SharedPreferences. A phone clone/restore could
        // therefore make multiple physical phones look like one racer. It is no longer authoritative.
        if (prefs.contains(LEGACY_KEY_PROFILE_ID)) {
            prefs.edit().remove(LEGACY_KEY_PROFILE_ID).apply()
        }

        return Profile(
            name = prefs.getString(KEY_NAME, "").orEmpty().trim(),
            nickname = prefs.getString(KEY_NICKNAME, "").orEmpty().trim(),
            bib = prefs.getString(KEY_BIB, "").orEmpty().trim(),
            profileId = id
        )
    }

    fun save(context: Context, name: String, nickname: String, bib: String): Profile {
        val cleanName = name.trim().take(40)
        val cleanNickname = nickname.trim().take(40)
        val cleanBib = bib.trim().take(20)
        val id = deviceScopedProfileId(context)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, cleanName)
            .putString(KEY_NICKNAME, cleanNickname)
            .putString(KEY_BIB, cleanBib)
            .remove(LEGACY_KEY_PROFILE_ID)
            .apply()
        return Profile(cleanName, cleanNickname, cleanBib, id)
    }

    /** Legacy callers keep their current bib value. */
    fun save(context: Context, name: String, nickname: String): Profile {
        val current = profile(context)
        return save(context, name, nickname, current.bib)
    }

    /**
     * Android 8+ ANDROID_ID is scoped by device/user/app signing key and survives normal app-data
     * restore without becoming the source phone's ID. Hash it before it leaves this process.
     * If a device does not provide a usable ANDROID_ID, use noBackupFilesDir so Android Auto Backup
     * cannot copy the fallback identity to another phone.
     */
    private fun deviceScopedProfileId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim()

        val seed = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            "${context.packageName}|$androidId"
        } else {
            val file = context.noBackupFilesDir.resolve(FALLBACK_ID_FILE)
            val local = runCatching { file.takeIf { it.isFile }?.readText()?.trim().orEmpty() }.getOrDefault("")
            val stable = local.ifBlank {
                UUID.randomUUID().toString().also { generated ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(generated)
                    }
                }
            }
            "${context.packageName}|fallback|$stable"
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return PROFILE_ID_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
