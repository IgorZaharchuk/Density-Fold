package com.densityfold.engine

import com.densityfold.backend.DensityBackendManager
import com.densityfold.backend.getBackendOrThrow

import android.content.Context
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DensityProfile {
    INNER,
    OUTER
}

data class DensityApplyResult(
    val isExternal: Boolean,
    val shortPx: Int,
    val targetDp: Int,
    val targetDpi: Int,
    val actualDpi: Int,
    val alreadyApplied: Boolean
)

object DensityExecutor {

    private val applyMutex = Mutex()

    suspend fun apply(
        context: Context,
        profile: DensityProfile,
        timeoutMs: Long = 10_000L
    ): Result<DensityApplyResult> {
        return applyMutex.withLock {
            try {
                val active = DisplayUtils.getActiveDisplay(context)
                    ?: throw IllegalStateException("Не удалось определить активный экран")

                val isExternal = profile == DensityProfile.OUTER

                if (active.isExternal != isExternal) {
                    throw IllegalStateException(
                        if (isExternal) {
                            "Для применения настроек внешнего экрана закройте телефон"
                        } else {
                            "Для применения настроек внутреннего экрана раскройте телефон"
                        }
                    )
                }

                val shortPx = active.shortPx

                val preferences = DensityPreferences(context)
                val targetDpi = if (isExternal) {
                    preferences.getOuterDpi()
                } else {
                    preferences.getInnerDpi()
                }

                val targetDp = DisplayUtils.dpiToDp(targetDpi, shortPx)

                val settings = preferences.getSettings()
                val result = DensityBackendManager.withBackend(
                    rootEnabled = settings.rootBackendEnabled,
                    shizukuEnabled = settings.shizukuBackendEnabled,
                    timeoutMs = timeoutMs
                ) { backend ->
                    backend.awaitReady(timeoutMs).getBackendOrThrow()
                    ensureDisplayUnchanged(context, active)

                    val currentDpi = backend.getCurrentDensity().getBackendOrThrow()
                    val alreadyApplied = currentDpi == targetDpi

                    val actualDpi = if (alreadyApplied) {
                        currentDpi
                    } else {
                        ensureDisplayUnchanged(context, active)
                        backend.setDensity(targetDpi).getBackendOrThrow()
                        backend.getCurrentDensity().getBackendOrThrow()
                    }

                    if (actualDpi != targetDpi) {
                        throw IllegalStateException(
                            "Проверка DPI не пройдена: целевой=$targetDpi, фактический=$actualDpi"
                        )
                    }

                    ensureDisplayUnchanged(context, active)
                    DensityApplyResult(
                        isExternal = isExternal,
                        shortPx = shortPx,
                        targetDp = targetDp,
                        targetDpi = targetDpi,
                        actualDpi = actualDpi,
                        alreadyApplied = alreadyApplied
                    )
                }
                Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun readCurrentDensity(
        context: Context,
        timeoutMs: Long = 10_000L
    ): Result<Int> {
        return applyMutex.withLock {
            try {
                val active = DisplayUtils.getActiveDisplay(context)
                    ?: throw IllegalStateException("Не удалось определить активный экран")

                val settings = DensityPreferences(context).getSettings()
                val currentDpi = DensityBackendManager.withBackend(
                    rootEnabled = settings.rootBackendEnabled,
                    shizukuEnabled = settings.shizukuBackendEnabled,
                    timeoutMs = timeoutMs
                ) { backend ->
                    backend.awaitReady(timeoutMs).getBackendOrThrow()
                    ensureDisplayUnchanged(context, active)
                    val dpi = backend.getCurrentDensity().getBackendOrThrow()
                    ensureDisplayUnchanged(context, active)
                    dpi
                }
                Result.success(currentDpi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun adjustCurrent(
        context: Context,
        delta: Int,
        timeoutMs: Long = 10_000L
    ): Result<DensityApplyResult> {
        require(delta == -20 || delta == 20) {
            "Поддерживается изменение только на 20 DPI"
        }

        return applyMutex.withLock {
            try {
                val active = DisplayUtils.getActiveDisplay(context)
                    ?: throw IllegalStateException("Не удалось определить активный экран")

                val preferences = DensityPreferences(context)
                val settings = preferences.getSettings()

                val result = DensityBackendManager.withBackend(
                    rootEnabled = settings.rootBackendEnabled,
                    shizukuEnabled = settings.shizukuBackendEnabled,
                    timeoutMs = timeoutMs
                ) { backend ->
                    backend.awaitReady(timeoutMs).getBackendOrThrow()
                    ensureDisplayUnchanged(context, active)

                    val currentDpi = backend.getCurrentDensity().getBackendOrThrow()
                    val targetDpi = (currentDpi + delta).coerceIn(
                        DensityPreferences.MIN_DPI,
                        DensityPreferences.MAX_DPI
                    )

                    val alreadyApplied = targetDpi == currentDpi

                    if (!alreadyApplied) {
                        ensureDisplayUnchanged(context, active)
                        backend.setDensity(targetDpi).getBackendOrThrow()
                    }

                    val actualDpi = backend.getCurrentDensity().getBackendOrThrow()
                    if (actualDpi != targetDpi) {
                        throw IllegalStateException(
                            "Проверка DPI не пройдена: целевой=$targetDpi, фактический=$actualDpi"
                        )
                    }

                    ensureDisplayUnchanged(context, active)

                    DensityApplyResult(
                        isExternal = active.isExternal,
                        shortPx = active.shortPx,
                        targetDp = DisplayUtils.dpiToDp(targetDpi, active.shortPx),
                        targetDpi = targetDpi,
                        actualDpi = actualDpi,
                        alreadyApplied = alreadyApplied
                    )
                }
                Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun ensureDisplayUnchanged(
        context: Context,
        initial: DisplayInfo
    ) {
        val current = DisplayUtils.getActiveDisplay(context)
            ?: throw IllegalStateException("Не удалось определить активный экран")

        if (
            current.isExternal != initial.isExternal ||
            current.shortPx != initial.shortPx
        ) {
            throw IllegalStateException(
                "Экран изменился во время применения DPI; команда отменена или результат не подтверждён"
            )
        }
    }
}
