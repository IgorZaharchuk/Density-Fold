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
        val sb = StringBuilder()
        sb.appendLine("=== Текущий экран ===")

        val active = getActiveDisplay(context)
        if (active == null) {
            sb.appendLine("Активный дисплей: не определён")
        } else {
            sb.appendLine("Тип: ${active.name}")
            sb.appendLine("Разрешение: ${active.width} × ${active.height} px")
            sb.appendLine("Физический shortPx: ${active.shortPx} (порог внутреннего: $INNER_THRESHOLD_PX)")
            sb.appendLine("Системный DPI: ${active.density}")
        }

        sb.appendLine()
        sb.appendLine("=== Плотность через Shizuku ===")

        val rawOutput = ShizukuManager.lastRawOutput
        if (rawOutput.isBlank()) {
            sb.appendLine("Данные ещё не запрашивались")
        } else if (rawOutput.startsWith("ОШИБКА:")) {
            sb.appendLine(rawOutput)
            sb.appendLine("Попробуйте открыть приложение и дождаться подключения Shizuku")
        } else {
            val currentDpi = ShizukuManager.parseDensity(rawOutput)
            sb.appendLine("Текущий DPI: $currentDpi")

            if (active != null && currentDpi > 0) {
                val currentDp = dpiToDp(currentDpi, active.shortPx)
                sb.appendLine("Логическая ширина: $currentDp dp")
            }

            sb.appendLine()
            sb.appendLine("=== WM DENSITY RAW OUTPUT ===")
            sb.appendLine(rawOutput)
        }

        return sb.toString()
    }
}
