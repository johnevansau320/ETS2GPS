package com.ets2gps

import kotlin.math.min

/** A place chosen for a planned trip. */
class PlanPoint(val x: Float, val z: Float, val name: String)

/** The preview of a planned trip: the route as x,z pairs, where each edge starts in it, and its length (map metres) and time. */
class PlanOut(val pts: FloatArray, val starts: IntArray, val lengthM: Float, val timeS: Float, val error: String?, val lift: ByteArray = ByteArray(0))

object TripPlan {
    private fun fail(text: String) = PlanOut(FloatArray(0), IntArray(0), 0f, 0f, text)

    /** Routes start -> stops -> destination. The start is a place, not the truck, so no game is needed. */
    fun compute(r: Router, m: MapData, start: PlanPoint?, stops: List<PlanPoint>, dest: PlanPoint?): PlanOut {
        if (start == null) return fail("Choose a start first (or start the game)")
        if (dest == null) return fail("Choose a destination first")
        val pts = ArrayList<PlanPoint>()
        pts.add(start); pts.addAll(stops); pts.add(dest)
        val edges = ArrayList<Int>()
        var len = 0f; var time = 0f; var lastDe = -1
        for (i in 0 until pts.size - 1) {
            val b = r.nearestMainNode(pts[i + 1].x, pts[i + 1].z)
            if (b < 0) return fail("${pts[i + 1].name} is not reachable by road")
            val a = if (lastDe < 0) r.nearestMainNode(pts[i].x, pts[i].z) else m.deTo(lastDe)
            if (a < 0) return fail("${pts[i].name} is not reachable by road")
            if (a == b) continue
            var best: RouteResult? = null
            val n = r.outCount(a)
            for (k in 0 until min(n, 4)) {
                val de = r.outAt(a, k)
                if (lastDe >= 0 && n > 1 && (de shr 1) == (lastDe shr 1)) continue // no U-turn at a stop
                if (r.avoidFerries && m.isFerry(de shr 1)) continue
                val res = r.route(de, m.eLen[de shr 1], b)
                if (res != null && (best == null || res.timeS < best.timeS)) best = res
            }
            if (best == null) return fail(if (r.avoidFerries) "No route without ferries - allow ferries in Settings" else "No route to ${pts[i + 1].name}")
            for (de in best.edges) edges.add(de)
            len += best.lengthM; time += best.timeS; lastDe = best.edges.last()
        }
        if (edges.isEmpty()) return fail("The start and the destination are the same place")
        var total = 0
        for (de in edges) total += m.deCount(de)
        val line = FloatArray(total * 2); val lift = ByteArray(total); val starts = IntArray(edges.size)
        var p = 0
        for ((i, de) in edges.withIndex()) {
            starts[i] = p
            for (k in 0 until m.deCount(de)) { line[p * 2] = m.deX(de, k); line[p * 2 + 1] = m.deZ(de, k); lift[p] = (m.deLift(de, k) * 2).toInt().toByte(); p++ }
        }
        return PlanOut(line, starts, len, time, null, lift)
    }
}
