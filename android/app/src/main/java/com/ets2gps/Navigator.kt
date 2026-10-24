package com.ets2gps

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Turn { KEEP_LEFT, KEEP_RIGHT, BEAR_LEFT, BEAR_RIGHT, TURN_LEFT, TURN_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/** One lane of the approach to a junction: the direction it takes (deg, + = right; NaN = unknown), whether the route uses it, and an optional second direction. */
class Lane(val angleDeg: Float, val preferred: Boolean, val altDeg: Float?)

/** Lane guidance at a junction along the route, [atM] metres from the start of the route. */
class LaneHint(val atM: Float, val lanes: List<Lane>)

class Maneuver(
    val turn: Turn,
    val atM: Float, // distance along the route (from the start of its first edge)
    val exitNo: Int, // roundabout exit number, else 0
    val angleDeg: Float, // signed turn angle, + = right
    val x: Float, val z: Float, // position of the junction entry
) {
    var towards: String? = null // next town the route heads for after this maneuver
    var lanes: List<Lane>? = null // lane guidance for the approach, when the map has lane data
    var exit = false // a ramp / exit road leaving a bigger road (spoken as "take the exit on the right")
}

class NavState(
    val distToNextM: Float, // world metres
    val next: Maneuver?,
    val following: Maneuver?,
    val remainingM: Float,
    val remainingS: Float,
    val offRoute: Boolean,
    val onRoute: Boolean, // the truck is on the route right now (offRoute only turns true after a few seconds away from it)
    val arrived: Boolean,
    val speech: String?,
    val lanes: List<Lane>? = null, // lane guidance for the next junction that has lane data (not only turns)
    val lanesDistM: Float = 0f,
)

/**
 * Follows a route: tracks progress, produces maneuvers and the spoken prompts.
 * [distScale] converts world metres to the distances shown/spoken (the game's UI is 19x the map scale).
 */
class Navigator(private val map: MapData, private val router: Router) {
    var distScale = 19f

    private var route: IntArray = IntArray(0)
    private var cum = FloatArray(0)
    private var secs = FloatArray(0) // cumulative seconds at the start of each route edge
    var maneuvers: List<Maneuver> = emptyList()
        private set
    private var laneHints: List<LaneHint> = emptyList()
    private var stage = IntArray(0)
    private var idx = 0
    private var progress = 0f
    private var offCount = 0
    private var started = false

    val active get() = route.isNotEmpty()
    val currentIndex get() = idx

    /** Speed the router assumes for the road the truck is on now (m/s), 0 for junction paths. */
    val modelSpeedMps: Float
        get() {
            if (route.isEmpty()) return 0f
            val e = route[idx.coerceIn(0, route.size - 1)] shr 1
            return if (map.eJid[e] != 0) 0f else map.eSpeed[e] / 3.6f
        }
    val routeEdges: IntArray get() = route
    val routeLengthM get() = if (cum.isNotEmpty()) cum[cum.size - 1] else 0f

    fun clear() {
        route = IntArray(0); cum = FloatArray(0); maneuvers = emptyList(); laneHints = emptyList(); stage = IntArray(0)
        idx = 0; progress = 0f; offCount = 0; started = false
    }

    /** [remainingM] = how much of the first edge was still ahead when the route was computed. */
    // Prompts already given are remembered for a short while, so a recalculated route (the truck was off the road for a moment) does not read the same prompt out again.
    private val memo = HashMap<String, Pair<Int, Long>>()
    private fun memoKey(m: Maneuver) = "${m.turn}:${Math.round(m.x / 15f)}:${Math.round(m.z / 15f)}"

    fun setRoute(res: RouteResult, remainingM: Float, reroute: Boolean = false) {
        route = res.edges
        cum = FloatArray(route.size + 1)
        secs = FloatArray(route.size + 1)
        for (i in route.indices) {
            cum[i + 1] = cum[i] + map.eLen[route[i] shr 1]
            secs[i + 1] = secs[i] + router.edgeSeconds(route[i])
        }
        idx = 0
        progress = map.eLen[route[0] shr 1] - remainingM
        offCount = 0
        started = false
        maneuvers = buildManeuvers()
        assignTowards()
        stage = IntArray(maneuvers.size)
        // do not announce maneuvers that are already behind the start position
        for (i in maneuvers.indices) if (maneuvers[i].atM < progress) stage[i] = 3
        if (!reroute) memo.clear()
        else {
            val now = System.nanoTime() / 1_000_000
            for (i in maneuvers.indices) memo[memoKey(maneuvers[i])]?.let { if (now - it.second < 90_000) stage[i] = max(stage[i], it.first) }
        }
    }

    // ------------------------------------------------------------ maneuver generation --

    private fun tangent(de: Int, atEnd: Boolean, win: Float = 6f): Float {
        val n = map.deCount(de)
        if (n < 2) return 0f
        if (atEnd) {
            val px = map.deX(de, n - 1); val pz = map.deZ(de, n - 1)
            for (i in n - 2 downTo 0) {
                val qx = map.deX(de, i); val qz = map.deZ(de, i)
                if (hypot(px - qx, pz - qz) >= win || i == 0) return Router.bearing(px - qx, pz - qz)
            }
        } else {
            val px = map.deX(de, 0); val pz = map.deZ(de, 0)
            for (i in 1 until n) {
                val qx = map.deX(de, i); val qz = map.deZ(de, i)
                if (hypot(qx - px, qz - pz) >= win || i == n - 1) return Router.bearing(qx - px, qz - pz)
            }
        }
        return 0f
    }

    private fun degDiff(a: Float, b: Float): Float = Math.toDegrees(Router.angleDiff(a, b).toDouble()).toFloat()

    /** Total turning along a directed edge in degrees, + = right, NOT wrapped to +-180 (a 270 degree loop ramp counts as 270). */
    private fun cumTurn(de: Int, win: Float = 3f): Float {
        val n = map.deCount(de)
        if (n < 3) return 0f
        var tot = 0f; var prev = Float.NaN
        var lx = map.deX(de, 0); var lz = map.deZ(de, 0)
        for (i in 1 until n) {
            val x = map.deX(de, i); val z = map.deZ(de, i)
            if (hypot(x - lx, z - lz) < win && i != n - 1) continue
            val h = Router.bearing(x - lx, z - lz)
            if (!prev.isNaN()) tot += degDiff(h, prev)
            prev = h; lx = x; lz = z
        }
        return tot
    }

    // ------------------------------------------------------- roundabouts the map builder did not flag --
    // The builder flags a junction as a roundabout only when enough of its arms are connected to roads. A junction that has the shape of one anyway
    // (every straight-on path is forced to bend around a central island) is treated as a roundabout here too.
    private val geoRb = HashMap<Int, Boolean>()

    private fun isRoundabout(jid: Int, entry: Int, alts: List<Pair<Int, Float>>): Boolean {
        if (jid in map.roundabouts) return true
        geoRb[jid]?.let { return it }
        val nodes = HashSet<Int>(); nodes.add(entry)
        for (a in alts) nodes.add(map.deTo(a.first))
        var seenStraight = false; var rb = true
        for (node in nodes) {
            var i = 0
            while (i < router.outCount(node) && rb) {
                val j = router.outAt(node, i); i++
                if (map.eJid[j shr 1] != jid) continue
                val cnt = map.deCount(j)
                if (cnt < 3) continue
                if (abs(degDiff(tangent(j, true), tangent(j, false))) > 25f) continue // not carrying straight on
                val x0 = map.deX(j, 0); val z0 = map.deZ(j, 0); val x1 = map.deX(j, cnt - 1); val z1 = map.deZ(j, cnt - 1)
                val chord = hypot(x1 - x0, z1 - z0)
                if (chord < 12f) continue
                var bulge = 0f
                for (q in 0 until cnt) bulge = max(bulge, abs((map.deX(j, q) - x0) * (z1 - z0) - (map.deZ(j, q) - z0) * (x1 - x0)) / chord)
                if (bulge < 4f) rb = false else seenStraight = true // one straight path is really straight: an ordinary intersection
            }
        }
        val result = rb && seenStraight && nodes.size >= 3
        geoRb[jid] = result
        return result
    }

    // ------------------------------------------------------- roundabouts made of ordinary roads --
    // Many roundabouts are not one prefab: they are a ring of short curved road pieces (and small junction pieces) that the router sees as a
    // closed loop of directed edges: at most 14 edges, 80 to 260 m around, curving the same way for about one full turn.

    private class Ring(val edges: IntArray) { val set = edges.toHashSet() }
    private val ringOf = HashMap<Int, Ring?>()
    private val noRing = Ring(IntArray(0))

    private fun findRing(de0: Int): Ring? {
        ringOf[de0]?.let { return if (it === noRing) null else it }
        var found: Ring? = null
        val len0 = map.eLen[de0 shr 1]
        if (len0 <= 120f && abs(cumTurn(de0)) >= 8f) {
            val path = ArrayList<Int>(); path.add(de0)
            found = ringDfs(map.deFrom(de0), de0, path, len0, cumTurn(de0))
        }
        ringOf[de0] = found ?: noRing
        return found
    }

    /** The ring's junction points lie roughly on a circle (about 10 to 45 m across): a roundabout, not a block of streets. */
    private fun circular(path: List<Int>): Boolean {
        var cx = 0f; var cz = 0f
        for (e in path) { val nd = map.deFrom(e); cx += map.nodeX[nd]; cz += map.nodeZ[nd] }
        cx /= path.size; cz /= path.size
        var mn = Float.MAX_VALUE; var mx = 0f; var sum = 0f
        for (e in path) { val nd = map.deFrom(e); val r = hypot(map.nodeX[nd] - cx, map.nodeZ[nd] - cz); mn = min(mn, r); mx = max(mx, r); sum += r }
        val mean = sum / path.size
        return mean >= 10f && mean <= 45f && mx <= 1.45f * max(mn, 1f)
    }

    private fun ringDfs(start: Int, last: Int, path: ArrayList<Int>, length: Float, turn: Float): Ring? {
        if (path.size > 14) return null
        val cur = map.deTo(last)
        val endH = tangent(last, true)
        for (i in 0 until router.outCount(cur)) {
            val nx = router.outAt(cur, i)
            if (path.contains(nx)) continue
            val len = length + map.eLen[nx shr 1]
            if (len > 260f) continue
            val dh = degDiff(tangent(nx, false), endH)
            if (abs(dh) > 75f) continue
            val t = turn + dh + cumTurn(nx)
            path.add(nx)
            var res: Ring? = null
            if (map.deTo(nx) == start && path.size >= 3) {
                val close = degDiff(tangent(path[0], false), tangent(nx, true))
                val tot = t + close
                if (len >= 80f && abs(tot) >= 300f && abs(tot) <= 470f) {
                    val sg = if (tot > 0) 1f else -1f
                    var ok = true; var curved = 0
                    for (e in path) { val c = cumTurn(e) * sg; if (c < -12f) ok = false; if (c > 8f) curved++ }
                    if (ok && curved >= (if (path.size > 8) 3 else 2) && circular(path)) res = Ring(path.toIntArray())
                }
            } else res = ringDfs(start, nx, path, len, t)
            path.removeAt(path.size - 1)
            if (res != null) return res
        }
        return null
    }

    private class Span(val k0: Int, val k1: Int, val ring: Ring)

    /** Roundabouts on the route: first and last route index of the part that runs around a ring. */
    private fun ringSpans(): List<Span> {
        val spans = ArrayList<Span>()
        val n = route.size
        var k = 1
        while (k < n - 1) {
            val ring = findRing(route[k])
            if (ring == null) { k++; continue }
            var k1 = k
            while (k1 + 1 < n - 1 && route[k1 + 1] in ring.set) k1++
            var k0 = k
            while (k0 - 1 >= 1 && route[k0 - 1] in ring.set) k0--
            spans.add(Span(k0, k1, ring))
            k = k1 + 1
        }
        return spans
    }

    private fun buildManeuvers(): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        val hints = ArrayList<LaneHint>()
        val n = route.size
        // roundabouts built from ordinary road pieces: one instruction for the whole ring, nothing for the pieces inside it
        val inRing = BooleanArray(n)
        for (sp in ringSpans()) {
            val k0 = sp.k0; val k1 = sp.k1; val ring = sp.ring
            val k0e = if (k0 - 1 >= 1 && map.eJid[route[k0 - 1] shr 1] != 0 && route[k0 - 1] !in ring.set) k0 - 1 else k0
            val k1e = if (k1 + 1 < n - 1 && map.eJid[route[k1 + 1] shr 1] != 0 && route[k1 + 1] !in ring.set) k1 + 1 else k1
            val idx0 = ring.edges.indexOf(route[k0])
            var count = 0
            for (j in ring.edges.indices) {
                val re = ring.edges[(idx0 + j) % ring.edges.size]
                if (router.outCount(map.deTo(re)) >= 2) count++
                if (re == route[k1]) break
            }
            for (q in k0e..k1e) inRing[q] = true
            val ang = degDiff(tangent(route[k1e], true), tangent(route[k0e - 1], true))
            out.add(Maneuver(Turn.ROUNDABOUT, cum[k0e], max(1, count), ang, map.deX(route[k0e], 0), map.deZ(route[k0e], 0)))
        }
        for (k in 1 until n - 1) {
            if (inRing[k]) continue
            val de = route[k]
            val e = de shr 1
            val jid = map.eJid[e]
            if (jid == 0) continue
            val entry = map.deFrom(de)
            val cameFrom = map.deFrom(route[k - 1])
            // all junction paths of the same prefab that leave the entry node (excluding going back)
            val alts = ArrayList<Pair<Int, Float>>()
            for (i in 0 until router.outCount(entry)) {
                val j = router.outAt(entry, i)
                if (map.eJid[j shr 1] != jid || map.deTo(j) == cameFrom) continue
                alts.add(Pair(j, degDiff(tangent(j, true), tangent(j, false))))
            }
            if (alts.size < 2) continue
            val chosen = alts.firstOrNull { it.first == de } ?: continue
            val angle = chosen.second
            val straightest = alts.minByOrNull { abs(it.second) }!!
            val x = map.deX(de, 0); val z = map.deZ(de, 0)
            if (isRoundabout(jid, entry, alts)) {
                val sorted = alts.sortedByDescending { it.second }
                val no = sorted.indexOfFirst { it.first == de } + 1
                out.add(Maneuver(Turn.ROUNDABOUT, cum[k], no, angle, x, z))
                continue
            }
            val a = abs(angle)
            val right = angle > 0
            val before = out.size
            val laneList = lanesFor(alts, de)
            // a ramp that loops more than half a turn (up to 270 degrees) ends up pointing the "other" way once the angle is wrapped to +-180:
            // judge which way it LEAVES the road instead (compare its start with the straight-on road)
            when {
                straightest.first != de && abs(cumTurn(de)) > 160f && abs(straightest.second) < 40f -> {
                    val leavesRight = degDiff(tangent(de, false, 15f), tangent(straightest.first, false, 15f)) > 0f
                    val mv = Maneuver(if (leavesRight) Turn.BEAR_RIGHT else Turn.BEAR_LEFT, cum[k], 0, angle, x, z)
                    mv.exit = true
                    out.add(mv)
                }
                a < 22f -> if (straightest.first != de) {
                    out.add(Maneuver(if (right) Turn.KEEP_RIGHT else Turn.KEEP_LEFT, cum[k], 0, angle, x, z))
                }
                a < 55f -> out.add(Maneuver(if (right) Turn.BEAR_RIGHT else Turn.BEAR_LEFT, cum[k], 0, angle, x, z))
                a < 135f -> out.add(Maneuver(if (right) Turn.TURN_RIGHT else Turn.TURN_LEFT, cum[k], 0, angle, x, z))
                else -> out.add(Maneuver(Turn.UTURN, cum[k], 0, angle, x, z))
            }
            if (out.size > before) out[out.size - 1].lanes = laneList
            // also hint junctions where the route just carries straight on but some lanes would leave it
            if (laneList != null && (out.size > before || laneList.any { !it.preferred })) hints.add(LaneHint(cum[k], laneList))
        }
        out.sortBy { it.atM }
        val last = route[n - 1]
        val m = map.deCount(last)
        out.add(Maneuver(Turn.ARRIVE, cum[n], 0, 0f, map.deX(last, m - 1), map.deZ(last, m - 1)))
        laneHints = hints
        return out
    }

    /** Lane arrows for the approach to the junction path [de], from the lane table of the map (null when unknown). */
    private fun lanesFor(alts: List<Pair<Int, Float>>, de: Int): List<Lane>? {
        val n = map.lanesAtEntry(de shr 1)
        val chosen = map.laneMaskOf(de shr 1)
        if (n < 2 || chosen == 0) return null
        val mine = alts.first { it.first == de }.second
        val out = ArrayList<Lane>()
        for (i in 0 until n) {
            val dirs = alts.filter { (map.laneMaskOf(it.first shr 1) shr i) and 1 != 0 }
            if (dirs.isEmpty()) { out.add(Lane(Float.NaN, false, null)); continue }
            val pref = (chosen shr i) and 1 != 0
            val main = if (pref) mine else dirs.minByOrNull { abs(it.second) }!!.second
            val other = dirs.map { it.second }.filter { abs(it - main) > 20f }.minByOrNull { abs(it) }
            out.add(Lane(main, pref, other))
        }
        return out
    }

    /** For each maneuver, the first town the route passes after it (within 2 km of the road). */
    private fun assignTowards() {
        val passAt = FloatArray(map.cities.size) { Float.MAX_VALUE }
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (de in route) for (i in 0 until map.deCount(de)) {
            val x = map.deX(de, i); val z = map.deZ(de, i)
            minX = min(minX, x); maxX = max(maxX, x); minZ = min(minZ, z); maxZ = max(maxZ, z)
        }
        val cand = ArrayList<Int>()
        for ((ci, c) in map.cities.withIndex()) {
            if (c.x > minX - 2000f && c.x < maxX + 2000f && c.z > minZ - 2000f && c.z < maxZ + 2000f) cand.add(ci)
        }
        val bestD = FloatArray(map.cities.size) { Float.MAX_VALUE }
        for (k in route.indices) {
            val de = route[k]
            val n = map.deCount(de)
            val len = map.eLen[de shr 1]
            for (i in 0 until n step 2) {
                val x = map.deX(de, i); val z = map.deZ(de, i)
                for (ci in cand) {
                    val c = map.cities[ci]
                    val dx = c.x - x; val dz = c.z - z
                    if (dx > 2000f || dx < -2000f || dz > 2000f || dz < -2000f) continue
                    val d = hypot(dx, dz)
                    if (d < 2000f && d < bestD[ci]) { bestD[ci] = d; passAt[ci] = cum[k] + len * i / max(1, n - 1) }
                }
            }
        }
        for (m in maneuvers) {
            if (m.turn == Turn.ARRIVE) continue
            var best = -1
            for (ci in cand) if (passAt[ci] > m.atM + 250f && (best < 0 || passAt[ci] < passAt[best])) best = ci
            if (best >= 0) m.towards = map.cities[best].name
        }
    }

    // --------------------------------------------------------------------- following --

    private class Proj(val dist: Float, val along: Float)

    private fun project(de: Int, x: Float, z: Float): Proj {
        var best = Float.MAX_VALUE
        var bestAlong = 0f
        var cumLen = 0f
        val n = map.deCount(de)
        for (i in 0 until n - 1) {
            val x0 = map.deX(de, i); val z0 = map.deZ(de, i)
            val x1 = map.deX(de, i + 1); val z1 = map.deZ(de, i + 1)
            val dx = x1 - x0; val dz = z1 - z0
            val l2 = dx * dx + dz * dz
            val seg = kotlin.math.sqrt(l2)
            var t = if (l2 > 1e-6f) ((x - x0) * dx + (z - z0) * dz) / l2 else 0f
            t = t.coerceIn(0f, 1f)
            val d = hypot(x - (x0 + dx * t), z - (z0 + dz * t))
            if (d < best) { best = d; bestAlong = cumLen + seg * t }
            cumLen += seg
        }
        return Proj(best, bestAlong)
    }

    /** Feed the latest truck position; returns guidance for this instant. */
    fun update(x: Float, z: Float, speedMps: Float): NavState {
        if (route.isEmpty()) return NavState(0f, null, null, 0f, 0f, false, false, false, null)
        // find where on the route we are (look a little behind and well ahead)
        var bestK = idx
        var bestD = Float.MAX_VALUE
        var bestAlong = 0f
        for (k in max(0, idx - 1)..min(route.size - 1, idx + 10)) {
            val p = project(route[k], x, z)
            val score = p.dist + abs(k - idx) * 0.5f
            if (score < bestD) { bestD = score; bestK = k; bestAlong = p.along }
        }
        val rawDist = project(route[bestK], x, z).dist
        if (rawDist > OFF_ROUTE_M) offCount++ else offCount = 0
        val offRoute = offCount >= 8
        if (rawDist <= OFF_ROUTE_M) {
            idx = bestK
            val p = cum[idx] + bestAlong
            progress = if (p >= progress - 5f) max(progress, p) else p // ignore tiny backward jitter
        }
        val remaining = max(0f, cum[route.size] - progress)
        val arrived = remaining < 25f
        val remS = max(0f, secs[route.size] - (secs[idx] + (progress - cum[idx]) / map.eLen[route[idx] shr 1] * (secs[idx + 1] - secs[idx])))

        var ni = -1
        for (i in maneuvers.indices) if (maneuvers[i].atM > progress - 12f) { ni = i; break }
        val next = if (ni >= 0) maneuvers[ni] else null
        val following = if (ni >= 0 && ni + 1 < maneuvers.size) maneuvers[ni + 1] else null
        val toNext = if (next != null) max(0f, next.atM - progress) else 0f

        var speech: String? = null
        if (next != null && !offRoute) {
            val tau = max(speedMps, 4f)
            val d1 = max(tau * 26f, 300f)
            val d2 = max(tau * 10f, 110f)
            val d3 = max(tau * 3.5f, 35f)
            val want = when {
                toNext <= d3 -> 3
                toNext <= d2 -> 2
                toNext <= d1 -> 1
                else -> 0
            }
            if (want > stage[ni]) {
                stage[ni] = want
                memo[memoKey(next)] = Pair(want, System.nanoTime() / 1_000_000)
                speech = phrase(next, following, toNext, want)
            }
        }
        val hint = laneHints.firstOrNull { it.atM > progress - 12f }
        return NavState(toNext, next, following, remaining, remS, offRoute, rawDist <= OFF_ROUTE_M, arrived, speech, hint?.lanes, if (hint != null) max(0f, hint.atM - progress) else 0f)
    }

    /** What the route's last point is called in speech: "your destination", or "your waypoint" on a leg that ends at a stop. */
    var arriveWord = "your destination"

    private fun phrase(m: Maneuver, then: Maneuver?, dist: Float, stage: Int): String {
        val base = instruction(m)
        val tail = if (then != null && then.turn != Turn.ARRIVE && then.atM - m.atM < 320f && m.turn != Turn.ARRIVE)
            ", then " + instruction(then) else ""
        return when {
            m.turn == Turn.ARRIVE ->
                if (stage >= 3) "You have arrived at $arriveWord" else "In ${spokenDistance(dist)}, you will arrive at $arriveWord"
            stage >= 3 -> base.replaceFirstChar { it.uppercase() } + tail
            else -> "In ${spokenDistance(dist)}, $base${m.towards?.let { " towards $it" } ?: ""}$tail"
        }
    }

    fun instruction(m: Maneuver): String = when (m.turn) {
        Turn.KEEP_LEFT -> "keep left"
        Turn.KEEP_RIGHT -> "keep right"
        Turn.BEAR_LEFT -> if (m.exit) "take the exit on the left" else "bear left"
        Turn.BEAR_RIGHT -> if (m.exit) "take the exit on the right" else "bear right"
        Turn.TURN_LEFT -> "turn left"
        Turn.TURN_RIGHT -> "turn right"
        Turn.UTURN -> "make a U-turn"
        Turn.ROUNDABOUT -> "at the roundabout, take the ${ordinal(m.exitNo)} exit"
        Turn.ARRIVE -> "arrive at $arriveWord"
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; 6 -> "sixth"
        7 -> "seventh"; 8 -> "eighth"; else -> "${n}th"
    }

    fun spokenDistance(worldM: Float): String {
        val m = worldM * distScale
        return when {
            m < 950f -> "${max(50, (m / 50f).roundToInt() * 50)} meters"
            m < 10000f -> {
                val half = (m / 500f).roundToInt() / 2f
                if (half == half.toInt().toFloat()) "${half.toInt()} kilometers" else "$half kilometers"
            }
            else -> "${(m / 1000f).roundToInt()} kilometers"
        }
    }

    fun shortDistance(worldM: Float): String {
        val m = worldM * distScale
        return when {
            m < 1000f -> "${max(10, (m / 10f).roundToInt() * 10)} m"
            m < 10000f -> String.format(java.util.Locale.US, "%.1f km", m / 1000f)
            else -> "${(m / 1000f).roundToInt()} km"
        }
    }

    companion object {
        const val OFF_ROUTE_M = 45f
    }
}
