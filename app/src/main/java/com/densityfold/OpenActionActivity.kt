package com.densityfold

import android.app.Activity
import android.os.Bundle

class OpenActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DensityActionService.startOpen(this)
        
        moveTaskToBack(true)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
