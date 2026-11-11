package com.densityfold.engine

import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuUserService : IShizukuService.Stub() {
    override fun executeCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(
                InputStreamReader(process.inputStream)
            ).readText()

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                "SUCCESS:$output"
            } else {
                "ERROR:$exitCode:$output"
            }
        } catch (e: Exception) {
            "EXCEPTION:${e.message}"
        }
    }
}
