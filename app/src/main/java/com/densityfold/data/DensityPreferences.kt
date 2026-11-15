package com.densityfold.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "density_prefs")

data class DensitySettings(
    val innerDpi: Int,
    val outerDpi: Int,
    val foldMonitorEnabled: Boolean,
    val autoStartEnabled: Boolean,
    val rootBackendEnabled: Boolean,
    val shizukuBackendEnabled: Boolean
)

class DensityPreferences(private val context: Context) {
    companion object {
        val INNER_DPI = intPreferencesKey("inner_dpi")
        val OUTER_DPI = intPreferencesKey("outer_dpi")
        val PREVIOUS_INNER_DPI = intPreferencesKey("previous_inner_dpi")
        val PREVIOUS_OUTER_DPI = intPreferencesKey("previous_outer_dpi")
        val REMEMBER_PREVIOUS_INNER_DPI = booleanPreferencesKey("remember_previous_inner_dpi")
        val REMEMBER_PREVIOUS_OUTER_DPI = booleanPreferencesKey("remember_previous_outer_dpi")
        val FOLD_MONITOR_ENABLED = booleanPreferencesKey("fold_monitor_enabled")
        val AUTO_START_ENABLED = booleanPreferencesKey("auto_start_enabled")
        val ROOT_BACKEND_ENABLED = booleanPreferencesKey("root_backend_enabled")
        val SHIZUKU_BACKEND_ENABLED = booleanPreferencesKey("shizuku_backend_enabled")

        const val DEFAULT_ROOT_BACKEND_ENABLED = false
        const val DEFAULT_SHIZUKU_BACKEND_ENABLED = false

        const val DEFAULT_INNER_DPI = 350
        const val DEFAULT_OUTER_DPI = 440
        const val DEFAULT_MONITOR_ENABLED = false
        const val DEFAULT_AUTO_START_ENABLED = false

        const val MIN_DPI = 200
        const val MAX_DPI = 1000
    }

    suspend fun getSettings(): DensitySettings {
        val values = context.dataStore.data.first()
        return DensitySettings(
            innerDpi = values[INNER_DPI] ?: DEFAULT_INNER_DPI,
            outerDpi = values[OUTER_DPI] ?: DEFAULT_OUTER_DPI,
            foldMonitorEnabled = values[FOLD_MONITOR_ENABLED] ?: DEFAULT_MONITOR_ENABLED,
            autoStartEnabled = values[AUTO_START_ENABLED] ?: DEFAULT_AUTO_START_ENABLED,
            rootBackendEnabled = values[ROOT_BACKEND_ENABLED] ?: DEFAULT_ROOT_BACKEND_ENABLED,
            shizukuBackendEnabled = values[SHIZUKU_BACKEND_ENABLED] ?: DEFAULT_SHIZUKU_BACKEND_ENABLED
        )
    }

    private suspend fun getDpi(
        key: Preferences.Key<Int>,
        default: Int
    ): Int = context.dataStore.data.first()[key] ?: default

    suspend fun getInnerDpi(): Int = getDpi(INNER_DPI, DEFAULT_INNER_DPI)

    suspend fun getOuterDpi(): Int = getDpi(OUTER_DPI, DEFAULT_OUTER_DPI)

    private suspend fun setDpi(key: Preferences.Key<Int>, dpi: Int) {
        context.dataStore.edit {
            it[key] = dpi.coerceIn(MIN_DPI, MAX_DPI)
        }
    }

    suspend fun setInnerDpi(dpi: Int) = setDpi(INNER_DPI, dpi)

    suspend fun setOuterDpi(dpi: Int) = setDpi(OUTER_DPI, dpi)

    fun observePreviousDpi(): Flow<Pair<Int?, Int?>> =
        context.dataStore.data.map { values ->
            values[PREVIOUS_INNER_DPI] to values[PREVIOUS_OUTER_DPI]
        }

    suspend fun armPreviousDpi(isExternal: Boolean) {
        val key = if (isExternal) {
            REMEMBER_PREVIOUS_OUTER_DPI
        } else {
            REMEMBER_PREVIOUS_INNER_DPI
        }
        context.dataStore.edit { it[key] = true }
    }

    suspend fun rememberPreviousDpiIfArmed(isExternal: Boolean, dpi: Int): Boolean {
        val triggerKey = if (isExternal) {
            REMEMBER_PREVIOUS_OUTER_DPI
        } else {
            REMEMBER_PREVIOUS_INNER_DPI
        }
        val previousKey = if (isExternal) PREVIOUS_OUTER_DPI else PREVIOUS_INNER_DPI
        var saved = false

        context.dataStore.edit { values ->
            if (values[triggerKey] == true) {
                values[previousKey] = dpi.coerceIn(MIN_DPI, MAX_DPI)
                values.remove(triggerKey)
                saved = true
            }
        }
        return saved
    }

    suspend fun setFoldMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[FOLD_MONITOR_ENABLED] = enabled }
    }

    suspend fun setAutoStartEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_START_ENABLED] = enabled }
    }

    suspend fun setRootBackendEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ROOT_BACKEND_ENABLED] = enabled }
    }

    suspend fun setShizukuBackendEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SHIZUKU_BACKEND_ENABLED] = enabled }
    }
}
