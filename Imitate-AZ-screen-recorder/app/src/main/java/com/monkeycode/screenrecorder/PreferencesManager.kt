package com.monkeycode.screenrecorder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("recorder_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): RecorderConfig {
        val audioModeOrdinal = prefs.getInt("audio_mode", AudioMode.AUTO.ordinal)
        val audioMode = AudioMode.entries.getOrElse(audioModeOrdinal) { AudioMode.AUTO }
        val qualityOrdinal = prefs.getInt("quality_preset", RecordingPreset.HIGH.ordinal)
        val qualityPreset = RecordingPreset.entries.getOrElse(qualityOrdinal) { RecordingPreset.HIGH }
        val countdown = prefs.getInt("countdown_seconds", 3)
        val showBall = prefs.getBoolean("show_floating_ball", true)
        val pattern = prefs.getString("file_name_pattern", "ScreenRecord_%Y%m%d_%H%M%S") ?: "ScreenRecord_%Y%m%d_%H%M%S"
        val uriStr = prefs.getString("output_uri", null)
        val outputUri = if (uriStr != null) Uri.parse(uriStr) else null
        return RecorderConfig(audioMode, qualityPreset, countdown, showBall, pattern, outputUri)
    }

    fun saveConfig(config: RecorderConfig) {
        prefs.edit()
            .putInt("audio_mode", config.audioMode.ordinal)
            .putInt("quality_preset", config.qualityPreset.ordinal)
            .putInt("countdown_seconds", config.countdownSeconds)
            .putBoolean("show_floating_ball", config.showFloatingBall)
            .putString("file_name_pattern", config.fileNamePattern)
            .putString("output_uri", config.outputUri?.toString())
            .apply()
    }

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", false)
    fun setDarkMode(dark: Boolean) {
        prefs.edit().putBoolean("dark_mode", dark).apply()
    }
}
