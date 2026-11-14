package com.densityfold.backend.root

import com.densityfold.backend.DensityBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

object RootBackend : DensityBackend {
    override val name: String = "ROOT"

    override suspend fun awaitReady(timeoutMs: Long): Result<Unit> {
        return try {
            val uid = withTimeoutOrNull(timeoutMs) {
                RootCommandRunner.getProcessUid()
            } ?: return Result.failure(
                TimeoutException(
                    "Проверка Root превысила лимит времени (${timeoutMs} мс)"
                )
            )

            if (uid != 0) {
                Result.failure(
                    IllegalStateException(
                        "Root недоступен: процесс получил UID $uid вместо UID 0"
                    )
                )
            } else {
                Result.success(Unit)
            }
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
