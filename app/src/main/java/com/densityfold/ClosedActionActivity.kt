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

class ClosedActionActivity : Activity() {
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
        
        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(completionReceiver, filter)
        }
        
        DensityActionService.startClosed(this)
        timeoutHandler.postDelayed(timeoutRunnable, 5000)
    }

    private fun cleanupAndFinish() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        try { unregisterReceiver(completionReceiver) } catch (_: Exception) {}
        moveTaskToBack(true)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndFinish()
    }
}
