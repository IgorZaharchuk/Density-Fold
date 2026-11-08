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
    
    /**
     * Определяет фактическую действующую плотность.
     * Приоритет:
     * 1. Override density — реально действующее значение (установленное через wm density).
     * 2. Physical density — заводская плотность экрана, если override не задан.
     * 3. Ошибка, если ничего не найдено или значение равно 0.
     */
    suspend fun getCurrentDensity(): Result<Int> {
        val command = "wm density"
        return ShizukuCommandRunner.execute(command).mapCatching { rawOutput ->
            lastRawOutput = rawOutput
            
            // 1. Сначала ищем Override density (фактически действующее значение)
            val overrideRegex = Regex("Override density:\\s*(\\d+)")
            val overrideMatch = overrideRegex.find(rawOutput)
            if (overrideMatch != null) {
                val value = overrideMatch.groupValues[1].toIntOrNull()
                if (value != null && value > 0) return@mapCatching value
            }
            
            // 2. Если Override нет — берём Physical density
            val physicalRegex = Regex("Physical density:\\s*(\\d+)")
            val physicalMatch = physicalRegex.find(rawOutput)
            if (physicalMatch != null) {
                val value = physicalMatch.groupValues[1].toIntOrNull()
                if (value != null && value > 0) return@mapCatching value
            }
            
            // 3. Ни одно значение не найдено или равно 0
            throw Exception("Не удалось определить плотность. Вывод: $rawOutput")
        }
    }
}
