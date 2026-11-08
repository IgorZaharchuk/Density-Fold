package com.densityfold.engine

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuCommandRunner {
    
    /**
     * Выполняет shell-команду через Shizuku.
     * Использует Shizuku.newProcess — это официальный и самый надёжный способ,
     * который исключает ошибки двойного resume и ручного управления биндером.
     */
    suspend fun execute(command: String): Result<String> {
        return try {
            // Запускаем shell-процесс с правами Shizuku
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            
            // Читаем стандартный вывод и ошибки
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            
            // Ждём завершения процесса
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(Exception("Command failed with code $exitCode: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
