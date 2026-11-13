package com.densityfold.backend.root

import android.content.Context
import kotlinx.coroutines.CancellationException

object RootCommandRunner {

    fun initialize(context: Context) {
        RootServiceClient.initialize(context)
    }

    suspend fun isAvailable(): Boolean {
        return try {
            RootServiceClient.getProcessUid() == 0
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getCurrentDensity(): Result<String> {
        return try {
            val density = RootServiceClient.getCurrentDensity(0)
            if (density <= 0) {
                throw IllegalStateException(
                    "WindowManager вернул недопустимую плотность: $density"
                )
            }
            Result.success("Override density: $density")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun setDensity(dpi: Int): Result<String> {
        if (dpi !in 1..10000) {
            return Result.failure(
                IllegalArgumentException("Недопустимое значение DPI: $dpi")
            )
        }

        return try {
            val actual = RootServiceClient.setDensity(0, dpi)
            Result.success("Override density: $actual")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
