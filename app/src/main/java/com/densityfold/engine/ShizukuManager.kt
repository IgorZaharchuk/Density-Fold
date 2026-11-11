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
