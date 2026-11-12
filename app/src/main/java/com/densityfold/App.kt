package com.densityfold

import android.app.Application
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.densityfold.backend.DensityBackendManager
import com.densityfold.backend.shizuku.ShizukuManager
import com.densityfold.backend.shizuku.ShizukuRecovery
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class App : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ShizukuRecovery.initialize(applicationContext)
        Log.i("DensityFold", "APP onCreate pid=${Process.myPid()}")

        startupScope.launch {
            try {
                val settings = DensityPreferences(applicationContext).getSettings()

                if (settings.shizukuBackendEnabled) {
                    ShizukuManager.start()
                    val permissionGranted = try {
                        Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    } catch (_: Exception) {
                        false
                    }

                    if (!permissionGranted) {
                        Log.i("DensityFold", "Backend selection deferred until Shizuku permission flow")
                        return@launch
                    }
                }

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
