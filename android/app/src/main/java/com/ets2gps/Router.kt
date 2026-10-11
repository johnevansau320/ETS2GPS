package com.ets2gps

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How routes are chosen. [factors] scale travel time by road grade (minor, wide local, expressway, motorway):
 * below 1 attracts the route, above 1 repels it. [junctionExtra] adds seconds per junction path (turning a truck
 * through town junctions is slow and awkward).
 */
enum class RouteMode(val label: String, val short: String, val factors: FloatArray, val junctionExtra: Float) {
    HEAVY("Heavy transport", "Heavy", floatArrayOf(2.0f, 1.3f, 1.0f, 0.8f), 4f),
    FASTEST("Fastest", "Fast", floatArrayOf(1f, 1f, 1f, 1f), 0f),
    TOURIST("Tourist", "Tour", floatArrayOf(0.8f, 0.8f, 1.6f, 2.8f), 0f),
}

/** Where a world position sits on the road network. */
class Snap(
    val de: Int, // directed edge id (2 * edge + direction)
    val dist: Float, // lateral distance to the road, metres
    val along: Float, // metres travelled along the directed edge from its start
    val seg: Int, // segment index within the directed edge
)

class RouteResult(val edges: IntArray, val lengthM: Float, val timeS: Float)

/**
 * Directed-edge A* over the network, a snapping helper and the "main network" filter used so that
 * destinations never land on an isolated stub.
 */
class Router(val map: MapData) {
    private val outStart = IntArray(map.nodeCount + 1)
    private val outList: IntArray
    val inMain = BooleanArray(map.nodeCount)

    // A* scratch space, reused between queries via generation stamps
    private val edgeW = FloatArray(map.edgeCount) // route-mode cost multiplier per edge
    private var junctionExtra = 0f
    private val blocked = BooleanArray(map.edgeCount) // roads the user asked to avoid
    private var minFactor = 1f

    var mode: RouteMode = RouteMode.HEAVY
        set(v) { field = v; rebuildCosts() }

    private val gStamp = IntArray(map.edgeCount * 2)
    private val gVal = FloatArray(map.edgeCount * 2)
    private val prev = IntArray(map.edgeCount * 2)
    private val closedStamp = IntArray(map.edgeCount * 2)
    private var gen = 0

    init {
        val cnt = IntArray(map.nodeCount + 1)
        for (e in 0 until map.edgeCount) {
            cnt[map.eFrom[e] + 1]++
            if (map.isTwoWay(e)) cnt[map.eTo[e] + 1]++
        }
        for (i in 1..map.nodeCount) cnt[i] += cnt[i - 1]
        System.arraycopy(cnt, 0, outStart, 0, cnt.size)
        outList = IntArray(cnt[map.nodeCount])
        val fill = IntArray(map.nodeCount)
        for (e in 0 until map.edgeCount) {
            val a = map.eFrom[e]
            outList[outStart[a] + fill[a]++] = e * 2
            if (map.isTwoWay(e)) {
                val b = map.eTo[e]
                outList[outStart[b] + fill[b]++] = e * 2 + 1
            }
        }
        computeMainComponent()
        rebuildCosts()
    }

    private fun rebuildCosts() {
        val f = mode.factors
        var mn = 1f
        for (e in 0 until map.edgeCount) {
            var w = if (map.eJid[e] != 0 || map.isFerry(e)) 1f else f[map.eGrade[e].toInt().coerceIn(0, 3)]
            // hidden items are invisible and often blocked in game: use them only when there is no sensible alternative
            if (map.isHidden(e)) w *= HIDDEN_FACTOR
            edgeW[e] = w
            if (w < mn) mn = w
        }
        minFactor = mn
        junctionExtra = mode.junctionExtra
    }

    /** Marks every road within ~25 m of each (x, z) point as forbidden for routing. */
    fun setAvoid(points: List<Pair<Float, Float>>) {
        blocked.fill(false)
        for ((x, z) in points) {
            map.queryEdges(x, z, 30f) { e ->
                if (map.eJid[e] != 0 || map.isFerry(e)) return@queryEdges
                var near = false
                for (p in map.ePtStart[e] until map.ePtStart[e + 1] - 1) {
                    val x0 = map.ptX[p]; val z0 = map.ptZ[p]; val dx = map.ptX[p + 1] - x0; val dz = map.ptZ[p + 1] - z0
                    val l2 = dx * dx + dz * dz
                    val t = if (l2 > 1e-6f) (((x - x0) * dx + (z - z0) * dz) / l2).coerceIn(0f, 1f) else 0f
                    if (hypot(x - (x0 + dx * t), z - (z0 + dz * t)) < 25f) { near = true; break }
                }
                if (near) blocked[e] = true
            }
        }
    }

    /** What the search minimises: travel time weighted by the route mode. [edgeSeconds] stays the true time. */
    fun edgeCost(de: Int): Float {
        val e = de shr 1
        return edgeSeconds(de) * edgeW[e] + (if (map.eJid[e] != 0) junctionExtra else 0f)
    }

