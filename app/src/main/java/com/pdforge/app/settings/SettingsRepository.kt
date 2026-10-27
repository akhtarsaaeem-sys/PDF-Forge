package com.pdforge.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_OUTPUT_LOCATION = stringPreferencesKey("default_output_location")
        val OUTPUT_FILENAME_BEHAVIOR = stringPreferencesKey("output_filename_behavior")
        val DEFAULT_OCR_LANGUAGE = stringPreferencesKey("default_ocr_language")
        val DEFAULT_COMPRESSION_QUALITY = stringPreferencesKey("default_compression_quality")
        val TEMP_FILE_CLEANUP = stringPreferencesKey("temp_file_cleanup")
    }

    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_MODE] ?: "system"
    }

    val defaultOutputLocation: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_OUTPUT_LOCATION] ?: "internal"
    }

    val outputFilenameBehavior: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[OUTPUT_FILENAME_BEHAVIOR] ?: "add_suffix"
    }

    val defaultOcrLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_OCR_LANGUAGE] ?: "eng"
    }

    val defaultCompressionQuality: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEFAULT_COMPRESSION_QUALITY] ?: "recommended"
    }

    val tempFileCleanup: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[TEMP_FILE_CLEANUP] ?: "on_exit"
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode
        }
    }

    suspend fun setDefaultOutputLocation(location: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_OUTPUT_LOCATION] = location
        }
    }

    suspend fun setOutputFilenameBehavior(behavior: String) {
        context.dataStore.edit { preferences ->
            preferences[OUTPUT_FILENAME_BEHAVIOR] = behavior
        }
    }

    suspend fun setDefaultOcrLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_OCR_LANGUAGE] = language
        }
    }

    suspend fun setDefaultCompressionQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_COMPRESSION_QUALITY] = quality
        }
    }

    suspend fun setTempFileCleanup(cleanup: String) {
        context.dataStore.edit { preferences ->
            preferences[TEMP_FILE_CLEANUP] = cleanup
        }
    }
}
