package com.densityfold.engine

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import java.lang.reflect.InvocationTargetException
import android.os.Process
import android.util.Log

object DirectDensityDiag {

    private const val TAG = "DirectDensityDiag"

    private const val DISPLAY_ID = 0
    private val USER_ID = Process.myUid() / 100000
    private val I = Int::class.javaPrimitiveType!!

    @Volatile private var savedInitial: Int? = null
    @Volatile private var savedBase: Int? = null

    private fun service(): Any {
        val getter = Class.forName("android.view.WindowManagerGlobal")
            .getDeclaredMethod("getWindowManagerService")
            .apply { isAccessible = true }
        return getter.invoke(null) ?: error("WindowManager service недоступен")
    }

    fun readDensities(): Pair<Int, Int> {
        val s = service()
        val initial = s.javaClass.getMethod("getInitialDisplayDensity", I)
            .invoke(s, DISPLAY_ID) as Int
        val base = s.javaClass.getMethod("getBaseDisplayDensity", I)
            .invoke(s, DISPLAY_ID) as Int
        return initial to base
    }

    private fun captureBaseline() {
        if (savedBase == null) {
            val (initial, base) = readDensities()
            savedInitial = initial
            savedBase = base
            Log.i(TAG, "baseline initial=$initial base=$base")
        }
    }

    private fun namespaces(context: Context): String {
        val resolver = context.contentResolver
        return "secure=${Settings.Secure.getString(resolver, "display_density_forced")}, " +
            "system=${Settings.System.getString(resolver, "display_density_forced")}"
    }

