package com.densityfold.engine

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    var lastRawOutput: String = ""
        private set

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (Shizuku.getUid() != -1) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setDensity(dpi: Int): Result<String> {
        if (dpi <= 0) {
            return Result.failure(IllegalArgumentException("DPI должен быть положительным: $dpi"))
        }
        val command = "wm density $dpi"
        return ShizukuCommandRunner.execute(command).map { "DPI изменён на $dpi" }
    }
    
    suspend fun getCurrentDensity(): Result<Int> {
        val command = "wm density"
        return ShizukuCommandRunner.execute(command).mapCatching { rawOutput ->
            lastRawOutput = rawOutput
            
            // Ищем Physical density (всегда присутствует)
            val physicalRegex = Regex("Physical density:\\s*(\\d+)")
            val physicalMatch = physicalRegex.find(rawOutput)
            if (physicalMatch != null) {
                val value = physicalMatch.groupValues[1].toIntOrNull()
                if (value != null && value > 0) return@mapCatching value
            }
            
            throw Exception("Не удалось определить плотность. Вывод: $rawOutput")
        }
    }
}
