package com.densityfold.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "density_prefs")

class DensityPreferences(private val context: Context) {
    companion object {
        val INNER_DP = intPreferencesKey("inner_dp")  // Теперь храним dp, не dpi!
        val OUTER_DP = intPreferencesKey("outer_dp")
        const val DEFAULT_INNER_DP = 411  // Стандартное значение для Fold
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
    
    // Для обратной совместимости (если вдруг есть старые dpi значения)
    suspend fun getInnerDpi(): Int = getInnerDp()
    suspend fun getOuterDpi(): Int = getOuterDp()
    suspend fun setInnerDpi(dpi: Int) { setInnerDp(dpi) }
    suspend fun setOuterDpi(dpi: Int) { setOuterDp(dpi) }
}
