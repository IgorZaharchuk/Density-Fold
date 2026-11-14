package com.densityfold

import android.app.Application
import android.util.Log
import android.os.Build
import java.io.File
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

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").readText()
                .trimEnd('\u0000')
                .trim()
        }
        return processName == packageName
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return

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
