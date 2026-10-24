package com.ets2gps

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.hypot

class JobInfo(
    val srcCity: String, val srcCompany: String,
    val destCity: String, val destCompany: String,
    val cargo: String, val massKg: Float, val income: Long,
    val dueMin: Float, // game minutes left until the deadline, -1 unknown
    val plannedKm: Int,
    val units: Int = 1,
)

/** Extra fields for the trip / job screen. Damage values are percent. */
class Detail(
    val truckName: String,
    val odometerKm: Float,
    val fuelL: Float, val fuelCapL: Float,
    val consumptionLPerKm: Float, val rangeKm: Float,
    val trailerAttached: Boolean,
    val trailerName: String, val trailerPlate: String,
    val trailerChassis: Float, val trailerWheels: Float, val trailerBody: Float, val cargoDamage: Float,
    val navDistanceKm: Float, // the game's own remaining distance to its navigation target, game km
    val jobMarket: String,
)

class TruckState(
    val connected: Boolean,
    val paused: Boolean,
    val x: Float,
    val z: Float,
    val y: Float, // altitude in map metres
    val reportedHeadingDeg: Float, // as sent by the plugin (see TelemetryClient for the convention)
    val speedKmh: Float,
    val speedLimitKmh: Int,
    val destCityId: String,
    val destCity: String,
    val destCompanyId: String,
    val destCompany: String,
    val hasTrailer: Boolean,
    val fuelFraction: Float, // 0..1, -1 unknown
    val fuelWarning: Boolean,
    val nextRestMin: Float, // game minutes until a rest is needed, -1 unknown
    val job: JobInfo?,
    val navEtaMin: Float, // the game's own ETA to its navigation target in game minutes, -1 unknown
    val gameTimeMin: Float, // in-game clock as minutes since day 1 00:00 (day 1 is a Monday), -1 unknown
    val detail: Detail? = null,
    val onJob: Boolean = false, val jobDelivered: Boolean = false, val jobCancelled: Boolean = false, val jobFinished: Boolean = false, // the server's gameplay section
)

/**
 * Polls the TruckSim GPS Telemetry Server (http://<pc>:31377/api/ets2/telemetry).
 * Listener callbacks run on the main thread.
 */
