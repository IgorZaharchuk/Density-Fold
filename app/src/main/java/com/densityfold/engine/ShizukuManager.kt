package com.densityfold.engine

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (Shizuku.getUid() != -1) Shizuku.requestPermission(requestCode)
        } catch (e: Exception) { e.printStackTrace() }
    }

    suspend fun setDensity(displayId: Int, dpi: Int): Result<String> {
        return try {
            val command = "wm density $dpi -d $displayId"
            val process = ShizukuExec.newProcess(arrayOf("sh", "-c", command), null, null)
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            if (exitCode == 0) Result.success("DPI изменён на $dpi")
            else Result.failure(Exception("Ошибка: $error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getCurrentDensity(displayId: Int): Result<Int> {
        return try {
            val process = ShizukuExec.newProcess(arrayOf("sh", "-c", "wm density -d $displayId"), null, null)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val regex = Regex("override density: (\\d+)")
            val match = regex.find(output)
            if (match != null) Result.success(match.groupValues[1].toInt())
            else Result.failure(Exception("Не удалось прочитать DPI"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
