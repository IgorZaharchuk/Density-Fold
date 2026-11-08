package com.densityfold

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class OpenActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Запускаем фоновую работу
        DensityActionService.startOpen(this)

        // 2. Отправляем задачу в фон
        moveTaskToBack(true)
        
        // 3. Убираем анимацию
        overridePendingTransition(0, 0)
        
        // 4. Начинаем уничтожение
        finishAndRemoveTask()

        // 5. Дополнительная страховка для One UI: повторяем уничтожение через 50 мс
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finishAndRemoveTask()
            }
        }, 50)
    }
}
