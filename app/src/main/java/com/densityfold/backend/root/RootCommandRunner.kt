package com.densityfold.backend.root

import android.content.Context
import kotlinx.coroutines.CancellationException

object RootCommandRunner {

    fun initialize(context: Context) {
        RootServiceClient.initialize(context)
    }

    suspend fun getProcessUid(): Int =
        RootServiceClient.getProcessUid()

    suspend fun getCurrentDensity(): Result<Int> {
        return try {
            val density = RootServiceClient.getCurrentDensity(0)
            if (density <= 0) {
                throw IllegalStateException(
                    "WindowManager вернул недопустимую плотность: $density"
                )
            }
            Result.success(density)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun setDensity(dpi: Int): Result<Int> {
        if (dpi !in 1..10000) {
            return Result.failure(
                IllegalArgumentException("Недопустимое значение DPI: $dpi")
            )
        }

        return try {
            val actual = RootServiceClient.setDensity(0, dpi)
            Result.success(actual)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
