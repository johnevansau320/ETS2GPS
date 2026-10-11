package com.ets2gps

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RouteTest {
    @Test
    fun routesAndGuidesOnRealMap() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        var t = System.currentTimeMillis()
        val m = MapData.load(File(path!!))
        println("load ${System.currentTimeMillis() - t} ms, ${m.edgeCount} edges")
        t = System.currentTimeMillis()
        val r = Router(m)
        println("router+scc ${System.currentTimeMillis() - t} ms")
        val a = m.findCity("paris")!!; val b = m.findCity("berlin")!!
        val na = r.nearestMainNode(a.x, a.z); val nb = r.nearestMainNode(b.x, b.z)
        // start from an edge leaving na
        val de = r.outAt(na, 0)
        t = System.currentTimeMillis()
        val res = r.route(de, m.eLen[de shr 1], nb)
        println("route ${System.currentTimeMillis() - t} ms")
        assertNotNull(res)
        println("edges ${res!!.edges.size} len ${res.lengthM}")
        val nav = Navigator(m, r)
        nav.setRoute(res, m.eLen[de shr 1])
        for (mv in nav.maneuvers) println("%7.0f m %s exit=%d ang=%.0f".format(mv.atM, mv.turn, mv.exitNo, mv.angleDeg))
        // drive along the route and collect speech
        var spoken = 0
        for (k in res.edges.indices) {
            val e = res.edges[k]
            for (i in 0 until m.deCount(e)) {
                val st = nav.update(m.deX(e, i), m.deZ(e, i), 25f)
                if (st.speech != null) { spoken++; if (spoken <= 8) println("SAY: ${st.speech}") }
                if (st.offRoute) println("OFF ROUTE at edge $k")
            }
        }
        println("spoken prompts: $spoken")
        val s = r.snap(m.deX(de, 0), m.deZ(de, 0), null, 60f)
        println("snap: ${s?.de} dist=${s?.dist}")
    }

    @Test
    fun aberdeenToCalaisJob() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        val r = Router(m)
        println("POIS: " + m.pois.groupBy { it.type }.mapValues { it.value.size })
        org.junit.Assert.assertTrue(m.pois.isNotEmpty())
        val co = m.findCompany("calais", "nbfc")
        assertNotNull(co)
        val snap = r.snap(-39177.3f, -54841.4f, null, 60f)
        assertNotNull(snap)
        val node = r.nearestMainNode(co!!.x, co.z)
        val res = r.route(snap!!.de, (m.eLen[snap.de shr 1] - snap.along).coerceAtLeast(1f), node)
        assertNotNull(res)
        println("TOWARDS: " + Navigator(m, r).also { it.setRoute(res!!, 10f) }.maneuvers.mapNotNull { it.towards }.distinct().take(12))
        println("ABERDEEN->CALAIS nbfc: ${res!!.edges.size} edges, ${res.lengthM / 1000} km map, ${res.lengthM * 19 / 1000} km game")
    }

    @Test
    fun routeModesDiffer() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        val r = Router(m)
        for ((a, b) in listOf("paris" to "berlin", "calais" to "madrid", "london" to "edinburgh")) {
            val ca = m.findCity(a)!!; val cb = m.findCity(b)!!
            val na = r.nearestMainNode(ca.x, ca.z); val nb = r.nearestMainNode(cb.x, cb.z)
            val de = r.outAt(na, 0)
            for (mode in RouteMode.values()) {
                r.mode = mode
                val t0 = System.currentTimeMillis()
                val res = r.route(de, m.eLen[de shr 1], nb)
                if (res == null) { println("MODE $a->$b ${mode.label}: no route"); continue }
                val len = DoubleArray(4); var junc = 0; var total = 0.0; var secs = 0.0
                for (d in res.edges) {
                    val e = d shr 1
                    total += m.eLen[e]; secs += r.edgeSeconds(d)
                    if (m.eJid[e] != 0) junc++ else if (!m.isFerry(e)) len[m.eGrade[e].toInt()] += m.eLen[e].toDouble()
                }
                println("MODE %s->%s %-16s %6.1f km map  %5.0f min  motorway %2.0f%%  expressway %2.0f%%  wide %2.0f%%  minor %2.0f%%  junctions %d  (%d ms)".format(
                    a, b, mode.label, total / 1000, secs / 60, 100 * len[3] / total, 100 * len[2] / total, 100 * len[1] / total, 100 * len[0] / total, junc, System.currentTimeMillis() - t0))
            }
        }
    }

    @Test
    fun avoidsInvisibleHiddenRoadAtTruckPosition() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        val r = Router(m)
        val snap = r.snap(150176.6f, 22529.2f, null, 60f)
        assertNotNull(snap)
        val c = m.findCity("athens")!!
        val node = r.nearestMainNode(c.x, c.z)
        val res = r.route(snap!!.de, (m.eLen[snap.de shr 1] - snap.along).coerceAtLeast(1f), node)
        assertNotNull(res)
        var hiddenM = 0f; var hiddenEdges = 0
        for (d in res!!.edges) if (m.isHidden(d shr 1)) { hiddenM += m.eLen[d shr 1]; hiddenEdges++ }
        val first = res.edges[0] shr 1
        println("BLOCKED-ROAD CHECK: first edge hidden=${m.isHidden(first)} len=${m.eLen[first]}; route ${res.lengthM / 1000} km, hidden edges used=$hiddenEdges (${hiddenM} m)")
        org.junit.Assert.assertTrue("route must not start on the hidden road", !m.isHidden(first))
    }

    @Test
    fun destinationDoesNotEndOnHiddenRoad() {
        val path = System.getenv("ETS2_MAP")
        assumeTrue(path != null && File(path).exists())
        val m = MapData.load(File(path!!))
        val r = Router(m)
        // Balkan Loco, Maribor: the nearest road to the yard is an invisible one the game GPS never ends on
        val node = r.nearestMainNode(18617.7f, 23030.4f)
        var visible = false
        for (i in 0 until r.outCount(node)) if (!m.isHidden(r.outAt(node, i) shr 1)) visible = true
        org.junit.Assert.assertTrue("destination node must touch a visible road", visible)
    }
}
