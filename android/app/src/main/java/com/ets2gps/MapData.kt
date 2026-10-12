package com.ets2gps

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Poi(val type: Int, val x: Float, val z: Float) {
    companion object {
        const val FUEL = 1; const val SERVICE = 2; const val PARKING = 3; const val WEIGH = 4
        const val DEALER = 5; const val GARAGE = 6; const val RECRUIT = 7; const val HOTEL = 8
        fun label(t: Int) = when (t) {
            FUEL -> "Fuel station"; SERVICE -> "Service centre"; PARKING -> "Rest area / parking"
            WEIGH -> "Weigh station"; DEALER -> "Truck dealer"; GARAGE -> "Garage"; RECRUIT -> "Recruitment agency"
            else -> "Hotel"
        }
    }
}

class Company(val id: String, val cityId: String, val x: Float, val z: Float)

class City(val id: String, val name: String, val x: Float, val z: Float) {
    /** lower-case, accent-free name for searching */
    val key: String = normalize(name)

    companion object {
        fun normalize(s: String): String {
            val d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            return d.replace(Regex("\\p{M}+"), "").lowercase()
        }
    }
}

/**
 * The road network produced by companion/build_map.py (etsmap.bin, format version 2).
 *
 * Edges are polylines between nodes. flags bit0 = two-way; otherwise only from -> to is drivable.
 * cls: 0 local, 1 regional, 2 highway, 3 junction (prefab path). World units are metres in the game's
 * (x east, z south) coordinate system, i.e. the same space the telemetry plugin reports.
 */
