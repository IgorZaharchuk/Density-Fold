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
) {
    val shortPx: Int get() = minOf(width, height)
}

object DisplayUtils {
    const val INNER_THRESHOLD_PX = 1400
    
    fun getActiveDisplay(context: Context): DisplayInfo? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null

        val size = Point()
        display.getRealSize(size)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        val shortPx = minOf(size.x, size.y)
        val isInner = shortPx >= INNER_THRESHOLD_PX

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
            val isInner = shortPx >= INNER_THRESHOLD_PX

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
    
    fun getInnerShortPx(context: Context): Int {
        val active = getActiveDisplay(context)
        return if (active != null && !active.isExternal) active.shortPx else 1968
    }
    
    fun getOuterShortPx(context: Context): Int {
        val active = getActiveDisplay(context)
        return if (active != null && active.isExternal) active.shortPx else 1080
    }
    
    fun dpToDpi(dp: Int, shortPx: Int): Int? {
        if (dp <= 0 || shortPx <= 0) return null
        return (shortPx * 160f / dp).toInt().coerceIn(72, 2000)
    }
    
    fun dpiToDp(dpi: Int, shortPx: Int): Int {
        if (dpi <= 0 || shortPx <= 0) return 0
        return (shortPx * 160f / dpi).toInt()
    }

    fun getDiagnosticString(context: Context): String {
        val active = getActiveDisplay(context)
        val sb = StringBuilder("=== Текущий экран ===\n")
        sb.append("Тип: ${active?.name ?: "не определён"}\n")
        sb.append("Разрешение: ${active?.width ?: 0} x ${active?.height ?: 0}\n")
        
        val shortPx = active?.shortPx ?: 0
        sb.append("Физический shortPx: $shortPx (порог внутреннего: $INNER_THRESHOLD_PX)\n")
        
        val rawOutput = ShizukuManager.lastRawOutput
        val currentDpi = extractPhysicalDensity(rawOutput)
        sb.append("Текущий DPI: $currentDpi\n")
        
        if (currentDpi > 0 && shortPx > 0) {
            val currentDp = dpiToDp(currentDpi, shortPx)
            sb.append("Логическая ширина (расчётная): $currentDp dp\n")
        } else {
            sb.append("Логическая ширина: — (невозможно рассчитать)\n")
        }
        
        sb.append("\n=== WM DENSITY RAW OUTPUT ===\n")
        sb.append(rawOutput.ifEmpty { "(не запрашивался)" })
        return sb.toString()
    }
    
    private fun extractPhysicalDensity(rawOutput: String): Int {
        if (rawOutput.isBlank()) return 0
        val match = Regex("Physical density:\\s*(\\d+)").find(rawOutput)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
