package com.densityfold.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayUtilsTest {

    @Test
    fun outerDisplayHasExpectedDpiRange() {
        assertEquals(200..864, DisplayUtils.allowedDpiRange(1080))
    }

    @Test
    fun innerDisplayHasExpectedDpiRange() {
        assertEquals(263..1000, DisplayUtils.allowedDpiRange(1968))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroScreenSizeIsRejected() {
        DisplayUtils.allowedDpiRange(0)
    }

    @Test
    fun dpiToDpConvertsCorrectly() {
        assertEquals(400, DisplayUtils.dpiToDp(432, 1080))
        assertEquals(801, DisplayUtils.dpiToDp(393, 1968))
    }

    @Test
    fun dpiToDpReturnsZeroForInvalidInput() {
        assertEquals(0, DisplayUtils.dpiToDp(0, 1080))
        assertEquals(0, DisplayUtils.dpiToDp(440, 0))
    }
}
