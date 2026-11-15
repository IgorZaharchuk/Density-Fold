package com.densityfold.backend.shizuku

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.densityfold.ClosedActionActivity
import com.densityfold.DensityActionService
import com.densityfold.OpenActionActivity

/**
 * Одноразовый триггер восстановления главного Shizuku Binder.
 * Вызывается монитором только при реальном изменении положения экрана.
 * Если запуск пропущен или не удался, повтор возможен только
 * при следующем реальном переходе или после восстановления READY.
 */
object ShizukuRecovery {

    private const val TAG = "DensityFold-ShizukuRecovery"

    fun launch(context: Context, isExternal: Boolean) {
        val app = context.applicationContext

        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive != true) {
            Log.i(TAG, "Recovery пропущен: экран выключен")
            return
        }

        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked != false) {
            Log.i(TAG, "Recovery пропущен: экран заблокирован")
            return
        }

        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "Recovery пропущен: нет SYSTEM_ALERT_WINDOW")
            return
        }

        val activityClass = if (isExternal) {
            ClosedActionActivity::class.java
        } else {
            OpenActionActivity::class.java
        }

        try {
            val intent = Intent(app, activityClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(DensityActionService.EXTRA_RECOVERY_REQUEST, true)
            }
            Log.w(TAG, "Одноразовый recovery trigger: " +
                if (isExternal) "Внешний" else "Внутренний")
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить recovery Activity", e)
        }
    }
}
