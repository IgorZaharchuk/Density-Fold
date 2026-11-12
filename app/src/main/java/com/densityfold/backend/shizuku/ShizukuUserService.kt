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

    override fun executeCommand(command: String): String {
        var process: Process? = null

        return try {
            val runningProcess = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = runningProcess

            val output = ByteArrayOutputStream()
            val truncated = java.util.concurrent.atomic.AtomicBoolean(false)

            // Поток постоянно вычитывает pipe, но хранит не более 64 KiB.
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
                                if (count > remaining) {
                                    truncated.set(true)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Процесс мог быть принудительно завершён.
                }
            }, "DensityFold-command-output").apply {
                isDaemon = true
                start()
            }

            val finished = runningProcess.waitFor(
                COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            if (!finished) {
                process.destroyForcibly()
                runningProcess.waitFor(1, TimeUnit.SECONDS)
                try {
                    runningProcess.inputStream.close()
                } catch (_: Exception) {}
                reader.interrupt()
                reader.join(500L)
                return "EXCEPTION:Команда превысила лимит времени (${COMMAND_TIMEOUT_SECONDS} секунд)"
            }

            reader.join(1_000L)

            val text = synchronized(output) {
                output.toString(Charsets.UTF_8.name())
            } + if (truncated.get()) {
                "\n[output truncated: limit 64 KiB]"
            } else {
                ""
            }

            val exitCode = runningProcess.exitValue()
            if (exitCode == 0) {
                "SUCCESS:$text"
            } else {
                "ERROR:$exitCode:$text"
            }
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            "EXCEPTION:Выполнение команды прервано"
        } catch (e: Exception) {
            process?.destroyForcibly()
            "EXCEPTION:${e.message ?: e.javaClass.simpleName}"
        }
    }
}
