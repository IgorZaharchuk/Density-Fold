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

    /**
     * Определение экрана как в оригинальном ScreenSnap:
     * shortPx = min(width, height)
     * inner = shortPx >= 1400
     */
    fun getActiveDisplay(context: Context): DisplayInfo? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null

        // Берём display 0 (DEFAULT_DISPLAY) — как в оригинале, это текущий активный экран
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null

        val size = Point()
        display.getRealSize(size)

        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        val shortPx = minOf(size.x, size.y)
        val isInner = shortPx >= 1400          // оригинальный надёжный порог

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
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()

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
        val displays = getAllDisplays(context)
        val active = getActiveDisplay(context)

        val sb = StringBuilder("=== DIAGNOSTICS (ScreenSnap logic) ===\n")
        sb.append("Active Display ID: ${active?.displayId}\n")
        sb.append("Active is External: ${active?.isExternal}\n")
        sb.append("Active Resolution: ${active?.width}x${active?.height}\n")
        sb.append("Active shortPx: ${if (active != null) minOf(active.width, active.height) else "?"}\n")
        sb.append("Threshold: shortPx >= 1400 → Внутренний\n\n")

        displays.forEach {
            val shortPx = minOf(it.width, it.height)
            sb.append("ID: ${it.displayId} | ${it.name}\n")
            sb.append("Res: ${it.width}x${it.height} | DPI: ${it.density}\n")
            sb.append("shortPx: $shortPx | External: ${it.isExternal}\n\n")
        }

        sb.append("=== WM DENSITY RAW OUTPUT ===\n")
        sb.append(ShizukuManager.lastRawOutput.ifEmpty { "(ещё не запрашивался)" })

        return sb.toString()
    }
}
