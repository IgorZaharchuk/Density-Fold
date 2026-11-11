package com.densityfold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.densityfold.data.DensityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private val TAG = "DensityFold-Boot"

    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }


        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = DensityPreferences(appContext)
                val settings = prefs.getSettings()

                if (settings.foldMonitorEnabled && settings.autoStartEnabled) {
                    appContext.startService(
                        Intent(appContext, FoldMonitorService::class.java)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при обработке BOOT_COMPLETED: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
