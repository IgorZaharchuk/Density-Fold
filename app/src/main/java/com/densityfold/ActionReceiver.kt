package com.densityfold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        // Определяем, какое действие требуется, на основе строки действия Intent
        val isOuter = action == "com.densityfold.ACTION_CLOSED"
        val screenName = if (isOuter) "Внешний" else "Внутренний"
        
        if (!ShizukuManager.isShizukuAvailable()) {
            Toast.makeText(context, "Shizuku не активен", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Используем goAsync() для корректной асинхронной работы в Receiver
        val pendingResult = goAsync()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(context)
                val targetDpi = if (isOuter) prefs.getOuterDpi() else prefs.getInnerDpi()
                
                val result = ShizukuManager.setDensity(targetDpi)
                
                withContext(Dispatchers.Main) {
                    val message = if (result.isSuccess) "$screenName экран: $targetDpi dpi" 
                                  else "Ошибка: ${result.exceptionOrNull()?.message}"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    pendingResult.finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    pendingResult.finish()
                }
            }
        }
    }
}
