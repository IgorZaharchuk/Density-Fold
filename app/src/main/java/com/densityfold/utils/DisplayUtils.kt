package com.densityfold.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.densityfold.data.DisplayInfo

object DisplayUtils {
    fun identifyFoldDisplays(context: Context): Pair<DisplayInfo?, DisplayInfo?> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays.map { display ->
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            DisplayInfo(display.displayId, display.name ?: "Unknown", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.flags)
        }

        var internal: DisplayInfo? = null
        var external: DisplayInfo? = null

        for (display in displays) {
            when {
                display.isInternal -> internal = display
                display.name.contains("external", ignoreCase = true) || display.name.contains("cover", ignoreCase = true) -> external = display
                else -> {
                    if (display.width < 1000 && external == null) external = display
                    else if (internal == null) internal = display
                }
            }
        }
        return Pair(internal, external)
    }
}
