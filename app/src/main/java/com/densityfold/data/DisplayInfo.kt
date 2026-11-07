package com.densityfold.data

import android.view.Display

data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val density: Int,
    val flags: Int
) {
    val isInternal: Boolean
        get() = (flags and Display.FLAG_PRIVATE != 0) || 
                name.contains("internal", ignoreCase = true) ||
                name.contains("fold", ignoreCase = true)
}
