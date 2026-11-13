package com.densityfold.backend.root

import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal object WindowManagerDensity {

    private const val USER_CURRENT = -2

    private data class Api(
        val manager: Any,
        val interfaceClass: Class<*>
    )

    private fun api(): Api {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "window") as? IBinder
            ?: throw IllegalStateException("Binder службы WindowManager недоступен")

        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        val manager = asInterface.invoke(null, binder)
            ?: throw IllegalStateException("Не удалось получить IWindowManager")

        return Api(
            manager = manager,
            interfaceClass = Class.forName("android.view.IWindowManager")
        )
    }

    private fun invoke(method: Method, target: Any, vararg args: Any): Any? {
        try {
            return method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    fun get(displayId: Int): Int {
        val api = api()
        val method = api.interfaceClass.getMethod(
            "getBaseDisplayDensity",
            Int::class.javaPrimitiveType
        )
        val density = invoke(method, api.manager, displayId) as Int

        if (density <= 0) {
            throw IllegalStateException(
                "WindowManager вернул недопустимую плотность: $density"
            )
        }
        return density
    }

    fun set(displayId: Int, dpi: Int): Int {
        require(displayId >= 0) { "Недопустимый ID дисплея: $displayId" }
        require(dpi in 1..10000) { "Недопустимое значение DPI: $dpi" }

        val api = api()
        val method = api.interfaceClass.getMethod(
            "setForcedDisplayDensityForUser",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        invoke(method, api.manager, displayId, dpi, USER_CURRENT)

        val actual = get(displayId)
        if (actual != dpi) {
            throw IllegalStateException(
                "Проверка не пройдена: задано $dpi, получено $actual"
            )
        }
        return actual
    }
}
