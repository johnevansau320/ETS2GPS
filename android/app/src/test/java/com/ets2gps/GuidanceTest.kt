package com.ets2gps

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

class GuidanceTest {
    /** Routes between random towns on the real map: roundabouts and exit ramps must be found, and building the instructions must never fail. */
    @Test fun roundaboutsAndExitsOnRealRoutes() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        val r = Router(m)
        val rng = Random(7)
        var routes = 0; var roundabouts = 0; var exits = 0; var maneuvers = 0
        var tries = 0
        while (routes < 100 && tries < 6000) {
            tries++
            val a = m.cities[rng.nextInt(m.cities.size)]; val b = m.cities[rng.nextInt(m.cities.size)]
            val d = Math.hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())
            if (d < 5000 || d > 25000) continue
            val snap = r.snap(a.x, a.z, null, 400f) ?: continue
            val node = r.nearestMainNode(b.x, b.z)
            if (node < 0) continue
            val rem = (m.eLen[snap.de shr 1] - snap.along).coerceAtLeast(1f)
            val res = r.route(snap.de, rem, node) ?: continue
            val nav = Navigator(m, r)
            nav.setRoute(res, rem)
            routes++
            for (mv in nav.maneuvers) {
                maneuvers++
                if (mv.turn == Turn.ROUNDABOUT) { roundabouts++; assertTrue(mv.exitNo >= 1) }
                if (mv.exit) exits++
                nav.instruction(mv)
            }
        }
        println("routes $routes maneuvers $maneuvers roundabouts $roundabouts exits $exits")
        assertTrue(routes >= 50)
        assertTrue(roundabouts > 0)
    }
}
