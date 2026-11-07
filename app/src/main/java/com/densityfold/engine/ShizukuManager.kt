package com.densityfold.engine

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (Shizuku.getUid() != -1) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setDensity(displayId: Int, dpi: Int): Result<String> {
        val command = "wm density $dpi -d $displayId"
        return ShizukuCommandRunner.execute(command).map { "DPI изменён на $dpi" }
    }
    
    suspend fun getCurrentDensity(displayId: Int): Result<Int> {
        val command = "wm density -d $displayId"
        return ShizukuCommandRunner.execute(command).mapCatching { output ->
            val regex = Regex("override density: (\\d+)")
            val match = regex.find(output)
            if (match != null) {
                match.groupValues[1].toInt()
            } else {
                val physicalRegex = Regex("Physical density: (\\d+)")
                val physicalMatch = physicalRegex.find(output)
                if (physicalMatch != null) {
                    physicalMatch.groupValues[1].toInt()
                } else {
                    throw Exception("Не удалось определить плотность: $output")
                }
            }
        }
    }
}
