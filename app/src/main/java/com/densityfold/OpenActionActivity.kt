package com.densityfold

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class OpenActionActivity : Activity() {
    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DensityActionService.ACTION_COMPLETED) {
                // Сервис завершил работу — уходим в фон
                moveTaskToBack(true)
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Регистрируем receiver для получения уведомления о завершении
        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        registerReceiver(completionReceiver, filter)
        
        // Запускаем сервис
        DensityActionService.startOpen(this)
        
        // Таймаут на случай если сервис не ответит (3 секунды)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                moveTaskToBack(true)
                finish()
                overridePendingTransition(0, 0)
            }
        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(completionReceiver)
        } catch (e: Exception) {
            // Receiver уже unregister'ed
        }
    }
}
