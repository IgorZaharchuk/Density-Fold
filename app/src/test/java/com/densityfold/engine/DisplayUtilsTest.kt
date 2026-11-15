package com.densityfold.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun dpToDpiConvertsAndClamps() {
        assertEquals(432, DisplayUtils.dpToDpi(400, 1080))
        assertEquals(200, DisplayUtils.dpToDpi(1200, 1080))
        assertEquals(1000, DisplayUtils.dpToDpi(100, 1080))
    }

    @Test
    fun dpToDpiRejectsInvalidInput() {
        assertNull(DisplayUtils.dpToDpi(0, 1080))
        assertNull(DisplayUtils.dpToDpi(400, 0))
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
