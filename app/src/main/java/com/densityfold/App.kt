package com.densityfold

import android.app.Application
import android.os.Process
import android.util.Log
import com.densityfold.backend.DensityBackendManager
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    private val startupScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Log.i("DensityFold", "APP onCreate pid=${Process.myPid()}")

        startupScope.launch {
            try {
                val settings = DensityPreferences(applicationContext).getSettings()

                DensityBackendManager.selectBackend(
                    rootEnabled = settings.rootBackendEnabled,
                    shizukuEnabled = settings.shizukuBackendEnabled
                ).onFailure {
                    Log.w("DensityFold", "Не удалось выбрать backend: ${it.message}")
                }
            } catch (e: Exception) {
                Log.e("DensityFold", "Ошибка инициализации backend", e)
            }
        }
    }
}
