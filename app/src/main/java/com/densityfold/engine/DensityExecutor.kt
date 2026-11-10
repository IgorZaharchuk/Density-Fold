package com.densityfold.engine

import android.content.Context
import com.densityfold.data.DensityPreferences

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
    val actualDpi: Int
)

object DensityExecutor {

    suspend fun apply(
        context: Context,
        profile: DensityProfile,
        timeoutMs: Long = 10_000L
    ): Result<DensityApplyResult> {
        return try {
            val active = DisplayUtils.getActiveDisplay(context)
                ?: throw IllegalStateException("Не удалось определить активный экран")

            val isExternal = when (profile) {
                DensityProfile.AUTO -> active.isExternal
                DensityProfile.INNER -> false
                DensityProfile.OUTER -> true
            }

            val shortPx = when (profile) {
                DensityProfile.AUTO -> active.shortPx
                DensityProfile.INNER -> DisplayUtils.getInnerShortPx(context)
                DensityProfile.OUTER -> DisplayUtils.getOuterShortPx(context)
            }

            val preferences = DensityPreferences(context)
            val targetDp = if (isExternal) {
                preferences.getOuterDp()
            } else {
                preferences.getInnerDp()
            }

            val targetDpi = DisplayUtils.dpToDpi(targetDp, shortPx)
                ?: throw IllegalArgumentException("Некорректные параметры для расчёта DPI")

            val readiness = ShizukuManager.awaitShizukuReady(timeoutMs)
            readiness.getOrThrow()

            val currentDpi = ShizukuManager.getCurrentDensity().getOrThrow()

            if (currentDpi != targetDpi) {
                ShizukuManager.setDensity(targetDpi).getOrThrow()
            }

            val actualDpi = ShizukuManager.getCurrentDensity().getOrThrow()
            if (actualDpi != targetDpi) {
                throw IllegalStateException(
                    "Проверка DPI не пройдена: целевой=$targetDpi, фактический=$actualDpi"
                )
            }

            if (profile == DensityProfile.AUTO) {
                val currentActive = DisplayUtils.getActiveDisplay(context)
                    ?: throw IllegalStateException(
                        "Не удалось повторно определить активный экран"
                    )

                if (currentActive.isExternal != isExternal) {
                    throw IllegalStateException(
                        "Экран сменился во время применения DPI"
                    )
                }
            }

            Result.success(
                DensityApplyResult(
                    isExternal = isExternal,
                    shortPx = shortPx,
                    targetDp = targetDp,
                    targetDpi = targetDpi,
                    actualDpi = actualDpi
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
