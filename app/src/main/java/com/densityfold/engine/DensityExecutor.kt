package com.densityfold.engine

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

                ShizukuManager.awaitShizukuReady(timeoutMs).getOrThrow()

                ensureDisplayUnchanged(context, active)

                val currentDpi = ShizukuManager.getCurrentDensity().getOrThrow()
                val alreadyApplied = currentDpi == targetDpi

                if (!alreadyApplied) {
                    ensureDisplayUnchanged(context, active)
                    ShizukuManager.setDensity(targetDpi).getOrThrow()
                }

                val actualDpi = ShizukuManager.getCurrentDensity().getOrThrow()

                if (actualDpi != targetDpi) {
                    throw IllegalStateException(
                        "Проверка DPI не пройдена: целевой=$targetDpi, фактический=$actualDpi"
                    )
                }

                ensureDisplayUnchanged(context, active)

                Result.success(
                    DensityApplyResult(
                        isExternal = isExternal,
                        shortPx = shortPx,
                        targetDp = targetDp,
                        targetDpi = targetDpi,
                        actualDpi = actualDpi,
                        alreadyApplied = alreadyApplied
                    )
                )
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
