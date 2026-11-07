package com.densityfold.shizuku

import android.content.pm.PackageManager

object ShizukuManager {
    fun isShizukuAvailable(): Boolean {
        return try {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { 
            false 
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (rikka.shizuku.Shizuku.getUid() != -1) {
                rikka.shizuku.Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }

    suspend fun setDensity(displayId: Int, dpi: Int): Result<String> {
        return try {
            val command = "wm density $dpi -d $displayId"
            // Явное указание полного пути к классу Shizuku решает проблему видимости
            val process = rikka.shizuku.Shizuku.newProcess(
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