class TelemetryClient(
    private val hostProvider: () -> String,
    private val onState: (TruckState) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val thread = HandlerThread("telemetry").also { it.start() }
    private val bg = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var failures = 0

    fun start() {
        if (running) return
        running = true
        bg.post(poll)
    }

    fun stop() {
        running = false
        bg.removeCallbacks(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            var delay = 120L
            try {
                val host = hostProvider().trim()
                if (host.isEmpty()) {
                    ui.post { onStatus("Set the PC address in settings") }
                    delay = 1000L
                } else {
                    val st = fetch(host)
                    failures = 0
                    ui.post { onState(st) }
                }
            } catch (e: Exception) {
                failures++
                ui.post { onStatus("No telemetry server (${e.javaClass.simpleName}) - retrying") }
                delay = if (failures < 5) 700L else 2000L
            }
            if (running) bg.postDelayed(this, delay)
        }
    }

    /** org.json's optString returns the text "null" for JSON null; treat that as empty. */
    private fun str(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else fixText(o.optString(key, "").trim())



    /** "0001-01-01T10:11:00" (a duration encoded as a date) -> minutes. */
    private fun restMinutes(t: String): Float {
        val m = Regex("^\\d{4}-\\d{2}-(\\d{2})T(\\d{2}):(\\d{2})").find(t) ?: return -1f
        val (d, h, mi) = m.destructured
        return ((d.toInt() - 1) * 1440 + h.toInt() * 60 + mi.toInt()).toFloat()
    }

    /** "0001-05-05T23:49:00Z" -> minutes since 0001-01-01 00:00 (a Monday). */
    private fun clockMinutes(t: String): Float {
        val m = Regex("^\\d{4}-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(t) ?: return -1f
        val (mo, d, h, mi) = m.destructured
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = cum[(mo.toInt() - 1).coerceIn(0, 11)] + d.toInt() - 1
        return (doy * 1440 + h.toInt() * 60 + mi.toInt()).toFloat()
    }

    private fun fetch(host: String): TruckState {
        val h = if (host.contains(":")) host else "$host:31377"
        val c = URL("http://$h/api/ets2/telemetry").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        try {
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            val game = j.optJSONObject("game")
            val truck = j.optJSONObject("truck")
            val pl = truck?.optJSONObject("placement")
            val nav = j.optJSONObject("navigation")
            val job = j.optJSONObject("job")
            val trailers = j.optJSONArray("trailers")
            val gp = j.optJSONObject("gameplay")
            val attached = trailers != null && trailers.length() > 0 &&
                trailers.optJSONObject(0)?.optBoolean("attached", false) == true
            return TruckState(
                connected = game?.optBoolean("connected", false) ?: false,
                paused = game?.optBoolean("paused", false) ?: false,
                x = (pl?.optDouble("x", 0.0) ?: 0.0).toFloat(),
                z = (pl?.optDouble("z", 0.0) ?: 0.0).toFloat(),
                y = (pl?.optDouble("y", 0.0) ?: 0.0).toFloat(),
                reportedHeadingDeg = (pl?.optDouble("heading", 0.0) ?: 0.0).toFloat(),
                speedKmh = ((truck?.optDouble("speed", 0.0) ?: 0.0).toFloat()) * 1f,
                speedLimitKmh = nav?.optInt("speedLimit", 0) ?: 0,
                destCityId = str(job, "destinationCityId"),
                destCity = str(job, "destinationCity"),
                destCompanyId = str(job, "destinationCompanyId"),
                destCompany = str(job, "destinationCompany"),
                hasTrailer = attached,
                fuelFraction = fuelFrac(truck),
                fuelWarning = truck?.optBoolean("fuelWarningOn", false) ?: false,
                nextRestMin = restMinutes(str(game, "nextRestStopTime")),
                job = if (str(job, "destinationCityId").isEmpty()) null else JobInfo(
                    srcCity = str(job, "sourceCity"), srcCompany = str(job, "sourceCompany"),
                    destCity = str(job, "destinationCity"), destCompany = str(job, "destinationCompany"),
                    cargo = str(job, "cargo"), massKg = (job?.optDouble("cargoMass", 0.0) ?: 0.0).toFloat(),
                    income = job?.optLong("income", 0L) ?: 0L,
                    dueMin = restMinutes(str(job, "remainingTime")),
                    plannedKm = job?.optInt("plannedDistanceKm", 0) ?: 0,
                    units = job?.optInt("unitCount", 1) ?: 1,
                ),
                navEtaMin = restMinutes(nav?.optString("estimatedTime", "") ?: ""),
                gameTimeMin = clockMinutes(str(game, "time")),
                onJob = gp?.optBoolean("onJob", false) ?: false, jobDelivered = gp?.optBoolean("jobDelivered", false) ?: false,
                jobCancelled = gp?.optBoolean("jobCancelled", false) ?: false, jobFinished = gp?.optBoolean("jobFinished", false) ?: false,
                detail = run {
                    val tr = trailers?.optJSONObject(0)
                    Detail(
                        truckName = (str(truck, "make") + " " + str(truck, "model")).trim(),
                        odometerKm = (truck?.optDouble("odometer", 0.0) ?: 0.0).toFloat(),
                        fuelL = (truck?.optDouble("fuel", 0.0) ?: 0.0).toFloat(),
                        fuelCapL = (truck?.optDouble("fuelCapacity", 0.0) ?: 0.0).toFloat(),
                        consumptionLPerKm = (truck?.optDouble("fuelAverageConsumption", 0.0) ?: 0.0).toFloat(),
                        rangeKm = (truck?.optDouble("fuelRange", 0.0) ?: 0.0).toFloat(),
                        trailerAttached = attached,
                        trailerName = if (attached) (str(tr, "brand") + " " + str(tr, "name")).trim() else "",
                        trailerPlate = if (attached) str(tr, "licensePlate") else "",
                        trailerChassis = if (attached) ((tr?.optDouble("wearChassis", 0.0) ?: 0.0) * 100).toFloat() else 0f,
                        trailerWheels = if (attached) ((tr?.optDouble("wearWheels", 0.0) ?: 0.0) * 100).toFloat() else 0f,
                        trailerBody = if (attached) ((tr?.optDouble("wearBody", 0.0) ?: 0.0) * 100).toFloat() else 0f,
                        cargoDamage = if (attached) ((tr?.optDouble("cargoDamage", 0.0) ?: 0.0) * 100).toFloat() else 0f,
                        navDistanceKm = ((nav?.optDouble("estimatedDistance", 0.0) ?: 0.0) / 1000.0).toFloat(),
                        jobMarket = str(job, "jobMarket"),
                    )
                },
            )
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Turns the noisy position/heading stream into a steady position, heading and speed (world metres / s).
 * Heading is taken from the direction of travel when moving (this is independent of the plugin's angle
 * convention) and from the reported heading when stationary.
 */
class MotionFilter {
    var x = 0f; var z = 0f
    var bearing = 0f // radians, clockwise from north
    var speedMps = 0f
    private var lastT = 0L
    private var vx = 0f; private var vz = 0f
    private var haveFix = false
    private var maxReported = 0f

    // altitude / gradient, derived from height change over distance driven (independent of the pitch units)
    var altitude = 0f
    var travelled = 0f // map metres driven since the app started
    var gradientPct = 0f
    var gradientValid = false
    private val gd = FloatArray(96)
    private val gy = FloatArray(96)
    private var gCount = 0
    private var gHead = 0
    private var lastPush = -1e9f

    private fun pushAlt(y: Float) {
        altitude = y
        if (travelled - lastPush >= 5f) {
            lastPush = travelled
            gd[gHead] = travelled; gy[gHead] = y
            gHead = (gHead + 1) % gd.size
            if (gCount < gd.size) gCount++
        }
        // newest sample that is at least 60 m behind us
        var i = 0
        while (i < gCount) {
            val idx = (gHead - 1 - i + gd.size * 2) % gd.size
            val dd = travelled - gd[idx]
            if (dd >= 60f) {
                val g = (y - gy[idx]) / dd * 100f
                gradientPct += ((g - gradientPct) * (if (gradientValid) 0.3f else 1f))
                gradientValid = true
                return
            }
            i++
        }
    }

    /** world metres per second -> used for dead reckoning between samples */
    fun update(s: TruckState, nowMs: Long) {
        if (!s.connected) return
        if (!haveFix || hypot(s.x - x, s.z - z) > 400f) { // first fix or teleport
            x = s.x; z = s.z; haveFix = true; lastT = nowMs; vx = 0f; vz = 0f
            gCount = 0; gHead = 0; gradientValid = false; lastPush = -1e9f; altitude = s.y
            bearing = reportedBearing(s.reportedHeadingDeg)
            return
        }
        val dt = (nowMs - lastT) / 1000f
        val dx = s.x - x
        val dz = s.z - z
        val moved = hypot(dx, dz)
        if (moved < 0.01f) { // duplicate sample (server updates slower than we poll) or standing still
            if (dt > 0.8f) {
                vx = 0f; vz = 0f; speedMps = 0f
                bearing = blend(bearing, reportedBearing(s.reportedHeadingDeg), 0.1f)
                lastT = nowMs
            }
            return
        }
        if (dt < 0.02f) return
        vx += (dx / dt - vx) * 0.5f
        vz += (dz / dt - vz) * 0.5f
        speedMps = hypot(vx, vz)
        if (moved > 0.4f && moved / dt > 1.5f) bearing = blend(bearing, atan2(dx, -dz), 0.35f)
        x = s.x; z = s.z; lastT = nowMs
        travelled += moved
        pushAlt(s.y)
    }

    /** Extrapolated position for smooth rendering. */
    fun predicted(nowMs: Long): Pair<Float, Float> {
        val dt = ((nowMs - lastT) / 1000f).coerceIn(0f, 0.4f)
        return Pair(x + vx * dt, z + vz * dt)
    }

    private fun reportedBearing(h: Float): Float {
        if (h > maxReported) maxReported = h
        val deg = if (maxReported <= 1.0001f) h * 360f else h
        // SCS heading: 0 = north, increasing counter-clockwise
        return Math.toRadians(((360.0 - deg) % 360.0)).toFloat()
    }

    private fun blend(a: Float, b: Float, k: Float): Float = a + Router.angleDiff(b, a) * k
}

/**
 * The server sometimes sends UTF-8 text that was decoded as Windows-1252 (Russian names show as "Ð¦Ðµ...").
 * Undo that when the result is clean UTF-8.
 */
internal fun fixText(s: String): String {
    if (s.none { it.code in 0xC2..0xF4 || it.code in 0xDC80..0xDCFF }) return s
    return try {
        val cp = java.nio.charset.Charset.forName("windows-1252")
        val out = java.io.ByteArrayOutputStream()
        for (ch in s) {
            val c = ch.code
            when {
                c in 0xDC80..0xDCFF -> out.write(c - 0xDC00) // "surrogateescape" of an undefined byte
                c < 0x100 -> out.write(c)
                else -> {
                    val b = ch.toString().toByteArray(cp)
                    if (b.size != 1 || b[0] == '?'.code.toByte()) return s
                    out.write(b[0].toInt() and 0xFF)
                }
            }
        }
        val dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        if (dec.length < s.length) dec else s
    } catch (e: Exception) {
        s
    }
}

private fun fuelFrac(truck: JSONObject?): Float {
    val cap = truck?.optDouble("fuelCapacity", 0.0) ?: 0.0
    return if (cap > 0) ((truck?.optDouble("fuel", 0.0) ?: 0.0) / cap).toFloat() else -1f
}
