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

abstract class BaseActionActivity : Activity() {
    private val TAG = "DensityFold-Action"

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DensityActionService.ACTION_COMPLETED) {
                cleanupAndFinish()
            }
        }
    }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { cleanupAndFinish() }

    protected abstract val action: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(DensityActionService.ACTION_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(completionReceiver, filter)
        }

        // Диагностический лог перед запуском сервиса
        Log.d(TAG, "Запуск сервиса с действием: $action")

        // ВАЖНО: this@BaseActionActivity.action ссылается на свойство Activity,
        // а не на Intent.action (иначе сервис получал бы null action)
        startService(Intent(this, DensityActionService::class.java).apply {
            this.action = this@BaseActionActivity.action
        })

        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun cleanupAndFinish() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        try { unregisterReceiver(completionReceiver) } catch (_: Exception) {}
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        try { unregisterReceiver(completionReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val TIMEOUT_MS = 5000L
    }
}
