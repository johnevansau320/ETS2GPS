package com.ets2gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PlanTest {
    private fun place(m: MapData, name: String): PlanPoint {
        val c = m.cities.firstOrNull { it.key == City.normalize(name) } ?: error("no city $name")
        return PlanPoint(c.x, c.z, c.name)
    }

    @Test
    fun plansATripWithoutTheTruck() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path!!).exists())
        val m = MapData.load(File(path))
        val r = Router(m)
        val out = TripPlan.compute(r, m, place(m, "paris"), listOf(place(m, "frankfurt am main")), place(m, "berlin"))
        println("plan: ${out.lengthM} m, ${out.timeS} s, ${out.starts.size} edges, error=${out.error}")
        assertNull(out.error)
        assertTrue(out.pts.size > 1000 && out.starts.isNotEmpty())
        // the same trip is 1197.4 game km (63022 map m) in the Windows and web apps
        assertEquals(63022f, out.lengthM, 200f)
        val direct = TripPlan.compute(r, m, place(m, "paris"), emptyList(), place(m, "berlin"))
        assertNull(direct.error)
        assertTrue(direct.lengthM <= out.lengthM + 1f)
    }

    @Test
    fun explainsWhatIsMissing() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path!!).exists())
        val m = MapData.load(File(path))
        val r = Router(m)
        assertNotNull(TripPlan.compute(r, m, null, emptyList(), place(m, "berlin")).error)
        assertNotNull(TripPlan.compute(r, m, place(m, "paris"), emptyList(), null).error)
        assertNotNull(TripPlan.compute(r, m, place(m, "paris"), emptyList(), place(m, "paris")).error)
    }
}
