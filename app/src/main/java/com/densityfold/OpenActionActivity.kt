package com.densityfold

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class OpenActionActivity : Activity() {
    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DensityActionService.ACTION_COMPLETED) {
                cleanupAndFinish()
            }
        }
    }
    
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { cleanupAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Безопасная регистрация для Android 14+ (API 33+)
        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(completionReceiver, filter)
        }
        
        // Запускаем сервис
        DensityActionService.startOpen(this)
        
        // Увеличенный таймаут: 5 секунд (достаточно для Shizuku + wm density)
        timeoutHandler.postDelayed(timeoutRunnable, 5000)
    }

    private fun cleanupAndFinish() {
        // Отменяем таймаут, если он ещё активен
        timeoutHandler.removeCallbacks(timeoutRunnable)
        
        // Безопасное отключение receiver
        try { unregisterReceiver(completionReceiver) } catch (_: Exception) {}
        
        // Возвращаем предыдущее приложение на передний план
        moveTaskToBack(true)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndFinish() // Гарантированная очистка при уничтожении
    }
}
