package com.densityfold.engine

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

data class DisplayInfo(
    val width: Int,
    val height: Int,
    val density: Int,
    val isExternal: Boolean
) {
    val shortPx: Int get() = minOf(width, height)
}

object DisplayUtils {
    const val INNER_THRESHOLD_PX = 1400
    const val FALLBACK_INNER_SHORT_PX = 1968
    const val FALLBACK_OUTER_SHORT_PX = 1080

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
            width = size.x,
            height = size.y,
            density = metrics.densityDpi,
            isExternal = !isInner
        )
    }

    fun getInnerShortPx(active: DisplayInfo?): Int =
        if (active != null && !active.isExternal) active.shortPx else FALLBACK_INNER_SHORT_PX

    fun getOuterShortPx(active: DisplayInfo?): Int =
        if (active != null && active.isExternal) active.shortPx else FALLBACK_OUTER_SHORT_PX

    fun dpToDpi(dp: Int, shortPx: Int): Int? {
        if (dp <= 0 || shortPx <= 0) return null
        return (shortPx * 160f / dp).toInt().coerceIn(72, 2000)
    }

    fun dpiToDp(dpi: Int, shortPx: Int): Int {
        if (dpi <= 0 || shortPx <= 0) return 0
        return (shortPx * 160f / dpi).toInt()
    }


}
