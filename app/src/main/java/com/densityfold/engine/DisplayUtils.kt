package com.densityfold.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val density: Int,
    val isDefault: Boolean
)

object DisplayUtils {
    fun getAllDisplays(context: Context): List<DisplayInfo> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.map { display ->
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            DisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "Unknown",
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                density = metrics.densityDpi,
                isDefault = display.displayId == android.view.Display.DEFAULT_DISPLAY
            )
        }
    }

    fun getActiveDisplay(context: Context): DisplayInfo? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val currentDisplay = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        currentDisplay.getRealMetrics(metrics)
        
        return DisplayInfo(
            displayId = currentDisplay.displayId,
            name = currentDisplay.name ?: "Active",
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.densityDpi,
            isDefault = true
        )
    }

    fun getDiagnosticString(context: Context): String {
        val displays = getAllDisplays(context)
        val active = getActiveDisplay(context)
        val sb = StringBuilder("=== DIAGNOSTICS ===\n")
        sb.append("Active Display ID: ${active?.displayId}\n\n")
        displays.forEach {
            sb.append("ID: ${it.displayId} | Name: ${it.name}\n")
            sb.append("Res: ${it.width}x${it.height} | DPI: ${it.density} | Default: ${it.isDefault}\n\n")
        }
        return sb.toString()
    }
}
