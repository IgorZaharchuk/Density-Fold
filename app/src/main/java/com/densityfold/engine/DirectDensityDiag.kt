package com.densityfold.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log

object DirectDensityDiag {

    private const val TAG = "DirectDensityDiag"

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