    fun outCount(node: Int) = outStart[node + 1] - outStart[node]
    fun outAt(node: Int, i: Int) = outList[outStart[node] + i]

    fun edgeSeconds(de: Int): Float {
        val e = de shr 1
        val v = map.eSpeed[e].toInt().coerceAtLeast(10) / 3.6f
        return map.eLen[e] / v + (if (map.eJid[e] != 0) JUNCTION_PENALTY_S else 0f)
    }

    // ------------------------------------------------------------------ main component --

    /** Largest strongly connected component (iterative Kosaraju). */
    private fun computeMainComponent() {
        val n = map.nodeCount
        val rStart = IntArray(n + 1)
        for (e in 0 until map.edgeCount) {
            rStart[map.eTo[e] + 1]++
            if (map.isTwoWay(e)) rStart[map.eFrom[e] + 1]++
        }
        for (i in 1..n) rStart[i] += rStart[i - 1]
        val rList = IntArray(rStart[n])
        val rFill = IntArray(n)
        for (e in 0 until map.edgeCount) {
            val a = map.eFrom[e]; val b = map.eTo[e]
            rList[rStart[b] + rFill[b]++] = a
            if (map.isTwoWay(e)) rList[rStart[a] + rFill[a]++] = b
        }
        val seen = BooleanArray(n)
        val order = IntArray(n)
        var oc = 0
        val stackNode = IntArray(n)
        val stackIdx = IntArray(n)
        for (s in 0 until n) {
            if (seen[s] || outCount(s) == 0) continue
            var sp = 0
            stackNode[0] = s; stackIdx[0] = 0; sp = 1
            seen[s] = true
            while (sp > 0) {
                val u = stackNode[sp - 1]
                val i = stackIdx[sp - 1]
                if (i < outCount(u)) {
                    stackIdx[sp - 1] = i + 1
                    val v = map.deTo(outAt(u, i))
                    if (!seen[v]) {
                        seen[v] = true
                        stackNode[sp] = v; stackIdx[sp] = 0; sp++
                    }
                } else {
                    order[oc++] = u
                    sp--
                }
            }
        }
        val comp = IntArray(n) { -1 }
        var best = 0
        var bestId = -1
        var cid = 0
        val stack = IntArray(n)
        for (k in oc - 1 downTo 0) {
            val s = order[k]
            if (comp[s] != -1) continue
            comp[s] = cid
            var sp = 0
            stack[sp++] = s
            var cnt = 0
            while (sp > 0) {
                val u = stack[--sp]
                cnt++
                for (j in rStart[u] until rStart[u + 1]) {
                    val v = rList[j]
                    if (comp[v] == -1) {
                        comp[v] = cid
                        stack[sp++] = v
                    }
                }
            }
            if (cnt > best) { best = cnt; bestId = cid }
            cid++
        }
        for (i in 0 until n) inMain[i] = comp[i] == bestId
    }

    // ----------------------------------------------------------------------- snapping --

    /**
     * Closest drivable position to (x, z). When [headingRad] is given (bearing, clockwise from north)
     * the direction of travel is chosen to match it and misaligned roads are penalised.
     */
    fun snap(x: Float, z: Float, headingRad: Float?, radius: Float = 60f): Snap? {
        var best: Snap? = null
        var bestScore = Float.MAX_VALUE
        map.queryEdges(x, z, radius) { e ->
            val a = map.ePtStart[e]
            val b = map.ePtStart[e + 1]
            var cum = 0f
            for (p in a until b - 1) {
                val x0 = map.ptX[p]; val z0 = map.ptZ[p]
                val x1 = map.ptX[p + 1]; val z1 = map.ptZ[p + 1]
                val dx = x1 - x0; val dz = z1 - z0
                val l2 = dx * dx + dz * dz
                val segLen = sqrt(l2)
                var t = if (l2 > 1e-6f) ((x - x0) * dx + (z - z0) * dz) / l2 else 0f
                t = t.coerceIn(0f, 1f)
                val px = x0 + dx * t; val pz = z0 + dz * t
                val d = hypot(x - px, z - pz)
                if (d <= radius) {
                    val bearing = atan2(dx, -dz) // direction of increasing point index
                    for (dir in 0..1) {
                        if (dir == 1 && !map.isTwoWay(e)) continue
                        var penalty = 0f
                        if (headingRad != null) {
                            val br = if (dir == 0) bearing else bearing + Math.PI.toFloat()
                            val diff = abs(angleDiff(br, headingRad))
                            penalty = if (diff > 1.9f) 1000f else diff * 12f // ~110 deg is "opposite"
                        }
                        val score = d + penalty
                        if (score < bestScore) {
                            bestScore = score
                            val alongFwd = cum + segLen * t
                            val total = map.eLen[e]
                            best = if (dir == 0) Snap(e * 2, d, alongFwd, p - a)
                            else Snap(e * 2 + 1, d, (total - alongFwd).coerceAtLeast(0f), (b - p - 2).coerceAtLeast(0))
                        }
                    }
                }
                cum += segLen
            }
        }
        return best
    }

