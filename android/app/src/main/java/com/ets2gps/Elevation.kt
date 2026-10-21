package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Altitude samples along the trip (one every ~25 map metres), decimated when it grows large. */
class ElevationLog {
    var dist = FloatArray(2048) // distance travelled, map metres
        private set
    var alt = FloatArray(2048)
        private set
    var size = 0
        private set
    private var step = 25f
    private var lastD = -1e9f

    fun add(distance: Float, y: Float) {
        if (distance - lastD < step) return
        lastD = distance
        if (size == dist.size) {
            if (size >= MAX) { decimate() } else {
                dist = dist.copyOf(size * 2); alt = alt.copyOf(size * 2)
            }
        }
        dist[size] = distance; alt[size] = y; size++
    }

    private fun decimate() {
        var j = 0
        for (i in 0 until size step 2) { dist[j] = dist[i]; alt[j] = alt[i]; j++ }
        size = j
        step *= 2f
    }

    fun clear() { size = 0; lastD = -1e9f; step = 25f }

    fun ascent(): Float { var a = 0f; for (i in 1 until size) { val d = alt[i] - alt[i - 1]; if (d > 0.3f) a += d }; return a }
    fun descent(): Float { var a = 0f; for (i in 1 until size) { val d = alt[i] - alt[i - 1]; if (d < -0.3f) a -= d }; return a }

    companion object { const val MAX = 8192 }
}

/** Elevation profile of the trip so far. The x axis is shown in game kilometres (map metres x [distScale]). */
class ElevationChart(ctx: Context, private val log: ElevationLog, private val distScale: Float) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 190, 255); style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 90, 190, 255) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); textSize = 12f * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 214, 120) }
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (240 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(16, 21, 28))
        val n = log.size
        val l = 46f * d; val r = width - 12f * d; val t = 12f * d; val b = height - 24f * d
        if (n < 2) {
            txt.textAlign = Paint.Align.CENTER
            c.drawText("Drive a little to build the profile", width / 2f, height / 2f, txt)
            return
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until n) { lo = minOf(lo, log.alt[i]); hi = maxOf(hi, log.alt[i]) }
        if (hi - lo < 10f) { val m = (hi + lo) / 2f; lo = m - 5f; hi = m + 5f }
        val d0 = log.dist[0]; val d1 = max(log.dist[n - 1], d0 + 1f)
        fun px(i: Int) = l + (log.dist[i] - d0) / (d1 - d0) * (r - l)
        fun py(i: Int) = b - (log.alt[i] - lo) / (hi - lo) * (b - t)
        txt.textAlign = Paint.Align.RIGHT
        for (k in 0..3) {
            val y = t + (b - t) * k / 3f
            c.drawLine(l, y, r, y, grid)
            c.drawText("%.0f m".format(hi - (hi - lo) * k / 3f), l - 4f * d, y + 4f * d, txt)
        }
        path.reset()
        path.moveTo(px(0), b)
        for (i in 0 until n) path.lineTo(px(i), py(i))
        path.lineTo(px(n - 1), b); path.close()
        c.drawPath(path, fill)
        path.reset()
        for (i in 0 until n) if (i == 0) path.moveTo(px(0), py(0)) else path.lineTo(px(i), py(i))
        c.drawPath(path, line)
        c.drawCircle(px(n - 1), py(n - 1), 5f * d, dot)
        txt.textAlign = Paint.Align.LEFT
        c.drawText("start", l, height - 6f * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        c.drawText("%.0f km%s".format((d1 - d0) * distScale / 1000f, if (distScale == 1f) "" else " (game)") + " travelled", r, height - 6f * d, txt)
    }
}
