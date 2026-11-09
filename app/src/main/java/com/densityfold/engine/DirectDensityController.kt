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

    fun inspectRuntimeApi(context: Context): Result<String> {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("Нет WRITE_SECURE_SETTINGS"))
        }

        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val getter = global.getDeclaredMethod("getWindowManagerService")
            getter.isAccessible = true
            val service = getter.invoke(null)
                ?: error("WindowManagerService вернул null")

            val runtimeClass = service.javaClass
            val methods = (
                runtimeClass.methods.toList() +
                runtimeClass.declaredMethods.toList()
            )
                .distinctBy { it.toGenericString() }
                .filter {
                    val name = it.name.lowercase()
                    "density" in name || "forced" in name
                }
                .sortedBy { it.name }
                .joinToString("\\n") { method ->
                    val params = method.parameterTypes.joinToString(", ") {
                        it.simpleName
                    }
                    "${method.declaringClass.name}#${method.name}($params): ${method.returnType.simpleName}"
                }

            val interfaces = runtimeClass.interfaces.joinToString("\\n") {
                it.name
            }.ifBlank { "(нет непосредственно реализованных интерфейсов)" }

            Result.success(
                "Runtime-класс: ${runtimeClass.name}\\n" +
                "Суперкласс: ${runtimeClass.superclass?.name ?: "(нет)"}\\n" +
                "Интерфейсы:\\n$interfaces\\n\\n" +
                "Методы density / forced: " +
                (methods.ifBlank { "(не найдены)" })
            )
        } catch (e: Exception) {
            Result.failure(IllegalStateException(
                "Диагностика runtime API не удалась: ${e.javaClass.name}: ${e.message}", e
            ))
        } catch (e: LinkageError) {
            Result.failure(IllegalStateException(
                "Ошибка системного API: ${e.message}", e
            ))
        }
    }


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
    fun resetDensity(context: Context, displayId: Int): Result<String> {
        if (displayId < 0) {
            return Result.failure(IllegalArgumentException("Некорректный displayId: $displayId"))
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("Нет WRITE_SECURE_SETTINGS"))
        }

        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val getService = global.getDeclaredMethod("getWindowManagerService")
            getService.isAccessible = true
            val service = getService.invoke(null) ?: error("WindowManagerService вернул null")
            val iface = Class.forName("android.view.IWindowManager")
            val method = iface.getMethod(
                "clearForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val userId = Process.myUid() / 100000
            method.invoke(service, displayId, userId)
            Result.success("Сброшено переопределение DPI: displayId=$displayId")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            Result.failure(IllegalStateException(
                "IWindowManager не сбросил DPI: ${cause.message ?: cause.javaClass.simpleName}", cause
            ))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(
                "Не удалось сбросить DPI: ${e.message ?: e.javaClass.simpleName}", e
            ))
        } catch (e: LinkageError) {
            Result.failure(IllegalStateException("Ошибка системного API: ${e.message}", e))
        }
    }
}
