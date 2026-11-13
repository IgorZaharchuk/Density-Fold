package com.densityfold.backend.root

import com.densityfold.backend.DensityBackend
import com.densityfold.engine.DensityOutputParser

object RootBackend : DensityBackend {
    override val name: String = "ROOT"

    override suspend fun isAvailable(): Boolean =
        RootCommandRunner.isAvailable()

    override suspend fun awaitReady(timeoutMs: Long): Result<Unit> {
        return if (isAvailable()) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("Root недоступен или не предоставлен приложению")
            )
        }
    }

    override suspend fun getCurrentDensity(): Result<Int> {
        return RootCommandRunner.getCurrentDensity().mapCatching { output ->
            val density = DensityOutputParser.parse(output)
            if (density > 0) {
                density
            } else {
                throw IllegalStateException(
                    "Не удалось определить плотность через Root. Вывод: $output"
                )
            }
        }
    }

    override suspend fun setDensity(dpi: Int): Result<String> {
        return RootCommandRunner.setDensity(dpi)
            .map { "DPI изменён на $dpi" }
    }
}
