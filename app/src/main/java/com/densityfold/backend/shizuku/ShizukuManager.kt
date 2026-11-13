package com.densityfold.backend.shizuku

import com.densityfold.engine.DensityOutputParser


object ShizukuManager {
    fun start() = ShizukuCommandRunner.start()

    suspend fun stop() = ShizukuCommandRunner.stop()

    suspend fun awaitShizukuReady(timeoutMs: Long = 10_000L): Result<Unit> {
        return ShizukuCommandRunner.awaitReady(timeoutMs)
    }

    suspend fun setDensity(dpi: Int): Result<String> {
        return ShizukuCommandRunner.setDensity(dpi)
            .map { "DPI изменён на $dpi" }
    }

    suspend fun getCurrentDensity(): Result<Int> {
        return ShizukuCommandRunner.getCurrentDensity().mapCatching { rawOutput ->
            val density = DensityOutputParser.parse(rawOutput)
            if (density > 0) {
                density
            } else {
                throw IllegalStateException(
                    "Не удалось определить плотность. Вывод: $rawOutput"
                )
            }
        }
    }
}
