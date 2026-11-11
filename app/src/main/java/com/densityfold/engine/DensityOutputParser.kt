package com.densityfold.engine

object DensityOutputParser {
    private val overrideRegex = Regex("Override density:\\s*(\\d+)")
    private val physicalRegex = Regex("Physical density:\\s*(\\d+)")

    fun parse(rawOutput: String): Int {
        if (rawOutput.isBlank()) return 0

        val override = overrideRegex.find(rawOutput)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (override != null && override > 0) return override

        val physical = physicalRegex.find(rawOutput)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (physical != null && physical > 0) return physical

        return 0
    }
}
