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
    
    // Порог определения внутреннего экрана
    const val INNER_THRESHOLD_PX = 1400
    
    // Физические разрешения Fold 7 (для миграции, когда экран недоступен)
    const val PHYSICAL_INNER_SHORT_PX = 1968
    const val PHYSICAL_OUTER_SHORT_PX = 1080
    
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
    
    /**
     * Получить shortPx для внутреннего экрана (по текущему активному или физическому).
     */
    fun getInnerShortPx(context: Context): Int {
        val active = getActiveDisplay(context)
        if (active != null && !active.isExternal) return active.shortPx
        // Fallback на физическое разрешение
        return PHYSICAL_INNER_SHORT_PX
    }
    
    /**
     * Получить shortPx для внешнего экрана (по текущему активному или физическому).
     */
    fun getOuterShortPx(context: Context): Int {
        val active = getActiveDisplay(context)
        if (active != null && active.isExternal) return active.shortPx
        // Fallback на физическое разрешение
        return PHYSICAL_OUTER_SHORT_PX
    }
    
    /**
     * Конвертация dp → dpi для команды wm density.
     * Формула: dpi = shortPx * 160 / dp
     * Возвращает null если dp <= 0 или shortPx <= 0 (защита от деления на ноль).
     */
    fun dpToDpi(dp: Int, shortPx: Int): Int? {
        if (dp <= 0 || shortPx <= 0) return null
        return (shortPx * 160f / dp).toInt().coerceIn(72, 2000)
    }
    
    /**
     * Конвертация dpi → dp для отображения.
     * Формула: dp = shortPx * 160 / dpi
     */
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
        sb.append("shortPx: $shortPx (порог внутреннего: $INNER_THRESHOLD_PX)\n")
        
        // Безопасное чтение текущего DPI из сырого вывода
        val rawOutput = ShizukuManager.lastRawOutput
        val currentDpi = extractPhysicalDensity(rawOutput)
        sb.append("Текущий DPI (физический): $currentDpi\n")
        
        if (currentDpi > 0 && shortPx > 0) {
            val currentDp = dpiToDp(currentDpi, shortPx)
            sb.append("Логическая ширина: $currentDp dp\n")
        } else {
            sb.append("Логическая ширина: — (невозможно рассчитать)\n")
        }
        
        sb.append("\n=== WM DENSITY RAW OUTPUT ===\n")
        sb.append(rawOutput.ifEmpty { "(не запрашивался)" })
        return sb.toString()
    }
    
    /**
     * Безопасное извлечение Physical density из сырого вывода wm density.
     */
    private fun extractPhysicalDensity(rawOutput: String): Int {
        if (rawOutput.isBlank()) return 0
        val match = Regex("Physical density:\\s*(\\d+)").find(rawOutput)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
