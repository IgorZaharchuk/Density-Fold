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
import android.util.Log

class ClosedActionActivity : Activity() {
    private val TAG = "DensityFold-Closed"

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DensityActionService.ACTION_COMPLETED) {
                Log.d(TAG, "Broadcast received: ACTION_COMPLETED")
                cleanupAndFinish()
            }
        }
    }
    
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { 
        Log.d(TAG, "Timeout triggered (5s)")
        cleanupAndFinish() 
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: taskId=$taskId, isTaskRoot=$isTaskRoot, taskAffinity=$taskAffinity")
        
        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(completionReceiver, filter)
        }
        
        Log.d(TAG, "Starting DensityActionService (CLOSED)")
        DensityActionService.startClosed(this)
        
        timeoutHandler.postDelayed(timeoutRunnable, 5000)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: taskId=$taskId, isTaskRoot=$isTaskRoot")
    }

    private fun cleanupAndFinish() {
        Log.d(TAG, "cleanupAndFinish called")
        timeoutHandler.removeCallbacks(timeoutRunnable)
        try { unregisterReceiver(completionReceiver) } catch (_: Exception) {}
        
        Log.d(TAG, "Calling moveTaskToBack(true) and finish()")
        moveTaskToBack(true)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: taskId=$taskId")
        cleanupAndFinish()
    }
}
