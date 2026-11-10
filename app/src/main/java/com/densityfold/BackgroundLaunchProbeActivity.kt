package com.densityfold

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log

class BackgroundLaunchProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(
            "DensityFold-BAL",
            "BACKGROUND ACTIVITY STARTED pid=${Process.myPid()}"
        )

        finish()
    }
}
