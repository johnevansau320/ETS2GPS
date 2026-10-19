package com.ets2gps

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class TripPt(val x: Float, val z: Float, val name: String)

/** One trip as written to / read from a trip file. [mode] is HEAVY, FASTEST or TOURIST. */
class TripData(val name: String, val mode: String, val avoidFerries: Boolean, val start: TripPt?, val stops: List<TripPt>, val dest: TripPt, val lengthM: Float)

/**
 * The trip file (*.ets2trip, plain JSON) that the Windows app, the web version and this app all read and write, so a trip can be
 * shared. Trips are map positions, so they fit the same map they were made on. A file is data: everything in it is validated.
 */
object TripFile {
    const val FORMAT = "ets2gps-trip"
    const val VERSION = 1
    const val MAX_TRIPS = 200
    const val MAX_STOPS = 50
    const val MAX_CHARS = 1_000_000
    const val MAX_NAME = 80
    private val MODES = listOf("HEAVY", "FASTEST", "TOURIST")

    class Result(val trips: List<TripData>, val skipped: Int, val error: String?)

    private fun pt(p: TripPt?): Any = if (p == null) JSONObject.NULL else JSONObject()
        .put("x", Math.round(p.x * 10.0) / 10.0).put("z", Math.round(p.z * 10.0) / 10.0).put("name", p.name)

    fun toJson(trips: List<TripData>): String {
        val arr = JSONArray()
        for (t in trips) arr.put(JSONObject().put("name", t.name).put("mode", t.mode).put("avoidFerries", t.avoidFerries)
            .put("start", pt(t.start)).put("stops", JSONArray().also { a -> t.stops.forEach { a.put(pt(it)) } })
            .put("dest", pt(t.dest)).put("lengthM", Math.round(t.lengthM.toDouble())))
        return JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("note", "ETS2 GPS trip file. Positions are map coordinates: they fit the same map (game and ProMods versions) they were made on.")
            .put("trips", arr).toString(2)
    }

    private fun clean(s: String?, fallback: String): String {
        var t = (s ?: "").filter { !it.isISOControl() }.trim()
        if (t.isEmpty()) t = fallback
        return if (t.length > MAX_NAME) t.substring(0, MAX_NAME) else t
    }

    private fun point(o: JSONObject?, fallback: String): TripPt? {
        if (o == null) return null
        val x = o.opt("x"); val z = o.opt("z")
        if (x !is Number || z !is Number) return null
        val dx = x.toDouble(); val dz = z.toDouble()
        if (dx.isNaN() || dz.isNaN() || dx.isInfinite() || dz.isInfinite() || Math.abs(dx) > 1e7 || Math.abs(dz) > 1e7) return null
        return TripPt(dx.toFloat(), dz.toFloat(), clean(o.opt("name") as? String, fallback))
    }

    /** Reads a trip file. Trips that are not valid are skipped and counted; a file that is not a trip file gives an error. */
    fun parse(text: String): Result {
        fun fail(e: String) = Result(emptyList(), 0, e)
        if (text.length > MAX_CHARS) return fail("That file is too big to be a trip file")
        val root = try { JSONObject(text) } catch (_: JSONException) { return fail("This is not an ETS2 GPS trip file") }
        if (root.optString("format") != FORMAT) return fail("This is not an ETS2 GPS trip file")
        if (root.optInt("version", 1) > VERSION) return fail("This trip file is from a newer version of ETS2 GPS - update the app")
        val arr = root.optJSONArray("trips") ?: return fail("The file contains no trips")
        val out = ArrayList<TripData>(); var skipped = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i)
            val dest = if (e == null) null else point(e.optJSONObject("dest"), "Destination")
            if (e == null || dest == null || out.size >= MAX_TRIPS) { skipped++; continue }
            val stops = ArrayList<TripPt>(); var bad = false
            val sa = e.optJSONArray("stops")
            if (sa != null) for (k in 0 until sa.length()) {
                val p = point(sa.optJSONObject(k), "Stop ${stops.size + 1}")
                if (p == null || stops.size >= MAX_STOPS) { bad = true; break }
                stops.add(p)
            }
            if (bad) { skipped++; continue }
            val start = e.optJSONObject("start")?.let { point(it, "Start") }
            var mode = e.optString("mode", "HEAVY").uppercase()
            if (mode !in MODES) mode = "HEAVY"
            val len = e.optDouble("lengthM", 0.0).let { if (it.isNaN() || it.isInfinite()) 0.0 else it.coerceIn(0.0, 1e8) }
            val name = clean(e.optString("name", ""), if (start != null) "${start.name} \u2192 ${dest.name}" else "Imported trip")
            out.add(TripData(name, mode, e.optBoolean("avoidFerries", false), start, stops, dest, len.toFloat()))
        }
        return Result(out, skipped, null)
    }
}
