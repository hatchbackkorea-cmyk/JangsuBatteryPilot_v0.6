package com.seungjae.jangsu280battery

import android.content.Context
import java.util.UUID

/**
 * RACE 공개 등록 정보는 아이디/닉네임/배번 세 항목만 받는다.
 * 기존 서버 호환을 위해 아이디 값은 프로토콜의 name 필드로 전송한다.
 * 내부 profileId는 사용자 입력 없이 자동 생성한다.
 */
object RaceProfileStore {
    private const val PREF = "race_profile_v1"
    private const val KEY_NAME = "name"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_BIB = "bib"
    private const val KEY_PROFILE_ID = "profile_id"

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
        var id = prefs.getString(KEY_PROFILE_ID, "").orEmpty()
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_PROFILE_ID, id).apply()
        }
        return Profile(
            name = prefs.getString(KEY_NAME, "").orEmpty().trim(),
            nickname = prefs.getString(KEY_NICKNAME, "").orEmpty().trim(),
            bib = prefs.getString(KEY_BIB, "").orEmpty().trim(),
            profileId = id
        )
    }

    fun save(context: Context, name: String, nickname: String, bib: String): Profile {
        val current = profile(context)
        val cleanName = name.trim().take(40)
        val cleanNickname = nickname.trim().take(40)
        val cleanBib = bib.trim().take(20)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, cleanName)
            .putString(KEY_NICKNAME, cleanNickname)
            .putString(KEY_BIB, cleanBib)
            .putString(KEY_PROFILE_ID, current.profileId)
            .apply()
        return Profile(cleanName, cleanNickname, cleanBib, current.profileId)
    }

    /** Legacy callers keep their current bib value. */
    fun save(context: Context, name: String, nickname: String): Profile {
        val current = profile(context)
        return save(context, name, nickname, current.bib)
    }
}
