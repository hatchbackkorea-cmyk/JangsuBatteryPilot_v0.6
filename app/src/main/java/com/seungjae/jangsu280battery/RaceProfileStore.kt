package com.seungjae.jangsu280battery

import android.content.Context
import java.util.UUID

/**
 * RACE 공개 프로필은 이름/닉네임 두 항목만 받는다.
 * 내부 식별자는 사용자 입력 없이 자동 생성하며 화면에는 노출하지 않는다.
 */
object RaceProfileStore {
    private const val PREF = "race_profile_v1"
    private const val KEY_NAME = "name"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_PROFILE_ID = "profile_id"

    data class Profile(
        val name: String,
        val nickname: String,
        val profileId: String
    ) {
        val isReady: Boolean get() = name.isNotBlank() && nickname.isNotBlank()
        val displayName: String get() = when {
            nickname.isNotBlank() && name.isNotBlank() -> "$nickname ($name)"
            nickname.isNotBlank() -> nickname
            else -> name
        }
    }

    fun profile(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_PROFILE_ID, "").orEmpty()
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_PROFILE_ID, id).apply()
        }
        return Profile(
            name = prefs.getString(KEY_NAME, "").orEmpty().trim(),
            nickname = prefs.getString(KEY_NICKNAME, "").orEmpty().trim(),
            profileId = id
        )
    }

    fun save(context: Context, name: String, nickname: String): Profile {
        val current = profile(context)
        val cleanName = name.trim().take(40)
        val cleanNickname = nickname.trim().take(40)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, cleanName)
            .putString(KEY_NICKNAME, cleanNickname)
            .putString(KEY_PROFILE_ID, current.profileId)
            .apply()
        return Profile(cleanName, cleanNickname, current.profileId)
    }
}
