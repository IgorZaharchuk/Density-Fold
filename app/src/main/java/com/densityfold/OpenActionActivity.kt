package com.densityfold

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class OpenActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Мгновенно отправляем broadcast нашему Receiver
        sendBroadcast(Intent("com.densityfold.ACTION_OPEN").setPackage(packageName))
        // И сразу закрываем Activity. 
        // Обычный finish() в onCreate с темой NoDisplay предотвращает переход на рабочий стол.
        finish()
    }
}
