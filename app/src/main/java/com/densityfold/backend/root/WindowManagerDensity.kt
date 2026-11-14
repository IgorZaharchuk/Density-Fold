package com.densityfold.backend.root

import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal object WindowManagerDensity {

    private const val USER_CURRENT = -2

    private data class ReflectionApi(
        val getService: Method,
        val asInterface: Method,
        val getBaseDisplayDensity: Method,
        val setForcedDisplayDensityForUser: Method
    )

    private val reflection: ReflectionApi by lazy {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val interfaceClass = Class.forName("android.view.IWindowManager")

        ReflectionApi(
            getService = serviceManager.getMethod(
                "getService",
                String::class.java
            ),
            asInterface = stub.getMethod(
                "asInterface",
                IBinder::class.java
            ),
            getBaseDisplayDensity = interfaceClass.getMethod(
                "getBaseDisplayDensity",
                Int::class.javaPrimitiveType
            ),
            setForcedDisplayDensityForUser = interfaceClass.getMethod(
                "setForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        )
    }

    private fun manager(): Any {
        val api = reflection
        val binder = api.getService.invoke(null, "window") as? IBinder
            ?: throw IllegalStateException(
                "Binder службы WindowManager недоступен"
            )

        return api.asInterface.invoke(null, binder)
            ?: throw IllegalStateException(
                "Не удалось получить IWindowManager"
            )
    }

    private fun invoke(method: Method, target: Any, vararg args: Any): Any? {
        try {
            return method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private fun readDensity(
        manager: Any,
        displayId: Int
    ): Int {
        val density = invoke(
            reflection.getBaseDisplayDensity,
            manager,
            displayId
        ) as Int

        if (density <= 0) {
            throw IllegalStateException(
                "WindowManager вернул недопустимую плотность: $density"
            )
        }
        return density
    }

    fun get(displayId: Int): Int {
        require(displayId >= 0) {
            "Недопустимый ID дисплея: $displayId"
        }
        return readDensity(manager(), displayId)
    }

    fun set(displayId: Int, dpi: Int): Int {
        require(displayId >= 0) {
            "Недопустимый ID дисплея: $displayId"
        }
        require(dpi in 1..10000) {
            "Недопустимое значение DPI: $dpi"
        }

        // Binder получаем заново для каждого вызова.
        // Сам Binder/proxy не кэшируем.
        val currentManager = manager()

        invoke(
            reflection.setForcedDisplayDensityForUser,
            currentManager,
            displayId,
            dpi,
            USER_CURRENT
        )

        // Readback выполняем через тот же актуальный proxy.
        val actual = readDensity(currentManager, displayId)
        if (actual != dpi) {
            throw IllegalStateException(
                "Проверка не пройдена: задано $dpi, получено $actual"
            )
        }
        return actual
    }
}
