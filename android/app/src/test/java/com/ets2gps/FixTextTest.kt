package com.ets2gps

import org.junit.Assert.assertEquals
import org.junit.Test

class FixTextTest {
    @Test
    fun repairsDoubleEncodedCyrillic() {
        val bad = "Ð¦ÐµÐ¼ÐµÐ»Ñ‚ÐµÐºÑ\udc81"
        assertEquals("Цемелтекс", fixText(bad))
    }

    @Test
    fun leavesNormalTextAlone() {
        assertEquals("Zürich", fixText("Zürich"))
        assertEquals("Bouygues Construction", fixText("Bouygues Construction"))
    }
}
