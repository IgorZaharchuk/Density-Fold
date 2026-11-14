package com.densityfold

import android.app.Application
import android.util.Log
import com.densityfold.backend.DensityBackendManager
import com.densityfold.backend.root.RootCommandRunner
import com.densityfold.backend.shizuku.ShizukuRecovery
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        RootCommandRunner.initialize(applicationContext)
        ShizukuRecovery.initialize(applicationContext)

        startupScope.launch {
            try {
                DensityBackendManager.selectBackend(
                    settingsProvider = {
                        DensityPreferences(applicationContext).getSettings()
                    }
                ).onFailure {
                    Log.w("DensityFold", "Не удалось выбрать backend: ${it.message}")
                }
            } catch (e: Exception) {
                Log.e("DensityFold", "Ошибка инициализации backend", e)
            }
        }
    }
}