    /** Nearest node in the main network, searching outwards. */
    fun nearestMainNode(x: Float, z: Float): Int {
        var r = 300f
        while (r <= 20000f) {
            var best = -1
            var bestD = Float.MAX_VALUE
            map.queryEdges(x, z, r) { e ->
                for (n in intArrayOf(map.eFrom[e], map.eTo[e])) {
                    if (!inMain[n]) continue
                    // invisible (hidden) and user-avoided roads are bad places to finish: the game's own GPS
                    // ends on a visible road, so prefer one unless it is much further away
                    val pen = if (blocked[e]) 600f else if (map.isHidden(e)) 150f else 0f
                    val d = hypot(map.nodeX[n] - x, map.nodeZ[n] - z) + pen
                    if (d < bestD) { bestD = d; best = n }
                }
            }
            if (best >= 0 && bestD <= r) return best
            r *= 3f
        }
        return -1
    }

    // --------------------------------------------------------------------------- A* --

    /**
     * Fastest route that starts by finishing directed edge [startDe] (with [remainingM] metres of it left)
     * and ends at [destNode].
     */
    fun route(startDe: Int, remainingM: Float, destNode: Int): RouteResult? {
        gen++
        val dx = map.nodeX[destNode]; val dz = map.nodeZ[destNode]
        val vmax = 110f / 3.6f / minFactor // a lower bound on cost per metre keeps A* admissible
        val heap = Heap()
        val startE = startDe shr 1
        val startCost = edgeCost(startDe) * (remainingM / map.eLen[startE]).coerceIn(0f, 1f)
        gStamp[startDe] = gen; gVal[startDe] = startCost; prev[startDe] = -1
        heap.push(startCost + h(map.deTo(startDe), dx, dz, vmax), startDe)
        var goal = -1
        var pops = 0
        while (heap.size > 0) {
            val cur = heap.pop()
            if (closedStamp[cur] == gen) continue
            closedStamp[cur] = gen
            if (++pops > 6_000_000) break
            val node = map.deTo(cur)
            if (node == destNode) { goal = cur; break }
            val g = gVal[cur]
            val curE = cur shr 1
            for (i in 0 until outCount(node)) {
                val nx = outAt(node, i)
                if (nx shr 1 == curE) continue // no U-turn on the same road
                if (blocked[nx shr 1]) continue
                if (closedStamp[nx] == gen) continue
                val ng = g + edgeCost(nx)
                if (gStamp[nx] != gen || ng < gVal[nx]) {
                    gStamp[nx] = gen; gVal[nx] = ng; prev[nx] = cur
                    heap.push(ng + h(map.deTo(nx), dx, dz, vmax), nx)
                }
            }
        }
        if (goal < 0) return null
        val list = ArrayList<Int>()
        var c = goal
        while (c != -1) { list.add(c); c = prev[c] }
        list.reverse()
        val arr = list.toIntArray()
        var len = remainingM
        for (k in 1 until arr.size) len += map.eLen[arr[k] shr 1]
        return RouteResult(arr, len, gVal[goal])
    }

    private fun h(node: Int, dx: Float, dz: Float, vmax: Float): Float =
        hypot(map.nodeX[node] - dx, map.nodeZ[node] - dz) / vmax

    /** Minimal binary min-heap on (priority, value) pairs. */
    private class Heap {
        private var pri = FloatArray(1024)
        private var vals = IntArray(1024)
        var size = 0
            private set

        fun push(p: Float, v: Int) {
            if (size == pri.size) {
                pri = pri.copyOf(size * 2)
                vals = vals.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) shr 1
                if (pri[parent] <= p) break
                pri[i] = pri[parent]; vals[i] = vals[parent]
                i = parent
            }
            pri[i] = p; vals[i] = v
        }

        fun pop(): Int {
            val top = vals[0]
            size--
            if (size > 0) {
                val p = pri[size]; val v = vals[size]
                var i = 0
                while (true) {
                    var c = i * 2 + 1
                    if (c >= size) break
                    if (c + 1 < size && pri[c + 1] < pri[c]) c++
                    if (pri[c] >= p) break
                    pri[i] = pri[c]; vals[i] = vals[c]
                    i = c
                }
                pri[i] = p; vals[i] = v
            }
            return top
        }
    }

    companion object {
        const val JUNCTION_PENALTY_S = 3f
        const val HIDDEN_FACTOR = 14f

        /** Smallest signed difference a - b in radians, in (-pi, pi]. */
        fun angleDiff(a: Float, b: Float): Float {
            var d = (a - b) % (2 * Math.PI.toFloat())
            if (d > Math.PI) d -= 2 * Math.PI.toFloat()
            if (d <= -Math.PI) d += 2 * Math.PI.toFloat()
            return d
        }

        fun bearing(dx: Float, dz: Float): Float = atan2(dx, -dz)

        fun forward(bearing: Float): Pair<Float, Float> = Pair(sin(bearing), -cos(bearing))
    }
}
