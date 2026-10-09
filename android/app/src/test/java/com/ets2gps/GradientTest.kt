package com.ets2gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientTest {
    private fun state(x: Float, z: Float, y: Float) = TruckState(
        connected = true, paused = false, x = x, z = z, y = y, reportedHeadingDeg = 0f, speedKmh = 80f,
        speedLimitKmh = 80, destCityId = "", destCity = "", destCompanyId = "", destCompany = "", hasTrailer = true,
        fuelFraction = 0.5f, fuelWarning = false, nextRestMin = -1f, job = null, navEtaMin = -1f, gameTimeMin = -1f,
    )

    @Test
    fun measuresGradientFromHeightOverDistance() {
        val m = MotionFilter()
        var t = 0L
        // drive north (decreasing z) at 20 m/s, climbing 5 %
        for (i in 0..60) {
            m.update(state(0f, -i * 4f, i * 4f * 0.05f), t); t += 200
        }
        assertTrue(m.gradientValid)
        assertEquals(5.0, m.gradientPct.toDouble(), 0.3)
        // then descend 8 %
        val z0 = -240f; val y0 = 12f
        for (i in 1..80) {
            m.update(state(0f, z0 - i * 4f, y0 - i * 4f * 0.08f), t); t += 200
        }
        assertEquals(-8.0, m.gradientPct.toDouble(), 0.5)
        println("GRADIENT ok: travelled=${m.travelled} m, altitude=${m.altitude}")
    }

    @Test
    fun elevationLogDecimatesAndTotals() {
        val log = ElevationLog()
        for (i in 0 until 40000) log.add(i * 30f, (i % 200).toFloat())
        assertTrue(log.size <= ElevationLog.MAX)
        assertTrue(log.ascent() > 0f && log.descent() > 0f)
    }
}