class MapData(
    val minX: Float, val minZ: Float, val maxX: Float, val maxZ: Float,
    val nodeX: FloatArray, val nodeZ: FloatArray,
    val eFrom: IntArray, val eTo: IntArray,
    val eFlags: ByteArray, val eCls: ByteArray,
    val eGrade: ByteArray, // 0 minor, 1 wide local, 2 expressway, 3 motorway
    val eSpeed: ShortArray, // km/h
    val eWidth: FloatArray, // metres
    val eJid: IntArray, // 0 for roads, prefab uid hash for junction paths
    val ePtStart: IntArray, // size edgeCount + 1
    val ptX: FloatArray, val ptZ: FloatArray,
    val eLen: FloatArray,
    val junctionNodes: HashMap<Int, Int>,
    val roundabouts: HashSet<Int>,
    val cities: List<City>,
    val companies: List<Company>,
    val pois: List<Poi>,
    /** Per junction edge: lanes at the entry (0 = unknown) and the bitmask of lanes (from the left) that take this path. */
    val laneN: ByteArray = ByteArray(0),
    val laneMask: ShortArray = ShortArray(0),
) {
    val nodeCount get() = nodeX.size
    val edgeCount get() = eFrom.size

    // ---- spatial grid over edges (CSR layout) ----
    val cell = 400f
    val cols = ((maxX - minX) / cell).toInt() + 2
    val rows = ((maxZ - minZ) / cell).toInt() + 2
    private val cellStart: IntArray
    private val cellEdges: IntArray
    private val stamp = IntArray(edgeCount)
    private var stampGen = 0

    init {
        val counts = IntArray(cols * rows + 1)
        forEachEdgeCell { _, c -> counts[c + 1]++ }
        for (i in 1 until counts.size) counts[i] += counts[i - 1]
        cellStart = counts
        cellEdges = IntArray(counts[counts.size - 1])
        val fill = IntArray(cols * rows)
        forEachEdgeCell { e, c ->
            cellEdges[cellStart[c] + fill[c]] = e
            fill[c]++
        }
    }

    private inline fun forEachEdgeCell(f: (Int, Int) -> Unit) {
        for (e in 0 until edgeCount) {
            var x0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var z0 = Float.MAX_VALUE
            var z1 = -Float.MAX_VALUE
            for (p in ePtStart[e] until ePtStart[e + 1]) {
                x0 = min(x0, ptX[p]); x1 = max(x1, ptX[p])
                z0 = min(z0, ptZ[p]); z1 = max(z1, ptZ[p])
            }
            if (x0 > x1) continue
            val cx0 = cellX(x0); val cx1 = cellX(x1)
            val cz0 = cellZ(z0); val cz1 = cellZ(z1)
            for (cz in cz0..cz1) for (cx in cx0..cx1) f(e, cz * cols + cx)
        }
    }

    private fun cellX(x: Float) = ((x - minX) / cell).toInt().coerceIn(0, cols - 1)
    private fun cellZ(z: Float) = ((z - minZ) / cell).toInt().coerceIn(0, rows - 1)

    /** Calls [f] once for every edge whose grid cells intersect the square [xÂ±r, zÂ±r]. */
    inline fun queryEdges(x: Float, z: Float, r: Float, f: (Int) -> Unit) {
        val gen = nextStamp()
        val cx0 = cellXPublic(x - r); val cx1 = cellXPublic(x + r)
        val cz0 = cellZPublic(z - r); val cz1 = cellZPublic(z + r)
        for (cz in cz0..cz1) for (cx in cx0..cx1) {
            val c = cz * cols + cx
            for (k in cellStartAt(c) until cellStartAt(c + 1)) {
                val e = cellEdgeAt(k)
                if (!testAndMark(e, gen)) f(e)
            }
        }
    }

    fun cellXPublic(x: Float) = cellX(x)
    fun cellZPublic(z: Float) = cellZ(z)
    fun cellStartAt(i: Int) = cellStart[i]
    fun cellEdgeAt(i: Int) = cellEdges[i]
    fun nextStamp(): Int { stampGen++; return stampGen }
    fun testAndMark(e: Int, gen: Int): Boolean {
        if (stamp[e] == gen) return true
        stamp[e] = gen
        return false
    }

    fun lanesAtEntry(e: Int): Int = if (e < laneN.size) laneN[e].toInt() else 0
    fun laneMaskOf(e: Int): Int = if (e < laneMask.size) laneMask[e].toInt() and 0xFFFF else 0

    fun isTwoWay(e: Int) = (eFlags[e].toInt() and 1) != 0
    fun isFerry(e: Int) = (eFlags[e].toInt() and 8) != 0

    /** Flagged hidden in the game data: invisible roads that the in-game GPS does not route over. */
    fun isHidden(e: Int) = (eFlags[e].toInt() and 16) != 0

    /** Start / end node of a directed edge (id = 2 * edge + direction). */
    fun deFrom(de: Int): Int = if (de and 1 == 0) eFrom[de shr 1] else eTo[de shr 1]
    fun deTo(de: Int): Int = if (de and 1 == 0) eTo[de shr 1] else eFrom[de shr 1]

    /** i-th point of a directed edge, i in 0 until deCount(de) */
    fun deCount(de: Int): Int { val e = de shr 1; return ePtStart[e + 1] - ePtStart[e] }
    fun deX(de: Int, i: Int): Float {
        val e = de shr 1
        return if (de and 1 == 0) ptX[ePtStart[e] + i] else ptX[ePtStart[e + 1] - 1 - i]
    }
    fun deZ(de: Int, i: Int): Float {
        val e = de shr 1
        return if (de and 1 == 0) ptZ[ePtStart[e] + i] else ptZ[ePtStart[e + 1] - 1 - i]
    }

    fun distance(x0: Float, z0: Float, x1: Float, z1: Float): Float = hypot(x1 - x0, z1 - z0)

    fun findCity(id: String): City? = cities.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun findCompany(cityId: String, companyId: String): Company? =
        companies.firstOrNull { it.id.equals(companyId, true) && it.cityId.equals(cityId, true) }

    companion object {
        fun load(file: File): MapData {
            val raw = InflaterInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { it.readBytes() }
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            b.get(magic)
            require(String(magic) == "ETSM") { "not an ETS2 GPS map file" }
            val ver = b.short.toInt()
            require(ver in 2..8) { "unsupported map version $ver (update the app or rebuild the map)" }
            b.short
            val minX = b.float; val minZ = b.float; val maxX = b.float; val maxZ = b.float

            val nn = b.int
            val nodeX = FloatArray(nn); val nodeZ = FloatArray(nn)
            for (i in 0 until nn) { nodeX[i] = b.float; nodeZ[i] = b.float }

            val jn = b.int
            val junctionNodes = HashMap<Int, Int>(jn * 2)
            val roundabouts = HashSet<Int>()
            for (i in 0 until jn) {
                val jid = b.int
                val cnt = b.get().toInt() and 0xFF
                val rb = b.get().toInt() and 0xFF
                junctionNodes[jid] = cnt
                if (rb != 0) roundabouts.add(jid)
            }

            val en = b.int
            val edgesPos = b.position()
            // pass 1: total point count
            var total = 0
            run {
                var p = edgesPos
                for (i in 0 until en) {
                    val npts = raw[p + 16].toInt() and 0xFF
                    total += npts
                    p += 17 + 4 * npts
                }
            }
            val eFrom = IntArray(en); val eTo = IntArray(en)
            val eFlags = ByteArray(en); val eCls = ByteArray(en); val eGrade = ByteArray(en)
            val eSpeed = ShortArray(en); val eWidth = FloatArray(en)
            val eJid = IntArray(en)
            val ePtStart = IntArray(en + 1)
            val ptX = FloatArray(total); val ptZ = FloatArray(total)
            val eLen = FloatArray(en)
            var pp = 0
            for (i in 0 until en) {
                eFrom[i] = b.int; eTo[i] = b.int
                eFlags[i] = b.get(); eCls[i] = b.get()
                eGrade[i] = if (ver >= 6) ((eFlags[i].toInt() shr 1) and 3).toByte()
                else when (eCls[i].toInt()) { 2 -> 3; 1 -> 2; else -> 0 }.toByte()
                eSpeed[i] = ((b.get().toInt() and 0xFF) * 5).toShort()
                eWidth[i] = (b.get().toInt() and 0xFF) / 4f
                eJid[i] = b.int
                val npts = b.get().toInt() and 0xFF
                ePtStart[i] = pp
                var qx = floor(nodeX[eFrom[i]] * 4.0 + 0.5).toInt()
                var qz = floor(nodeZ[eFrom[i]] * 4.0 + 0.5).toInt()
                var len = 0f
                for (k in 0 until npts) {
                    qx += b.short.toInt(); qz += b.short.toInt()
                    ptX[pp] = qx / 4f; ptZ[pp] = qz / 4f
                    if (k > 0) len += hypot(ptX[pp] - ptX[pp - 1], ptZ[pp] - ptZ[pp - 1])
                    pp++
                }
                eLen[i] = max(len, 0.5f)
            }
            ePtStart[en] = pp

            val cn = b.int
            val cities = ArrayList<City>(cn)
            for (i in 0 until cn) {
                val id = readStr(b); val name = readStr(b)
                cities.add(City(id, name, b.float, b.float))
            }
            val companies = ArrayList<Company>()
            if (ver >= 3 && b.remaining() >= 4) {
                val kn = b.int
                for (i in 0 until kn) {
                    val id = readStr(b); val cityId = readStr(b)
                    companies.add(Company(id, cityId, b.float, b.float))
                }
            }
            val pois = ArrayList<Poi>()
            if (ver >= 5 && b.remaining() >= 4) {
                val pn = b.int
                for (i in 0 until pn) {
                    val t = b.get().toInt() and 0xFF
                    pois.add(Poi(t, b.float, b.float))
                }
            }
            var laneN = ByteArray(0); var laneMask = ShortArray(0)
            if (ver >= 8 && b.remaining() >= 4) {
                laneN = ByteArray(en); laneMask = ShortArray(en)
                val ln = b.int
                for (i in 0 until ln) {
                    val e = b.int; val n = b.get(); val m = b.short
                    if (e in 0 until en) { laneN[e] = n; laneMask[e] = m }
                }
            }
            return MapData(
                minX, minZ, maxX, maxZ, nodeX, nodeZ, eFrom, eTo, eFlags, eCls, eGrade, eSpeed, eWidth, eJid,
                ePtStart, ptX, ptZ, eLen, junctionNodes, roundabouts, cities.sortedBy { it.key }, companies, pois, laneN, laneMask,
            )
        }

        private fun readStr(b: ByteBuffer): String {
            val n = b.get().toInt() and 0xFF
            val a = ByteArray(n)
            b.get(a)
            return String(a, Charsets.UTF_8)
        }
    }
}
