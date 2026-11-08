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
            DisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "Unknown",
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                density = metrics.densityDpi,
                isDefault = display.displayId == android.view.Display.DEFAULT_DISPLAY,
                isExternal = isExternalScreen(metrics.widthPixels)
            )
        }
    }

    fun getActiveDisplay(context: Context): DisplayInfo? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val currentDisplay = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        currentDisplay.getRealMetrics(metrics)
        
        return DisplayInfo(
            displayId = currentDisplay.displayId,
            name = if (isExternalScreen(metrics.widthPixels)) "Внешний экран" else "Внутренний экран",
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.densityDpi,
            isDefault = true,
            isExternal = isExternalScreen(metrics.widthPixels)
        )
    }

    // Для Fold 7: внешний экран 1080px шириной, внутренний 1968px
    private fun isExternalScreen(width: Int): Boolean {
        return width < 1200
    }

    fun getInnerDisplay(context: Context): DisplayInfo? {
        return getAllDisplays(context).firstOrNull { !it.isExternal }
    }

    fun getOuterDisplay(context: Context): DisplayInfo? {
        return getAllDisplays(context).firstOrNull { it.isExternal }
    }

    fun getDiagnosticString(context: Context): String {
        val displays = getAllDisplays(context)
        val active = getActiveDisplay(context)
        val inner = getInnerDisplay(context)
        val outer = getOuterDisplay(context)
        
        val sb = StringBuilder("=== DIAGNOSTICS ===\n")
        sb.append("Active Display ID: ${active?.displayId}\n")
        sb.append("Active is External: ${active?.isExternal}\n")
        sb.append("Active Resolution: ${active?.width}x${active?.height}\n")
        sb.append("Identified Inner: ID ${inner?.displayId} (${inner?.width}x${inner?.height})\n")
        sb.append("Identified Outer: ID ${outer?.displayId} (${outer?.width}x${outer?.height})\n\n")
        
        displays.forEach {
            sb.append("ID: ${it.displayId} | Name: ${it.name}\n")
            sb.append("Res: ${it.width}x${it.height} | DPI: ${it.density} | External: ${it.isExternal}\n\n")
        }
        return sb.toString()
    }
}
