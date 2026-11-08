package com.densityfold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isOuter = action == "com.densityfold.ACTION_CLOSED"
        
        if (!ShizukuManager.isShizukuAvailable()) return
        
        val pendingResult = goAsync()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(context)
                val targetDpi = if (isOuter) prefs.getOuterDpi() else prefs.getInnerDpi()
                ShizukuManager.setDensity(targetDpi)
            } catch (e: Exception) {
                // Тихо игнорируем ошибки в фоне
            } finally {
                pendingResult.finish()
            }
        }
    }
}
