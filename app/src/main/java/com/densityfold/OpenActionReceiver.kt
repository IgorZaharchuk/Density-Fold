package com.densityfold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ShizukuManager.isShizukuAvailable()) {
            Toast.makeText(context, "Shizuku не активен", Toast.LENGTH_SHORT).show()
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(context)
                val targetDpi = prefs.getInnerDpi()
                val display = DisplayUtils.getActiveDisplay(context)
                
                if (display == null) {
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "Дисплей не найден", Toast.LENGTH_SHORT).show() 
                    }
                    return@launch
                }
                
                val result = ShizukuManager.setDensity(display.displayId, targetDpi)
                withContext(Dispatchers.Main) {
                    val message = if (result.isSuccess) "Внутренний экран: $targetDpi dpi" 
                                  else "Ошибка: ${result.exceptionOrNull()?.message}"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show() 
                }
            }
        }
    }
}
