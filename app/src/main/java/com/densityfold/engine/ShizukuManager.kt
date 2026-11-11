package com.densityfold.engine

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                Shizuku.pingBinder()
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

    suspend fun awaitShizukuReady(timeoutMs: Long = 10_000L): Result<Unit> {
        return ShizukuCommandRunner.awaitReady(timeoutMs)
    }

    suspend fun setDensity(dpi: Int): Result<String> {
        if (dpi <= 0) {
            return Result.failure(IllegalArgumentException("DPI должен быть положительным: $dpi"))
        }
        val command = "wm density $dpi"
        return ShizukuCommandRunner.execute(command).map { "DPI изменён на $dpi" }
    }

    fun parseDensity(rawOutput: String): Int {
        if (rawOutput.isBlank()) return 0

        val overrideRegex = Regex("Override density:\\s*(\\d+)")
        val overrideMatch = overrideRegex.find(rawOutput)
        if (overrideMatch != null) {
            val value = overrideMatch.groupValues[1].toIntOrNull()
            if (value != null && value > 0) return value
        }

        val physicalRegex = Regex("Physical density:\\s*(\\d+)")
        val physicalMatch = physicalRegex.find(rawOutput)
        if (physicalMatch != null) {
            val value = physicalMatch.groupValues[1].toIntOrNull()
            if (value != null && value > 0) return value
        }

        return 0
    }

    suspend fun getCurrentDensity(): Result<Int> {
        val command = "wm density"
        return ShizukuCommandRunner.execute(command).fold(
            onSuccess = { rawOutput ->
                val density = parseDensity(rawOutput)
                if (density > 0) {
                    Result.success(density)
                } else {
                    Result.failure(Exception("Не удалось определить плотность. Вывод: $rawOutput"))
                }
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }
}
