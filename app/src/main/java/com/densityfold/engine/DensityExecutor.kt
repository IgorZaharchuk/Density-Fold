package com.densityfold.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.view.Display
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class DensityProfile {
    INNER,
    OUTER
}

data class DensityApplyResult(
    val isExternal: Boolean,
    val targetDpi: Int,
    val actualDpi: Int,
    val alreadyApplied: Boolean
)

object DensityExecutor {

    private val applyMutex = Mutex()
    private const val DISPLAY_ID = 0
    private val USER_ID = Process.myUid() / 100000
    private val INT = Int::class.javaPrimitiveType!!

    private fun windowManagerService(): Any {
        val getter = Class.forName("android.view.WindowManagerGlobal")
            .getDeclaredMethod("getWindowManagerService")
            .apply { isAccessible = true }

        return getter.invoke(null)
            ?: error("WindowManager service недоступен")
    }

    private fun checkPermission(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            error("Нет разрешения WRITE_SECURE_SETTINGS. Выдайте его через Shizuku.")
        }
    }

    private suspend fun setDensity(context: Context, dpi: Int) {
        checkPermission(context)

        withContext(Dispatchers.IO) {
            try {
                val service = windowManagerService()
                val method = service.javaClass.getMethod(
                    "setForcedDisplayDensityForUser",
                    INT, INT, INT
                )
                method.invoke(service, DISPLAY_ID, dpi, USER_ID)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw IllegalStateException(
                    "Не удалось изменить DPI: " +
                        (e.targetException?.message ?: e.message),
                    e.targetException ?: e
                )
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Ошибка прямого DPI API: ${e.message}", e
                )
            }
        }
    }

    private fun currentDensity(context: Context): Int {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE)
            as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Основной дисплей недоступен")

        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics.densityDpi
    }

    private suspend fun applyAndVerify(
        context: Context,
        dpi: Int,
        timeoutMs: Long
    ): Int {
        setDensity(context, dpi)

        return withTimeout(timeoutMs) {
            while (true) {
                val actual = currentDensity(context)
                if (actual == dpi) return@withTimeout actual
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            dpi
        }
    }

    suspend fun apply(
        context: Context,
        profile: DensityProfile,
        timeoutMs: Long = 10_000L
    ): Result<DensityApplyResult> {
        return applyMutex.withLock {
            try {
                val active = DisplayUtils.getActiveDisplay(context)
                    ?: error("Не удалось определить активный экран")

                val isExternal = profile == DensityProfile.OUTER
                if (active.isExternal != isExternal) {
                    error(
                        if (isExternal)
                            "Для применения настроек внешнего экрана закройте телефон"
                        else
                            "Для применения настроек внутреннего экрана раскройте телефон"
                    )
                }

                val preferences = DensityPreferences(context)
                val targetDpi = if (isExternal)
                    preferences.getOuterDpi()
                else
                    preferences.getInnerDpi()

                val allowed = DisplayUtils.allowedDpiRange(active.shortPx)
                if (targetDpi !in allowed) {
                    error(
                        "Профиль содержит $targetDpi DPI, допустимо: " +
                            "${allowed.first}–${allowed.last} DPI"
                    )
                }

                ensureDisplayUnchanged(context, active)
                val current = currentDensity(context)
                val alreadyApplied = current == targetDpi

                val actual = if (alreadyApplied) {
                    current
                } else {
                    ensureDisplayUnchanged(context, active)
                    applyAndVerify(context, targetDpi, timeoutMs)
                }

                ensureDisplayUnchanged(context, active)

                if (!alreadyApplied) {
                    preferences.rememberPreviousDpiIfArmed(isExternal, current)
                }

                Result.success(
                    DensityApplyResult(
                        isExternal, targetDpi, actual, alreadyApplied
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun readCurrentDensity(
        context: Context
    ): Result<Int> {
        return applyMutex.withLock {
            try {
                val active = DisplayUtils.getActiveDisplay(context)
                    ?: error("Не удалось определить активный экран")
                ensureDisplayUnchanged(context, active)
                val dpi = currentDensity(context)
                ensureDisplayUnchanged(context, active)
                Result.success(dpi)
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
        require(delta == -50 || delta == 50) {
            "Поддерживается изменение только на 50 DPI"
        }

        return applyMutex.withLock {
            try {
                val active = DisplayUtils.getActiveDisplay(context)
                    ?: error("Не удалось определить активный экран")
                ensureDisplayUnchanged(context, active)

                val current = currentDensity(context)
                val target = current + delta
                val allowed = DisplayUtils.allowedDpiRange(active.shortPx)

                if (target !in allowed) {
                    error(
                        "Достигнута граница диапазона: " +
                            "${allowed.first}–${allowed.last} DPI"
                    )
                }

                val actual = applyAndVerify(context, target, timeoutMs)
                ensureDisplayUnchanged(context, active)

                DensityPreferences(context)
                    .rememberPreviousDpiIfArmed(active.isExternal, current)

                Result.success(
                    DensityApplyResult(
                        active.isExternal, target, actual, false
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
            ?: error("Не удалось определить активный экран")

        if (
            current.isExternal != initial.isExternal ||
            current.shortPx != initial.shortPx
        ) {
            error("Экран изменился во время применения DPI")
        }
    }
}
