package com.densityfold.backend

import com.densityfold.backend.root.RootBackend
import com.densityfold.backend.shizuku.ShizukuBackend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DensityBackendManager {

    private val selectionMutex = Mutex()

    @Volatile
    private var activeBackend: DensityBackend? = null

    /**
     * Вызывается явно при выборе или повторном выборе транспорта.
     * Root имеет приоритет; Shizuku используется как fallback.
     */
    suspend fun selectBackend(
        rootEnabled: Boolean,
        shizukuEnabled: Boolean,
        timeoutMs: Long = 10_000L
    ): Result<DensityBackend> = selectionMutex.withLock {

        if (!rootEnabled && !shizukuEnabled) {
            activeBackend = null
            return@withLock Result.failure(
                IllegalStateException("Все backend отключены")
            )
        }

        var rootError: Throwable? = null
        var shizukuError: Throwable? = null

        if (rootEnabled) {
            val result = RootBackend.awaitReady(timeoutMs)

            if (result.isSuccess) {
                activeBackend = RootBackend
                return@withLock Result.success(RootBackend)
            }

            rootError = result.exceptionOrNull()
        }

        if (shizukuEnabled) {
            val result = ShizukuBackend.awaitReady(timeoutMs)

            if (result.isSuccess) {
                activeBackend = ShizukuBackend
                return@withLock Result.success(ShizukuBackend)
            }

            shizukuError = result.exceptionOrNull()
        }

        val reasons = buildList {
            if (rootEnabled) {
                add("Root: ${rootError?.message ?: "недоступен"}")
            }
            if (shizukuEnabled) {
                add("Shizuku: ${shizukuError?.message ?: "недоступен"}")
            }
        }

        activeBackend = null

        Result.failure(
            IllegalStateException(
                "Не удалось выбрать backend. ${reasons.joinToString("; ")}",
                shizukuError ?: rootError
            )
        )
    }

    /**
     * Возвращает выбранный backend без повторных проверок доступности.
     */
    fun currentBackend(): DensityBackend? = activeBackend
}
