package com.densityfold.engine


object ShizukuManager {
    fun isShizukuAvailable(): Boolean =
        ShizukuCommandRunner.currentState().isAvailable

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

    fun parseDensity(rawOutput: String): Int =
        DensityOutputParser.parse(rawOutput)

    suspend fun getCurrentDensity(): Result<Int> {
        return ShizukuCommandRunner.execute("wm density").mapCatching { rawOutput ->
            val density = parseDensity(rawOutput)
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
