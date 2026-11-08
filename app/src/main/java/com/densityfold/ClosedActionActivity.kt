package com.densityfold

import android.app.Activity
import android.os.Bundle

class ClosedActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DensityActionService.startClosed(this)
        
        moveTaskToBack(true)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
