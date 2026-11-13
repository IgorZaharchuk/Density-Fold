package com.densityfold.backend.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object RootCommandRunner {

    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val MAX_OUTPUT_BYTES = 64 * 1024
    private const val READ_BUFFER_SIZE = 8192

    suspend fun isAvailable(): Boolean {
        val result = executeFixed("id")
        val output = result.getOrNull() ?: return false
        return Regex("""\buid=0(?:\(|\s)""").containsMatchIn(output)
    }

    suspend fun getCurrentDensity(): Result<String> =
        executeFixed(helperCommand("get", "0"))

    suspend fun setDensity(dpi: Int): Result<String> {
        if (dpi !in 1..10000) {
            return Result.failure(
                IllegalArgumentException("Недопустимое значение DPI: $dpi")
            )
        }
        return executeFixed(helperCommand("set", "0", dpi.toString()))
    }

    /**
     * Запускает Java helper внутри root app_process.
     * Изменение плотности выполняется через Binder WindowManager,
     * а не через shell-команду wm density.
     */
    private fun helperCommand(operation: String, vararg args: String): String {
        require(operation == "get" || operation == "set")

        val arguments = args.joinToString(" ")
        return "APK_PATH=\"\$(pm path com.densityfold 2>/dev/null | " +
            "head -n 1 | cut -d: -f2)\"; " +
            "if [ -z \"\$APK_PATH\" ]; then " +
            "echo 'APK Density Fold не найден'; exit 20; fi; " +
            "CLASSPATH=\"\$APK_PATH\" /system/bin/app_process /system/bin " +
            "com.densityfold.backend.root.RootApiHelper $operation $arguments"
    }

    private suspend fun executeFixed(command: String): Result<String> {
        if (command.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Команда Root не может быть пустой")
            )
        }

        return try {
            val rawResult = runInterruptible(Dispatchers.IO) {
                executeBlocking(command)
            }

            when {
                rawResult.startsWith("SUCCESS:") ->
                    Result.success(rawResult.removePrefix("SUCCESS:").trim())

                rawResult.startsWith("ERROR:") ->
                    Result.failure(
                        IllegalStateException(rawResult.removePrefix("ERROR:").trim())
                    )

                rawResult.startsWith("EXCEPTION:") ->
                    Result.failure(
                        IllegalStateException(rawResult.removePrefix("EXCEPTION:").trim())
                    )

                else ->
                    Result.failure(
                        IllegalStateException("Неизвестный ответ Root: $rawResult")
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeBlocking(command: String): String {
        var process: Process? = null

        try {
            val runningProcess = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = runningProcess

            val output = ByteArrayOutputStream()
            val truncated = AtomicBoolean(false)

            val reader = Thread({
                drainOutput(runningProcess.inputStream, output, truncated)
            }, "DensityFold-RootOutput").apply {
                isDaemon = true
                start()
            }

            val finished = runningProcess.waitFor(
                COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            if (!finished) {
                runningProcess.destroy()
                if (!runningProcess.waitFor(500, TimeUnit.MILLISECONDS)) {
                    runningProcess.destroyForcibly()
                }
                runningProcess.inputStream.close()
                reader.join(500)

                return "ERROR:Таймаут Root helper " +
                    "(${COMMAND_TIMEOUT_SECONDS}s)"
            }

            reader.join(1000)

            val text = output.toString(Charsets.UTF_8.name()).let {
                if (truncated.get()) "$it\n[вывод обрезан: превышен лимит 64 КБ]" else it
            }

            return if (runningProcess.exitValue() == 0) {
                "SUCCESS:$text"
            } else {
                "ERROR:${runningProcess.exitValue()}:$text"
            }
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            process?.destroyForcibly()
            return "EXCEPTION:${e.javaClass.simpleName}: ${e.message ?: "ошибка Root"}"
        } finally {
            process?.let {
                if (it.isAlive) {
                    it.destroyForcibly()
                }
            }
        }
    }

    private fun drainOutput(
        input: InputStream,
        output: ByteArrayOutputStream,
        truncated: AtomicBoolean
    ) {
        val buffer = ByteArray(READ_BUFFER_SIZE)

        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break

                synchronized(output) {
                    val remaining = MAX_OUTPUT_BYTES - output.size()
                    if (remaining > 0) {
                        output.write(buffer, 0, minOf(count, remaining))
                    }
                    if (count > remaining) {
                        truncated.set(true)
                    }
                }
            }
        } catch (_: Exception) {
            // Поток может закрыться при таймауте или отмене процесса.
        }
    }
}
