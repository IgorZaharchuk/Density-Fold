package com.densityfold.backend.shizuku

import com.densityfold.engine.IShizukuService

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ShizukuUserService : IShizukuService.Stub() {

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 15L
        private const val MAX_OUTPUT_BYTES = 64 * 1024
        private const val READ_BUFFER_SIZE = 8192
    }

    override fun getCurrentDensity(): String =
        executeWmDensity(null)

    override fun setDensity(dpi: Int): String {
        if (dpi !in 1..10000) {
            return "ERROR:Недопустимое значение DPI: $dpi"
        }
        return executeWmDensity(dpi)
    }

    private fun executeWmDensity(dpi: Int?): String {
        var process: Process? = null

        return try {
            val command = if (dpi == null) {
                listOf("wm", "density")
            } else {
                listOf("wm", "density", dpi.toString())
            }

            val runningProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process = runningProcess

            val output = ByteArrayOutputStream()
            val truncated = java.util.concurrent.atomic.AtomicBoolean(false)

            val reader = Thread({
                try {
                    runningProcess.inputStream.use { input ->
                        val buffer = ByteArray(READ_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break

                            synchronized(output) {
                                val remaining = MAX_OUTPUT_BYTES - output.size()
                                if (remaining > 0) {
                                    output.write(buffer, 0, minOf(count, remaining))
                                }
                                if (count > remaining) truncated.set(true)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Поток может закрыться при завершении процесса.
                }
            }, "DensityFold-wm-output").apply {
                isDaemon = true
                start()
            }

            val finished = runningProcess.waitFor(
                COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            if (!finished) {
                runningProcess.destroyForcibly()
                runningProcess.waitFor(1, TimeUnit.SECONDS)
                try {
                    runningProcess.inputStream.close()
                } catch (_: Exception) {}
                reader.interrupt()
                reader.join(500L)
                return "EXCEPTION:Команда wm превысила лимит времени (${COMMAND_TIMEOUT_SECONDS} секунд)"
            }

            reader.join(1_000L)

            val text = synchronized(output) {
                output.toString(Charsets.UTF_8.name())
            } + if (truncated.get()) {
                "\\n[output truncated: limit 64 KiB]"
            } else {
                ""
            }

            if (runningProcess.exitValue() == 0) {
                "SUCCESS:$text"
            } else {
                "ERROR:${runningProcess.exitValue()}:$text"
            }
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            "EXCEPTION:Выполнение команды прервано"
        } catch (e: Exception) {
            process?.destroyForcibly()
            "EXCEPTION:${e.message ?: e.javaClass.simpleName}"
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
            }
        }
    }
}