    private fun verify(context: Context, expected: Int): String {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(DISPLAY_ID)
            ?: error("Display $DISPLAY_ID недоступен")
        val started = SystemClock.elapsedRealtime()

        repeat(50) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.densityDpi == expected) {
                return "ПРИМЕНЕНО за ${SystemClock.elapsedRealtime() - started} мс (metrics=${metrics.densityDpi})"
            }
            Thread.sleep(100)
        }

        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return "НЕ подтверждено за 5 с: metrics=${metrics.densityDpi}, ожидалось $expected"
    }

    fun applyDensity(context: Context, density: Int): String {
        require(density in 200..1000) { "Недопустимое значение DPI: $density" }

        return try {
            captureBaseline()
            val before = namespaces(context)
            val s = service()
            val method = s.javaClass.getMethod(
                "setForcedDisplayDensityForUser", I, I, I
            )
            method.invoke(s, DISPLAY_ID, density, USER_ID)
            val verification = verify(context, density)
            "set($density) OK; $verification\nдо: $before\nпосле: ${namespaces(context)}"
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            "IPC отклонён: ${cause.javaClass.name}: ${cause.message}"
        }
    }

    fun restoreDensity(context: Context): String {
        return try {
            captureBaseline()
            val initial = savedInitial!!
            val base = savedBase!!
            val s = service()

            if (base != initial) {
                s.javaClass.getMethod(
                    "setForcedDisplayDensityForUser", I, I, I
                ).invoke(s, DISPLAY_ID, base, USER_ID)
                "Восстановлен прежний override base=$base; ${verify(context, base)}"
            } else {
                s.javaClass.getMethod(
                    "clearForcedDisplayDensityForUser", I, I
                ).invoke(s, DISPLAY_ID, USER_ID)
                "Override снят (base==initial=$initial); ${verify(context, initial)}"
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            "IPC отклонён: ${cause.javaClass.name}: ${cause.message}"
        }
    }

    fun run(context: Context): String {
        Log.i(TAG, "DIRECT_DIAG_PROBE_START pid=${Process.myPid()}")

        val out = StringBuilder()
        out.appendLine("=== DIRECT API DIAGNOSTICS ===")

        val permission = context.checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        out.appendLine("WRITE_SECURE_SETTINGS granted: $permission")
        out.appendLine()

        val interfaceClass = try {
            Class.forName("android.view.IWindowManager")
        } catch (t: Throwable) {
            out.appendLine("IWindowManager Class.forName FAILED: ${describe(t)}")
            null
        }

        if (interfaceClass != null) {
            inspectClass(interfaceClass, "IWindowManager interface", out)
        }

        var proxyClass: Class<*>? = null
        try {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            out.appendLine("WindowManagerGlobal: ${globalClass.name}")

            val getter = globalClass.getDeclaredMethod("getWindowManagerService")
            getter.isAccessible = true
            val service = getter.invoke(null)

            if (service == null) {
                out.appendLine("getWindowManagerService returned null")
            } else {
                proxyClass = service.javaClass
                out.appendLine("Actual service class: ${proxyClass.name}")
                inspectClass(proxyClass, "Actual service / Proxy", out)
            }
        } catch (t: Throwable) {
            out.appendLine("WindowManagerGlobal / service getter FAILED: ${describe(t)}")
        }

        val targets = listOfNotNull(
            interfaceClass?.let { "IWindowManager" to it },
            proxyClass?.let { "Actual Proxy" to it }
        )

        out.appendLine()
        out.appendLine("=== EXACT METHOD LOOKUP (NO INVOCATION) ===")

        for ((label, clazz) in targets) {
            out.appendLine()
            out.appendLine("[$label: ${clazz.name}]")
            checkMethod(clazz, "setForcedDisplayDensityForUser", 3, out)
            checkMethod(clazz, "clearForcedDisplayDensityForUser", 2, out)
        }

        out.appendLine()
        out.appendLine("=== OWN PROCESS LOGCAT ===")
        out.append(readOwnLogcat())

        out.appendLine()
        out.appendLine("NOTE: отсутствие ART warning в logcat не доказывает отсутствие hidden API filtering.")
        out.appendLine("Ни один метод изменения DPI не вызывался.")

        return out.toString()
    }

    private fun inspectClass(clazz: Class<*>, label: String, out: StringBuilder) {
        out.appendLine()
        out.appendLine("=== METHODS: $label ===")

        try {
            val methods = clazz.methods
                .filter { it.name.contains("density", true) || it.name.contains("forced", true) }
                .map { it.toString() }
                .distinct()
                .sorted()

            out.appendLine("public/inherited matches: ${methods.size}")
            methods.forEach { out.appendLine(it) }
        } catch (t: Throwable) {
            out.appendLine("getMethods FAILED: ${describe(t)}")
        }

        try {
            val methods = clazz.declaredMethods
                .filter { it.name.contains("density", true) || it.name.contains("forced", true) }
                .map { it.toString() }
                .distinct()
                .sorted()

            out.appendLine("declared matches: ${methods.size}")
            methods.forEach { out.appendLine(it) }
        } catch (t: Throwable) {
            out.appendLine("getDeclaredMethods FAILED: ${describe(t)}")
        }
    }

    private fun checkMethod(
        clazz: Class<*>,
        name: String,
        parameterCount: Int,
        out: StringBuilder
    ) {
        val parameterTypes = Array(parameterCount) {
            Int::class.javaPrimitiveType!!
        }

        try {
            val method = clazz.getMethod(name, *parameterTypes)
            out.appendLine("getMethod $name: FOUND — $method")
        } catch (t: Throwable) {
            out.appendLine("getMethod $name: FAILED — ${describe(t)}")
        }

        try {
            val method = clazz.getDeclaredMethod(name, *parameterTypes)
            out.appendLine("getDeclaredMethod $name: FOUND — $method")
        } catch (t: Throwable) {
            out.appendLine("getDeclaredMethod $name: FAILED — ${describe(t)}")
        }
    }

    private fun readOwnLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-b", "main",
                "-b", "system",
                "--pid=${Process.myPid()}",
                "-v", "brief"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val relevant = output.lineSequence().filter {
                val line = it.lowercase()
                listOf(
                    "hidden", "reflection", "non-sdk", "blocked",
                    "denied", "windowmanagerglobal", "iwindowmanager",
                    "directdi agprobe", "direct_diag_probe"
                ).any(line::contains)
            }.toList()

            buildString {
                appendLine("logcat exit code: $exitCode")
                if (relevant.isEmpty()) {
                    appendLine("Релевантных строк не найдено.")
                } else {
                    relevant.forEach { appendLine(it) }
                }
            }
        } catch (t: Throwable) {
            "Не удалось прочитать logcat: ${describe(t)}\n"
        }
    }

    private fun describe(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }
            .take(5)
            .joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message}" }
        return chain.ifBlank { t.toString() }
    }
}
