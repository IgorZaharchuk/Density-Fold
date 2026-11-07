package com.densityfold

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.*

class ClosedActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShizukuManager.isShizukuAvailable()) {
            showToast("Shizuku не активен")
            finish()
            return
        }
        applyDensityToOuter()
    }

    private fun applyDensityToOuter() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(applicationContext)
                val targetDpi = prefs.getOuterDpi()
                val outerDisplay = DisplayUtils.getOuterDisplay(applicationContext)
                
                if (outerDisplay == null) {
                    withContext(Dispatchers.Main) { showToast("Внешний дисплей не найден"); finish() }
                    return@launch
                }
                
                val result = ShizukuManager.setDensity(outerDisplay.displayId, targetDpi)
                withContext(Dispatchers.Main) {
                    showToast(if (result.isSuccess) "Внешний: $targetDpi dpi" else "Ошибка: ${result.exceptionOrNull()?.message}")
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Ошибка: ${e.message}"); finish() }
            }
        }
    }
    
    private fun showToast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
