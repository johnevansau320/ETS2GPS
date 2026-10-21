package com.ets2gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MapExtrasTest {
    @Test
    fun readsNamesRoadNumbersAreasAndTerminals() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        assumeTrue(m.areas.isNotEmpty())   // a version 8 map has none
        println("names ${m.companyNames.size}, road numbers ${m.roadLabels.size}, areas ${m.areas.size}")
        assertEquals("DHL", m.companyNames["stokes"])
        assertTrue(m.roadLabels.any { it.text == "E45" })
        assertTrue(m.roadLabels.any { it.text.startsWith("SH ") })
        assertTrue(m.areas.all { it.xs.size >= 3 && it.color in 0..3 })
        assertTrue(m.pois.any { it.type == Poi.FERRY })
        println("borders ${m.borders.size}, city countries ${m.cityCountry.size}")
        assertTrue(m.borders.any { it.kind == 0 } && m.borders.any { it.kind == 1 && it.cc == "de" })
        val berlin = m.findCity("berlin")!!
        assertEquals("de", m.countryAt(berlin.x, berlin.z))
        assertTrue(m.pois.any { it.type == Poi.GARAGE } && m.pois.any { it.type == Poi.DEALER } && m.pois.any { it.type == Poi.SERVICE })
    }

    @Test
    fun readsFlyoverLift() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        assumeTrue(m.eHasLift.isNotEmpty())   // maps before file version 10 have none
        val lifted = m.eHasLift.count { it }
        println("lifted road pieces $lifted of ${m.edgeCount}")
        assertTrue(lifted > 1000 && lifted < m.edgeCount / 4)
        assertTrue(m.ptLift.size == m.ptX.size)
        val e = m.eHasLift.indexOfFirst { it }
        // a lifted piece has some point above ground, and a reversed edge reads the same lifts backwards
        assertTrue((0 until m.deCount(e shl 1)).any { m.deLift(e shl 1, it) > 0f })
        val n = m.deCount(e shl 1)
        assertEquals(m.deLift(e shl 1, 0), m.deLift((e shl 1) or 1, n - 1), 0f)
    }

    @Test
    fun readsTheAssetPack() {
        val path = System.getenv("ETS2_ASSETS")
        assumeTrue(path != null && File(path).exists())
        val a = MapAssets.load(File(path!!))!!
        println("logos ${a.logoCount}, flags ${a.flagCount}")
        assertTrue(a.logoCount > 100 && a.flagCount > 40)
    }
}
