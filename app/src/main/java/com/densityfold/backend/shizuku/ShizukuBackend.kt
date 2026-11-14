package com.densityfold.backend.shizuku

import com.densityfold.backend.DensityBackend

object ShizukuBackend : DensityBackend {
    override val name: String = "SHIZUKU"

    override suspend fun awaitReady(timeoutMs: Long): Result<Unit> =
        ShizukuManager.awaitShizukuReady(timeoutMs)

    override suspend fun getCurrentDensity(): Result<Int> =
        ShizukuManager.getCurrentDensity()

    override suspend fun setDensity(dpi: Int): Result<String> =
        ShizukuManager.setDensity(dpi)
}
