package com.densityfold.engine

import android.content.Context
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CancellationException

enum class DensityProfile {
    AUTO,
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

    suspend fun apply(
        context: Context,
        profile: DensityProfile,
        timeoutMs: Long = 10_000L,
        requireProfileMatch: Boolean = false
    ): Result<DensityApplyResult> {
        return try {
            val active = DisplayUtils.getActiveDisplay(context)
                ?: throw IllegalStateException("Не удалось определить активный экран")

            val isExternal = when (profile) {
                DensityProfile.AUTO -> active.isExternal
                DensityProfile.INNER -> false
                DensityProfile.OUTER -> true
            }

            if (requireProfileMatch && active.isExternal != isExternal) {
                throw IllegalStateException(
                    if (isExternal) {
                        "Для применения настроек внешнего экрана закройте телефон"
                    } else {
                        "Для применения настроек внутреннего экрана раскройте телефон"
                    }
                )
            }

            val shortPx = when {
                active.isExternal == isExternal -> active.shortPx
                isExternal -> DisplayUtils.getOuterShortPx(context)
                else -> DisplayUtils.getInnerShortPx(context)
            }

            val preferences = DensityPreferences(context)
            val targetDpi = if (isExternal) {
                preferences.getOuterDpi()
            } else {
                preferences.getInnerDpi()
            }

            val targetDp = DisplayUtils.dpiToDp(targetDpi, shortPx)

            ShizukuManager.awaitShizukuReady(timeoutMs).getOrThrow()

            ensureDisplayUnchanged(
                context, active, isExternal, requireProfileMatch
            )

            val currentDpi = ShizukuManager.getCurrentDensity().getOrThrow()
            val alreadyApplied = currentDpi == targetDpi

            if (!alreadyApplied) {
                ensureDisplayUnchanged(
                    context, active, isExternal, requireProfileMatch
                )
                ShizukuManager.setDensity(targetDpi).getOrThrow()
            }

            val actualDpi = ShizukuManager.getCurrentDensity().getOrThrow()

            if (actualDpi != targetDpi) {
                throw IllegalStateException(
                    "Проверка DPI не пройдена: целевой=$targetDpi, фактический=$actualDpi"
                )
            }

            ensureDisplayUnchanged(
                context, active, isExternal, requireProfileMatch
            )

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

    private fun ensureDisplayUnchanged(
        context: Context,
        initial: DisplayInfo,
        expectedExternal: Boolean,
        enforceProfile: Boolean
    ) {
        if (!enforceProfile && initial.isExternal != expectedExternal) return

        val current = DisplayUtils.getActiveDisplay(context)
            ?: throw IllegalStateException("Не удалось определить активный экран")

        if (
            current.isExternal != expectedExternal ||
            current.shortPx != initial.shortPx
        ) {
            throw IllegalStateException(
                "Экран изменился во время применения DPI; команда отменена или результат не подтверждён"
            )
        }
    }
}
