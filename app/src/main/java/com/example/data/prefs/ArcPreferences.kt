package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.arcDataStore: DataStore<Preferences> by preferencesDataStore(name = "arc_prefs")

enum class VoiceGender(val geminiVoiceName: String, val elevenLabsVoiceId: String, val label: String) {
    MALE(
        geminiVoiceName = "Orus",
        elevenLabsVoiceId = "nPczCjzI2devNBz1zQrb", // Brian: deep, warm, comforting human male
        label = "Male"
    ),
    FEMALE(
        geminiVoiceName = "Sulafat",
        elevenLabsVoiceId = "EXAVITQu4vr4xnSDxMaL", // Soft warm female fallback
        label = "Female (Sulafat)"
    )
}

/**
 * Curated deep, warm male human voice presets paired across Gemini Live and ElevenLabs HD.
 */
enum class MaleVoicePreset(
    val geminiVoiceName: String,
    val elevenLabsVoiceId: String,
    val label: String,
    val subtitle: String
) {
    WARM_COMPANION(
        geminiVoiceName = "Orus",
        elevenLabsVoiceId = "nPczCjzI2devNBz1zQrb", // Brian
        label = "Warm Companion",
        subtitle = "Deep, relaxed & warm human tone (Orus / Brian)"
    ),
    BRITISH_JARVIS(
        geminiVoiceName = "Charon",
        elevenLabsVoiceId = "onwK4e9ZLuTAKqWW03F9", // Daniel
        label = "British JARVIS",
        subtitle = "Calm, articulate butler poise (Charon / Daniel)"
    ),
    DEEP_BARITONE(
        geminiVoiceName = "Algenib",
        elevenLabsVoiceId = "pNInz6obpgDQGcFmaJgB", // Adam
        label = "Deep Baritone",
        subtitle = "Low-pitched rich resonant timber (Algenib / Adam)"
    )
}

enum class VoicePathStatus(val label: String) {
    IDLE("Voice Ready"),
    LIVE_HD("Gemini Live HD • Wideband"),
    ELEVENLABS_HD("ElevenLabs HD • 44.1kHz"),
    DEVICE_TTS_FALLBACK("Device TTS Fallback")
}

data class PersonalInfo(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val city: String = ""
)

/**
 * Persisted ARC user & assistant settings.
 * - Removed wakeWordEnabled completely (manual mic-toggle controls listening instead).
 * - isMicMuted defaults to true on fresh install so ARC never records until user taps Mic ON;
 *   once toggled, the state persists across app restarts until changed or Good Night ARC runs.
 */
data class ArcSettingsState(
    val userName: String = "",
    val onboardingCompleted: Boolean = false,
    val permissionsReviewed: Boolean = false,
    val voiceGender: VoiceGender = VoiceGender.MALE,
    val maleVoicePreset: MaleVoicePreset = MaleVoicePreset.WARM_COMPANION,
    val isMicMuted: Boolean = true,
    val isOnline: Boolean = true,
    val antiTheftArmed: Boolean = false,
    // Controls Battery threshold alerts (20%, 15%, 10%), charging start confirmation & mobile-data warnings
    val proactiveAlertsEnabled: Boolean = true,
    val personalInfo: PersonalInfo = PersonalInfo(),
    val enabledNotificationApps: Set<String> = setOf(
        "com.whatsapp",
        "com.google.android.gm",
        "com.google.android.apps.messaging",
        "com.instagram.android"
    )
) {
    fun activeGeminiVoiceName(): String =
        if (voiceGender == VoiceGender.MALE) maleVoicePreset.geminiVoiceName else voiceGender.geminiVoiceName

    fun activeElevenLabsVoiceId(): String =
        if (voiceGender == VoiceGender.MALE) maleVoicePreset.elevenLabsVoiceId else voiceGender.elevenLabsVoiceId

    fun activeVoiceDisplayLabel(): String =
        if (voiceGender == VoiceGender.MALE) "Male • ${maleVoicePreset.label}" else voiceGender.label
}

class ArcPreferences(private val context: Context) {

    private object Keys {
        val USER_NAME = stringPreferencesKey("user_name")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PERMISSIONS_REVIEWED = booleanPreferencesKey("permissions_reviewed")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val MALE_VOICE_PRESET = stringPreferencesKey("male_voice_preset")
        val MIC_MUTED = booleanPreferencesKey("mic_muted")
        val IS_ONLINE = booleanPreferencesKey("is_online")
        val ANTI_THEFT_ARMED = booleanPreferencesKey("anti_theft_armed")
        val PROACTIVE_ALERTS_ENABLED = booleanPreferencesKey("proactive_alerts_enabled")
        val AUTOFILL_NAME = stringPreferencesKey("autofill_name")
        val AUTOFILL_EMAIL = stringPreferencesKey("autofill_email")
        val AUTOFILL_PHONE = stringPreferencesKey("autofill_phone")
        val AUTOFILL_ADDRESS = stringPreferencesKey("autofill_address")
        val AUTOFILL_CITY = stringPreferencesKey("autofill_city")
        val ENABLED_NOTIF_APPS = stringSetPreferencesKey("enabled_notif_apps")
    }

