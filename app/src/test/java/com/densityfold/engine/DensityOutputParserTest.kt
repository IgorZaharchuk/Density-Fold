package com.densityfold.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DensityOutputParserTest {

    @Test
    fun overrideDensityHasPriorityOverPhysicalDensity() {
        val output = """
            Physical density: 420
            Override density: 440
        """.trimIndent()

        assertEquals(440, DensityOutputParser.parse(output))
    }

    @Test
    fun physicalDensityIsUsedWhenOverrideIsAbsent() {
        assertEquals(
            420,
            DensityOutputParser.parse("Physical density: 420")
        )
    }

    @Test
    fun blankOutputReturnsZero() {
        assertEquals(0, DensityOutputParser.parse(""))
        assertEquals(0, DensityOutputParser.parse("   "))
    }

    @Test
    fun missingOrInvalidDensityReturnsZero() {
        assertEquals(0, DensityOutputParser.parse("Unknown output"))
        assertEquals(0, DensityOutputParser.parse("Override density: 0"))
        assertEquals(0, DensityOutputParser.parse("Physical density: abc"))
    }
}
