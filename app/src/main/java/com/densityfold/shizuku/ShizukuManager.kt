package com.densityfold.shizuku

import android.content.pm.PackageManager
// Используем псевдоним, чтобы избежать конфликта с именем пакета
import rikka.shizuku.Shizuku as ShizukuApi

object ShizukuManager {
    fun isShizukuAvailable(): Boolean {
        return try {
            ShizukuApi.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { 
            false 
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (ShizukuApi.getUid() != -1) {
                ShizukuApi.requestPermission(requestCode)
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }

    suspend fun setDensity(displayId: Int, dpi: Int): Result<String> {
        return try {
            val command = "wm density $dpi -d $displayId"
            val process = ShizukuApi.newProcess(
                arrayOf("sh", "-c", command), 
                null, 
                null
            )
            
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Result.success("DPI изменён на $dpi")
            } else {
                Result.failure(Exception("Ошибка выполнения: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
