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

class DensityPreferences(private val context: Context) {
    companion object {
        val INNER_DP = intPreferencesKey("inner_dp")
        val OUTER_DP = intPreferencesKey("outer_dp")
        val FOLD_MONITOR_ENABLED = booleanPreferencesKey("fold_monitor_enabled")
        
        val INNER_DPI_LEGACY = intPreferencesKey("inner_dpi")
        val OUTER_DPI_LEGACY = intPreferencesKey("outer_dpi")
        
        const val DEFAULT_INNER_DP = 900
        const val DEFAULT_OUTER_DP = 411
        const val DEFAULT_MONITOR_ENABLED = false
        
        const val MIN_DP = 200
        const val MAX_DP = 1200
    }

    suspend fun getInnerDp(): Int = context.dataStore.data.first()[INNER_DP] ?: DEFAULT_INNER_DP
    suspend fun getOuterDp(): Int = context.dataStore.data.first()[OUTER_DP] ?: DEFAULT_OUTER_DP
    suspend fun isFoldMonitorEnabled(): Boolean = context.dataStore.data.first()[FOLD_MONITOR_ENABLED] ?: DEFAULT_MONITOR_ENABLED
    
    suspend fun setInnerDp(dp: Int) { context.dataStore.edit { it[INNER_DP] = dp.coerceIn(MIN_DP, MAX_DP) } }
    suspend fun setOuterDp(dp: Int) { context.dataStore.edit { it[OUTER_DP] = dp.coerceIn(MIN_DP, MAX_DP) } }
    suspend fun setFoldMonitorEnabled(enabled: Boolean) { context.dataStore.edit { it[FOLD_MONITOR_ENABLED] = enabled } }
    
    suspend fun migrateLegacyDpiToDp(innerShortPx: Int, outerShortPx: Int) {
        val prefs = context.dataStore.data.first()
        
        if (!prefs.contains(INNER_DP) && prefs.contains(INNER_DPI_LEGACY)) {
            val legacyDpi = prefs[INNER_DPI_LEGACY]
            val newDp = if (legacyDpi != null && legacyDpi > 0 && innerShortPx > 0) {
                (innerShortPx * 160f / legacyDpi).toInt().coerceIn(MIN_DP, MAX_DP)
            } else { DEFAULT_INNER_DP }
            context.dataStore.edit { store -> store[INNER_DP] = newDp; store.remove(INNER_DPI_LEGACY) }
        }
        
        if (!prefs.contains(OUTER_DP) && prefs.contains(OUTER_DPI_LEGACY)) {
            val legacyDpi = prefs[OUTER_DPI_LEGACY]
            val newDp = if (legacyDpi != null && legacyDpi > 0 && outerShortPx > 0) {
                (outerShortPx * 160f / legacyDpi).toInt().coerceIn(MIN_DP, MAX_DP)
            } else { DEFAULT_OUTER_DP }
            context.dataStore.edit { store -> store[OUTER_DP] = newDp; store.remove(OUTER_DPI_LEGACY) }
        }
    }
}
