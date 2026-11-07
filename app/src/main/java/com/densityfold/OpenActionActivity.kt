package com.densityfold

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.*

class OpenActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShizukuManager.isShizukuAvailable()) {
            showToast("Shizuku не активен")
            finish()
            return
        }
        applyDensityToInner()
    }

    private fun applyDensityToInner() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(applicationContext)
                val targetDpi = prefs.getInnerDpi()
                val innerDisplay = DisplayUtils.getInnerDisplay(applicationContext)
                
                if (innerDisplay == null) {
                    withContext(Dispatchers.Main) { showToast("Внутренний дисплей не найден"); finish() }
                    return@launch
                }
                
                val result = ShizukuManager.setDensity(innerDisplay.displayId, targetDpi)
                withContext(Dispatchers.Main) {
                    showToast(if (result.isSuccess) "Внутренний: $targetDpi dpi" else "Ошибка: ${result.exceptionOrNull()?.message}")
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Ошибка: ${e.message}"); finish() }
            }
        }
    }
    
    private fun showToast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
