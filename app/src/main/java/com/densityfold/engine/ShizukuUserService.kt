package com.densityfold.engine

import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuUserService : IShizukuService.Stub() {
    override fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                "SUCCESS:$output"
            } else {
                "ERROR:$exitCode:$error"
            }
        } catch (e: Exception) {
            "EXCEPTION:${e.message}"
        }
    }
}
