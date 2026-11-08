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

    suspend fun setDensity(displayId: Int, dpi: Int): Result<String> {
        val command = "wm density $dpi -d $displayId"
        return ShizukuCommandRunner.execute(command).map { "DPI изменён на $dpi" }
    }
    
    suspend fun getCurrentDensity(displayId: Int): Result<Int> {
        val command = "wm density -d $displayId"
        return ShizukuCommandRunner.execute(command).mapCatching { rawOutput ->
            lastRawOutput = rawOutput
            
            // Пробуем несколько форматов вывода
            // Формат 1: "Physical density: 420; override density: 360"
            val overrideRegex = Regex("override density: (\\d+)")
            val overrideMatch = overrideRegex.find(rawOutput)
            if (overrideMatch != null) {
                return@mapCatching overrideMatch.groupValues[1].toInt()
            }
            
            // Формат 2: "Override density: 360" (с большой буквы)
            val overrideRegex2 = Regex("Override density: (\\d+)")
            val overrideMatch2 = overrideRegex2.find(rawOutput)
            if (overrideMatch2 != null) {
                return@mapCatching overrideMatch2.groupValues[1].toInt()
            }
            
            // Формат 3: просто число в выводе (некоторые версии wm)
            val numberRegex = Regex("(\\d+)")
            val numbers = numberRegex.findAll(rawOutput).map { it.groupValues[1].toInt() }.toList()
            if (numbers.size >= 2) {
                // Если два числа — второе обычно override
                return@mapCatching numbers[1]
            }
            if (numbers.size == 1) {
                return@mapCatching numbers[0]
            }
            
            // Формат 4: "Physical density: 420" — берём физическую
            val physicalRegex = Regex("Physical density: (\\d+)")
            val physicalMatch = physicalRegex.find(rawOutput)
            if (physicalMatch != null) {
                return@mapCatching physicalMatch.groupValues[1].toInt()
            }
            
            throw Exception("Не удалось определить плотность. Вывод: $rawOutput")
        }
    }
}
