package com.densityfold.backend.root

import com.densityfold.backend.DensityBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

object RootBackend : DensityBackend {
    override val name: String = "ROOT"

    override suspend fun isAvailable(): Boolean =
        RootCommandRunner.isAvailable()

    override suspend fun awaitReady(timeoutMs: Long): Result<Unit> {
        return try {
            withTimeout(timeoutMs.coerceAtLeast(1L)) {
                val uid = RootCommandRunner.getProcessUid()
                if (uid != 0) {
                    throw IllegalStateException(
                        "Root недоступен: процесс получил UID $uid вместо UID 0"
                    )
                }
            }
            Result.success(Unit)
        } catch (error: TimeoutCancellationException) {
            Result.failure(
                IllegalStateException(
                    "Истекло время ожидания привилегированного Root-доступа",
                    error
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun getCurrentDensity(): Result<Int> =
        RootCommandRunner.getCurrentDensity()

    override suspend fun setDensity(dpi: Int): Result<String> {
        return RootCommandRunner.setDensity(dpi)
            .map { actual -> "DPI изменён на $actual" }
    }
}
