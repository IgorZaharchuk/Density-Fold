package com.densityfold.engine

import android.content.Context
import android.hardware.display.DisplayManager

data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val density: Int,
    val isDefault: Boolean,
    val isExternal: Boolean = false
)

object DisplayUtils {
    fun getAllDisplays(context: Context): List<DisplayInfo> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.map { display ->
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            val isExternal = isExternalScreen(metrics.widthPixels, metrics.heightPixels)
            DisplayInfo(
                displayId = display.displayId,
                name = if (isExternal) "Внешний экран" else "Внутренний экран",
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                density = metrics.densityDpi,
                isDefault = display.displayId == android.view.Display.DEFAULT_DISPLAY,
                isExternal = isExternal
            )
        }
    }

    fun getActiveDisplay(context: Context): DisplayInfo? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val currentDisplay = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        currentDisplay.getRealMetrics(metrics)
        val isExternal = isExternalScreen(metrics.widthPixels, metrics.heightPixels)
        
        return DisplayInfo(
            displayId = currentDisplay.displayId,
            name = if (isExternal) "Внешний экран" else "Внутренний экран",
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.densityDpi,
            isDefault = true,
            isExternal = isExternal
        )
    }

    private fun isExternalScreen(width: Int, height: Int): Boolean {
        val minSide = minOf(width, height)
        return minSide < 1200
    }

    fun getDiagnosticString(context: Context): String {
        val displays = getAllDisplays(context)
        val active = getActiveDisplay(context)
        
        val sb = StringBuilder("=== DIAGNOSTICS ===\n")
        sb.append("Active Display ID: ${active?.displayId}\n")
        sb.append("Active is External: ${active?.isExternal}\n")
        sb.append("Active Resolution: ${active?.width}x${active?.height}\n")
        sb.append("Active min side: ${if (active != null) minOf(active.width, active.height) else "?"}\n\n")
        
        displays.forEach {
            sb.append("ID: ${it.displayId} | Name: ${it.name}\n")
            sb.append("Res: ${it.width}x${it.height} | DPI: ${it.density} | External: ${it.isExternal}\n")
            sb.append("  min side: ${minOf(it.width, it.height)}\n\n")
        }
        
        sb.append("=== WM DENSITY RAW OUTPUT ===\n")
        sb.append(ShizukuManager.lastRawOutput.ifEmpty { "(ещё не запрашивался)" })
        
        return sb.toString()
    }
}
