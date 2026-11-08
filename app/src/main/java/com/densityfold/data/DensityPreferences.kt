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
        val INNER_DPI = intPreferencesKey("inner_dpi")
        val OUTER_DPI = intPreferencesKey("outer_dpi")
        const val DEFAULT_INNER_DPI = 360
        const val DEFAULT_OUTER_DPI = 411
    }

    // Проверяем, существует ли ключ inner_dpi
    suspend fun hasInnerDpi(): Boolean {
        return context.dataStore.data.first().contains(INNER_DPI)
    }

    // Проверяем, существует ли ключ outer_dpi
    suspend fun hasOuterDpi(): Boolean {
        return context.dataStore.data.first().contains(OUTER_DPI)
    }

    suspend fun getInnerDpi(): Int = context.dataStore.data.map { 
        it[INNER_DPI] ?: DEFAULT_INNER_DPI 
    }.first()
    
    suspend fun getOuterDpi(): Int = context.dataStore.data.map { 
        it[OUTER_DPI] ?: DEFAULT_OUTER_DPI 
    }.first()
    
    suspend fun setInnerDpi(dpi: Int) { 
        context.dataStore.edit { it[INNER_DPI] = dpi } 
    }
    
    suspend fun setOuterDpi(dpi: Int) { 
        context.dataStore.edit { it[OUTER_DPI] = dpi } 
    }
}
