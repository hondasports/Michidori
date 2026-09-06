package com.michidori.app.recording

import android.content.Context

class CaptureSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun qualityProfile(): CaptureQualityProfile =
        CaptureQualityProfile.fromId(preferences.getString(KEY_QUALITY, null))

    fun setQualityProfile(profile: CaptureQualityProfile) {
        preferences.edit().putString(KEY_QUALITY, profile.id).apply()
    }

    fun lensMode(): LensMode = LensMode.fromId(preferences.getString(KEY_LENS, null))

    fun setLensMode(mode: LensMode) {
        preferences.edit().putString(KEY_LENS, mode.id).apply()
    }

    companion object {
        private const val FILE_NAME = "capture_settings"
        private const val KEY_QUALITY = "quality_profile"
        private const val KEY_LENS = "lens_mode"
    }
}
