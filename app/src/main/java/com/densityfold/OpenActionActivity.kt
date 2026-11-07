package com.densityfold

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.densityfold.data.DensityPreferences
import com.densityfold.shizuku.ShizukuManager
import com.densityfold.utils.DisplayUtils
import kotlinx.coroutines.*

class OpenActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShizukuManager.isShizukuAvailable()) {
            showToast("Shizuku не активен")
            finish()
            return
        }
        applyDensity(true)
    }

    private fun applyDensity(isInner: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(applicationContext)
                val targetDpi = if (isInner) prefs.getInnerDpi() else prefs.getOuterDpi()
                val (internal, external) = DisplayUtils.identifyFoldDisplays(applicationContext)
                val display = if (isInner) internal else external
                
                if (display == null) {
                    withContext(Dispatchers.Main) { showToast("Дисплей не найден"); finish() }
                    return@launch
                }
                
                val result = ShizukuManager.setDensity(display.displayId, targetDpi)
                withContext(Dispatchers.Main) {
                    showToast(if (result.isSuccess) "Применено: $targetDpi dpi" else "Ошибка: ${result.exceptionOrNull()?.message}")
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Ошибка: ${e.message}"); finish() }
            }
        }
    }
    
    private fun showToast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
