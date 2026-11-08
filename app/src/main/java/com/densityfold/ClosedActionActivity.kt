package com.densityfold

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClosedActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShizukuManager.isShizukuAvailable()) {
            Toast.makeText(this, "Shizuku не активен", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(applicationContext)
                val targetDpi = prefs.getOuterDpi()
                val result = ShizukuManager.setDensity(targetDpi)
                withContext(Dispatchers.Main) {
                    val message = if (result.isSuccess) "Внешний экран: $targetDpi dpi" 
                                  else "Ошибка: ${result.exceptionOrNull()?.message}"
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(applicationContext, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}
