package com.densityfold.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.densityfold.engine.DisplayUtils
import kotlinx.coroutines.flow.first

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "density_prefs")

class DensityPreferences(private val context: Context) {
    companion object {
        val INNER_DPI = intPreferencesKey("inner_dpi")
        val OUTER_DPI = intPreferencesKey("outer_dpi")
        val FOLD_MONITOR_ENABLED = booleanPreferencesKey("fold_monitor_enabled")
        val AUTO_START_ENABLED = booleanPreferencesKey("auto_start_enabled")

        const val DEFAULT_INNER_DP = 900
        const val DEFAULT_OUTER_DPI = 440
        const val DEFAULT_MONITOR_ENABLED = false
        const val DEFAULT_AUTO_START_ENABLED = false

        const val MIN_DPI = 72
        const val MAX_DPI = 2000
    }

    suspend fun getInnerDpi(): Int {
        val stored = context.dataStore.data.first()[INNER_DPI]
        return stored ?: DisplayUtils.dpToDpi(
            DEFAULT_INNER_DP,
            DisplayUtils.getInnerShortPx(context)
        ) ?: 350
    }

    suspend fun getOuterDpi(): Int {
        val stored = context.dataStore.data.first()[OUTER_DPI]
        return stored ?: DEFAULT_OUTER_DPI
    }

    suspend fun isFoldMonitorEnabled(): Boolean =
        context.dataStore.data.first()[FOLD_MONITOR_ENABLED] ?: DEFAULT_MONITOR_ENABLED

    suspend fun isAutoStartEnabled(): Boolean =
        context.dataStore.data.first()[AUTO_START_ENABLED] ?: DEFAULT_AUTO_START_ENABLED

    suspend fun setInnerDpi(dpi: Int) {
        context.dataStore.edit {
            it[INNER_DPI] = dpi.coerceIn(MIN_DPI, MAX_DPI)
        }
    }

    suspend fun setOuterDpi(dpi: Int) {
        context.dataStore.edit {
            it[OUTER_DPI] = dpi.coerceIn(MIN_DPI, MAX_DPI)
        }
    }

    suspend fun setFoldMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[FOLD_MONITOR_ENABLED] = enabled }
    }

    suspend fun setAutoStartEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_START_ENABLED] = enabled }
    }
}
