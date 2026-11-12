package com.densityfold.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

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

        const val MIN_DPI = 72
        const val MAX_DPI = 2000
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