    val settingsFlow: Flow<ArcSettingsState> = context.arcDataStore.data.map { prefs ->
        val genderStr = prefs[Keys.VOICE_GENDER] ?: VoiceGender.MALE.name
        val gender = runCatching { VoiceGender.valueOf(genderStr) }.getOrDefault(VoiceGender.MALE)
        val presetStr = prefs[Keys.MALE_VOICE_PRESET] ?: MaleVoicePreset.WARM_COMPANION.name
        val preset = runCatching { MaleVoicePreset.valueOf(presetStr) }.getOrDefault(MaleVoicePreset.WARM_COMPANION)
        ArcSettingsState(
            userName = prefs[Keys.USER_NAME] ?: "",
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            permissionsReviewed = prefs[Keys.PERMISSIONS_REVIEWED] ?: false,
            voiceGender = gender,
            maleVoicePreset = preset,
            // Default to muted (OFF) until user explicitly taps Mic ON; persisted afterward
            isMicMuted = prefs[Keys.MIC_MUTED] ?: true,
            isOnline = prefs[Keys.IS_ONLINE] ?: true,
            antiTheftArmed = prefs[Keys.ANTI_THEFT_ARMED] ?: false,
            proactiveAlertsEnabled = prefs[Keys.PROACTIVE_ALERTS_ENABLED] ?: true,
            personalInfo = PersonalInfo(
                fullName = prefs[Keys.AUTOFILL_NAME] ?: (prefs[Keys.USER_NAME] ?: ""),
                email = prefs[Keys.AUTOFILL_EMAIL] ?: "",
                phone = prefs[Keys.AUTOFILL_PHONE] ?: "",
                address = prefs[Keys.AUTOFILL_ADDRESS] ?: "",
                city = prefs[Keys.AUTOFILL_CITY] ?: ""
            ),
            enabledNotificationApps = prefs[Keys.ENABLED_NOTIF_APPS] ?: setOf(
                "com.whatsapp",
                "com.google.android.gm",
                "com.google.android.apps.messaging",
                "com.instagram.android"
            )
        )
    }

    suspend fun saveUserNameAndCompleteOnboarding(name: String) {
        context.arcDataStore.edit { prefs ->
            prefs[Keys.USER_NAME] = name.trim()
            prefs[Keys.ONBOARDING_COMPLETED] = true
            if (prefs[Keys.AUTOFILL_NAME].isNullOrBlank()) {
                prefs[Keys.AUTOFILL_NAME] = name.trim()
            }
        }
    }

    suspend fun setPermissionsReviewed(reviewed: Boolean) {
        context.arcDataStore.edit { it[Keys.PERMISSIONS_REVIEWED] = reviewed }
    }

    suspend fun setVoiceGender(gender: VoiceGender) {
        context.arcDataStore.edit { it[Keys.VOICE_GENDER] = gender.name }
    }

    suspend fun setMaleVoicePreset(preset: MaleVoicePreset) {
        context.arcDataStore.edit { it[Keys.MALE_VOICE_PRESET] = preset.name }
    }

    suspend fun setMicMuted(muted: Boolean) {
        context.arcDataStore.edit { it[Keys.MIC_MUTED] = muted }
    }

    suspend fun setOnline(online: Boolean) {
        context.arcDataStore.edit { it[Keys.IS_ONLINE] = online }
    }

    suspend fun setAntiTheftArmed(armed: Boolean) {
        context.arcDataStore.edit { it[Keys.ANTI_THEFT_ARMED] = armed }
    }

    suspend fun setProactiveAlertsEnabled(enabled: Boolean) {
        context.arcDataStore.edit { it[Keys.PROACTIVE_ALERTS_ENABLED] = enabled }
    }

    suspend fun savePersonalInfo(info: PersonalInfo) {
        context.arcDataStore.edit { prefs ->
            prefs[Keys.AUTOFILL_NAME] = info.fullName.trim()
            prefs[Keys.AUTOFILL_EMAIL] = info.email.trim()
            prefs[Keys.AUTOFILL_PHONE] = info.phone.trim()
            prefs[Keys.AUTOFILL_ADDRESS] = info.address.trim()
            prefs[Keys.AUTOFILL_CITY] = info.city.trim()
        }
    }

    suspend fun toggleNotificationApp(packageName: String, enabled: Boolean) {
        context.arcDataStore.edit { prefs ->
            val current = (prefs[Keys.ENABLED_NOTIF_APPS] ?: setOf(
                "com.whatsapp",
                "com.google.android.gm",
                "com.google.android.apps.messaging",
                "com.instagram.android"
            )).toMutableSet()
            if (enabled) current.add(packageName) else current.remove(packageName)
            prefs[Keys.ENABLED_NOTIF_APPS] = current
        }
    }
}
