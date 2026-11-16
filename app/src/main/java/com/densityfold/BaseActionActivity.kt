package com.densityfold

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Минимально возможная Activity для запуска DensityActionService.
 * 
 * Архитектура: Samsung Modes запускает эту Activity → она запускает сервис → 
 * сразу завершается. Сервис работает независимо и сам себя останавливает.
 * 
 * Это минимизирует потерю фокуса предыдущего приложения.
 */
abstract class BaseActionActivity : Activity() {

    protected abstract val action: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // Запускаем сервис — он работает независимо от Activity
        val fromMonitor = intent.getBooleanExtra(
            DensityActionService.EXTRA_MONITOR_REQUEST,
            false
        )

        startService(Intent(this, DensityActionService::class.java).apply {
            this.action = this@BaseActionActivity.action
            putExtra(DensityActionService.EXTRA_MONITOR_REQUEST, fromMonitor)
            if (fromMonitor) {
                putExtra(
                    DensityActionService.EXTRA_MONITOR_REQUEST_ID,
                    intent.getLongExtra(
                        DensityActionService.EXTRA_MONITOR_REQUEST_ID,
                        0L
                    )
                )
            }
        })

        // Немедленно завершаем Activity, чтобы минимизировать потерю фокуса
        finishAndRemoveTask()
    }
}
