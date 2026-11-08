package com.densityfold

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class OpenActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent("com.densityfold.ACTION_OPEN").setPackage(packageName))
        moveTaskToBack(true)
        finish()
        overridePendingTransition(0, 0)
    }
}
