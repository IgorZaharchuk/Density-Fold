package com.densityfold.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.lang.reflect.InvocationTargetException

/**
 * Экспериментальный прямой вызов IWindowManager.
 *
 * Пока не подключён к основному механизму применения DPI.
 */
object DirectDensityController {

    fun setDensity(
        context: Context,
        displayId: Int,
        density: Int
    ): Result<String> {
        if (displayId < 0) {
            return Result.failure(
                IllegalArgumentException("Некорректный displayId: $displayId")
            )
        }

        if (density !in 72..2000) {
            return Result.failure(
                IllegalArgumentException("Некорректное значение DPI: $density")
            )
        }

        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(
                SecurityException(
                    "Нет WRITE_SECURE_SETTINGS. Сначала нужно выдать разрешение."
                )
            )
        }

        return try {
            val windowManagerGlobal =
                Class.forName("android.view.WindowManagerGlobal")

            val getService = windowManagerGlobal.getDeclaredMethod(
                "getWindowManagerService"
            )
            getService.isAccessible = true

            val windowManagerService = getService.invoke(null)
                ?: error("WindowManagerService вернул null")

            val windowManagerInterface =
                Class.forName("android.view.IWindowManager")

            val method = windowManagerInterface.getMethod(
                "setForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            val userId = Process.myUid() / 100000
            method.invoke(windowManagerService, displayId, density, userId)

            Result.success(
                "Прямой вызов выполнен: displayId=$displayId, DPI=$density, userId=$userId"
            )
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            Result.failure(
                IllegalStateException(
                    "IWindowManager отклонил вызов: ${cause.message ?: cause.javaClass.simpleName}",
                    cause
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Прямое управление DPI недоступно: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            )
        } catch (e: LinkageError) {
            Result.failure(
                IllegalStateException(
                    "Ошибка доступа к системному API: ${e.message}",
                    e
                )
            )
        }
    }
}
