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
}

class NavState(
    val distToNextM: Float, // world metres
    val next: Maneuver?,
    val following: Maneuver?,
    val remainingM: Float,
    val remainingS: Float,
    val offRoute: Boolean,
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
    fun setRoute(res: RouteResult, remainingM: Float) {
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

    private fun buildManeuvers(): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        val hints = ArrayList<LaneHint>()
        val n = route.size
        for (k in 1 until n - 1) {
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
            if (jid in map.roundabouts) {
                val sorted = alts.sortedByDescending { it.second }
                val no = sorted.indexOfFirst { it.first == de } + 1
                out.add(Maneuver(Turn.ROUNDABOUT, cum[k], no, angle, x, z))
                continue
            }
            val a = abs(angle)
            val right = angle > 0
            val before = out.size
            val laneList = lanesFor(alts, de)
            when {
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
        if (route.isEmpty()) return NavState(0f, null, null, 0f, 0f, false, false, null)
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
                speech = phrase(next, following, toNext, want)
            }
        }
        val hint = laneHints.firstOrNull { it.atM > progress - 12f }
        return NavState(toNext, next, following, remaining, remS, offRoute, arrived, speech, hint?.lanes, if (hint != null) max(0f, hint.atM - progress) else 0f)
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
        Turn.BEAR_LEFT -> "bear left"
        Turn.BEAR_RIGHT -> "bear right"
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
