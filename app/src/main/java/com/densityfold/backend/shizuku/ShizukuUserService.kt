package com.densityfold.backend.shizuku

import com.densityfold.engine.IShizukuService
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuUserService : IShizukuService.Stub() {

    companion object {
        private const val ALLOWED_PACKAGE = "com.densityfold"
        private const val PERMISSION =
            "android.permission.WRITE_SECURE_SETTINGS"

        private const val COMMAND_TIMEOUT_SECONDS = 15L
        private const val MAX_OUTPUT_BYTES = 16 * 1024
        private const val READ_BUFFER_SIZE = 4096
    }

    override fun grantWriteSecureSettings(packageName: String): String {
        if (packageName != ALLOWED_PACKAGE) {
            return "ERROR:Недопустимый package: $packageName"
        }

        return grantPermission()
    }

    private fun grantPermission(): String {
        var process: Process? = null

        return try {
            val runningProcess = ProcessBuilder(
                "pm",
                "grant",
                ALLOWED_PACKAGE,
                PERMISSION
            )
                .redirectErrorStream(true)
                .start()

            process = runningProcess

            val output = ByteArrayOutputStream()
            val truncated = AtomicBoolean(false)

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
                    // Поток может закрыться при завершении процесса.
                }
            }, "DensityFold-pm-output").apply {
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
                } catch (_: Exception) {
                }

                reader.interrupt()
                reader.join(500L)

                return "EXCEPTION:Команда pm grant превысила лимит времени"
            }

            reader.join(1_000L)

            val text = synchronized(output) {
                output.toString(Charsets.UTF_8.name())
            }

            val suffix = if (truncated.get()) {
                "\n[output truncated]"
            } else {
                ""
            }

            if (runningProcess.exitValue() == 0) {
                "SUCCESS:$text$suffix"
            } else {
                "ERROR:${runningProcess.exitValue()}:$text$suffix"
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
