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
        // Применяем глобально (флаг -d не работает адресно на Fold 7)
        val command = "wm density $dpi"
        return ShizukuCommandRunner.execute(command).map { "DPI изменён на $dpi" }
    }
    
    suspend fun getCurrentDensity(): Result<Int> {
        val command = "wm density"
        return ShizukuCommandRunner.execute(command).mapCatching { rawOutput ->
            lastRawOutput = rawOutput
            
            // Формат: "Physical density: 420\nOverride density: 360"
            val overrideRegex = Regex("[Oo]verride density: (\\d+)")
            val overrideMatch = overrideRegex.find(rawOutput)
            if (overrideMatch != null) {
                return@mapCatching overrideMatch.groupValues[1].toInt()
            }
            
            // Если нет override, берём физическую плотность
            val physicalRegex = Regex("Physical density: (\\d+)")
            val physicalMatch = physicalRegex.find(rawOutput)
            if (physicalMatch != null) {
                return@mapCatching physicalMatch.groupValues[1].toInt()
            }
            
            throw Exception("Не удалось определить плотность. Вывод: $rawOutput")
        }
    }
}
