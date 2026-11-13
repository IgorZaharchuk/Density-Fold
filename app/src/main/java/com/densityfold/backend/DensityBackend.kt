package com.densityfold.backend

interface DensityBackend {
    val name: String


    suspend fun awaitReady(timeoutMs: Long = 10_000L): Result<Unit>

    suspend fun getCurrentDensity(): Result<Int>

    suspend fun setDensity(dpi: Int): Result<String>
}
