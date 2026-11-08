package com.densityfold.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "density_prefs")

class DensityPreferences(private val context: Context) {
    companion object {
        // Новые ключи (dp значения)
        val INNER_DP = intPreferencesKey("inner_dp")
        val OUTER_DP = intPreferencesKey("outer_dp")
        
        // Старые ключи (dpi значения) — для миграции
        val INNER_DPI_LEGACY = intPreferencesKey("inner_dpi")
        val OUTER_DPI_LEGACY = intPreferencesKey("outer_dpi")
        
        const val DEFAULT_INNER_DP = 411
        const val DEFAULT_OUTER_DP = 411
    }

    suspend fun hasInnerDp(): Boolean = context.dataStore.data.first().contains(INNER_DP)
    suspend fun hasOuterDp(): Boolean = context.dataStore.data.first().contains(OUTER_DP)

    suspend fun getInnerDp(): Int = context.dataStore.data.map { 
        it[INNER_DP] ?: DEFAULT_INNER_DP 
    }.first()
    
    suspend fun getOuterDp(): Int = context.dataStore.data.map { 
        it[OUTER_DP] ?: DEFAULT_OUTER_DP 
    }.first()
    
    suspend fun setInnerDp(dp: Int) { 
        context.dataStore.edit { it[INNER_DP] = dp } 
    }
    
    suspend fun setOuterDp(dp: Int) { 
        context.dataStore.edit { it[OUTER_DP] = dp } 
    }
    
    /**
     * Миграция: если есть старые dpi значения, пересчитываем в dp.
     * Возвращает true, если миграция произошла.
     */
    suspend fun migrateLegacyDpiToDp(innerShortPx: Int, outerShortPx: Int): Boolean {
        var migrated = false
        
        context.dataStore.edit { store ->
            // Миграция внутреннего
            if (!store.contains(INNER_DP) && store.contains(INNER_DPI_LEGACY)) {
                val legacyDpi = store[INNER_DPI_LEGACY]
                if (legacyDpi != null && legacyDpi > 0 && innerShortPx > 0) {
                    // dp = shortPx * 160 / dpi
                    val dp = (innerShortPx * 160f / legacyDpi).toInt().coerceIn(200, 800)
                    store[INNER_DP] = dp
                } else {
                    store[INNER_DP] = DEFAULT_INNER_DP
                }
                store.remove(INNER_DPI_LEGACY)
                migrated = true
            }
            
            // Миграция внешнего
            if (!store.contains(OUTER_DP) && store.contains(OUTER_DPI_LEGACY)) {
                val legacyDpi = store[OUTER_DPI_LEGACY]
                if (legacyDpi != null && legacyDpi > 0 && outerShortPx > 0) {
                    val dp = (outerShortPx * 160f / legacyDpi).toInt().coerceIn(200, 800)
                    store[OUTER_DP] = dp
                } else {
                    store[OUTER_DP] = DEFAULT_OUTER_DP
                }
                store.remove(OUTER_DPI_LEGACY)
                migrated = true
            }
        }
        
        return migrated
    }
}
