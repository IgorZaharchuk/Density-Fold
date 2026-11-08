package com.densityfold

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ClosedActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Мгновенно отправляем broadcast нашему Receiver
        sendBroadcast(Intent("com.densityfold.ACTION_CLOSED").setPackage(packageName))
        // И сразу закрываем Activity
        finish()
    }
}
