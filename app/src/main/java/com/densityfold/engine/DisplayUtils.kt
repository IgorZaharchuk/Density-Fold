package com.densityfold.engine

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

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
    fun getActiveDisplay(context: Context): DisplayInfo? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null

        val size = Point()
        display.getRealSize(size)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        val shortPx = minOf(size.x, size.y)
        val isInner = shortPx >= 1400

        return DisplayInfo(
            displayId = display.displayId,
            name = if (isInner) "Внутренний экран" else "Внешний экран",
            width = size.x,
            height = size.y,
            density = metrics.densityDpi,
            isDefault = true,
            isExternal = !isInner
        )
    }

    fun getAllDisplays(context: Context): List<DisplayInfo> {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        return displayManager.displays.map { display ->
            val size = Point()
            display.getRealSize(size)
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val shortPx = minOf(size.x, size.y)
            val isInner = shortPx >= 1400

            DisplayInfo(
                displayId = display.displayId,
                name = if (isInner) "Внутренний экран" else "Внешний экран",
                width = size.x,
                height = size.y,
                density = metrics.densityDpi,
                isDefault = display.displayId == Display.DEFAULT_DISPLAY,
                isExternal = !isInner
            )
        }
    }

    fun getDiagnosticString(context: Context): String {
        val active = getActiveDisplay(context)
        val sb = StringBuilder("=== Текущий экран ===\n")
        sb.append("Тип: ${active?.name}\n")
        sb.append("Разрешение: ${active?.width} x ${active?.height}\n")
        sb.append("shortPx: ${if (active != null) minOf(active.width, active.height) else "?"} (порог: 1400)\n\n")
        
        sb.append("=== Вывод wm density ===\n")
        sb.append(ShizukuManager.lastRawOutput.ifEmpty { "(не запрашивался)" })
        return sb.toString()
    }
}
