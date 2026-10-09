package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Top banner: pictogram of the next maneuver, distance to it, what to do, and the one after. */
class TurnBanner(ctx: Context) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 18, 92, 58) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val thenBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    private var next: Maneuver? = null
    private var then: Maneuver? = null
    private var dist = ""
    private var what = ""
    private var message: String? = null
    private var job: JobInfo? = null
    private var etaMin = -1f
    private val jobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.RIGHT }
    private val jobPaintBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }

    /** Current job and our route ETA in game minutes (-1 when there is no route). */
    fun setJob(job: JobInfo?, etaGameMin: Float) {
        this.job = job
        etaMin = etaGameMin
        invalidate()
    }

    fun setManeuver(next: Maneuver?, dist: String, what: String, then: Maneuver?) {
        this.next = next; this.dist = dist; this.what = what; this.then = then; message = null
        invalidate()
    }

    fun setMessage(text: String?) {
        message = text
        next = null; then = null
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (96 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(8 * d, 6 * d, w - 8 * d, h - 6 * d)
        c.drawRoundRect(rect, 14 * d, 14 * d, bg)
        val msg = message
        if (msg != null) {
            mid.textSize = 17 * d
            c.drawText(msg, 20 * d, h / 2 + 6 * d, mid)
            drawJob(c, 20 * d + mid.measureText(msg) + 24 * d, w - 20 * d, h)
            return
        }
        val n = next
        if (n == null) {
            mid.textSize = 17 * d
            val idle = "No route - tap Search, Job or long-press the map"
            c.drawText(idle, 20 * d, h / 2 + 6 * d, mid)
            drawJob(c, 20 * d + mid.measureText(idle) + 24 * d, w - 20 * d, h)
            return
        }
        val box = h - 20 * d
        Glyphs.draw(c, n.turn, n.exitNo, n.angleDeg, 18 * d + box / 2, h / 2, box, Color.WHITE)
        val tx = 18 * d + box + 14 * d
        big.textSize = 34 * d
        c.drawText(dist, tx, h / 2 + 2 * d, big)
        mid.textSize = 17 * d
        c.drawText(what, tx, h / 2 + 26 * d, mid)
        val t = then
        val hasThen = t != null && t.turn != Turn.ARRIVE
        mid.textSize = 17 * d
        big.textSize = 34 * d
        val textEnd = tx + maxOf(big.measureText(dist), mid.measureText(what)) + 24 * d
        val thenLeft = w - 20 * d - 52 * d - 4 * d - 14 * d
        drawJob(c, textEnd, if (hasThen) thenLeft else w - 20 * d, h)
        if (t != null && t.turn != Turn.ARRIVE) {
            val bs = 52 * d
            val cx = w - 20 * d - bs / 2
            rect.set(cx - bs / 2 - 4 * d, h / 2 - bs / 2 - 4 * d, cx + bs / 2 + 4 * d, h / 2 + bs / 2 + 4 * d)
            c.drawRoundRect(rect, 10 * d, 10 * d, thenBg)
            Glyphs.draw(c, t.turn, t.exitNo, t.angleDeg, cx, h / 2, bs, Color.WHITE)
            small.textSize = 11 * d
            small.textAlign = Paint.Align.CENTER
            c.drawText("then", cx, h / 2 - bs / 2 - 8 * d, small)
        }
    }

    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        val n = p.breakText(text, true, maxW - p.measureText("\u2026"), null)
        return text.substring(0, n.coerceAtLeast(0)).trimEnd() + "\u2026"
    }

    private fun place(company: String, city: String) = if (company.isNotEmpty()) "$company, $city" else city

    private fun dur(min: Float): String {
        val m = Math.round(min)
        return when {
            m >= 1440 -> "${m / 1440}d ${(m % 1440) / 60}h ${"%02d".format(m % 60)}m"
            m >= 60 -> "${m / 60}h ${"%02d".format(m % 60)}m"
            else -> "${m}m"
        }
    }

    /** Trip / job summary, right-aligned in the free space between the maneuver text and the right edge. */
    private fun drawJob(c: Canvas, left: Float, right: Float, h: Float) {
        val j = job ?: return
        val avail = right - left
        if (avail < 170 * d) return
        jobPaintBold.textSize = 15 * d
        jobPaint.textSize = 14 * d
        val line1 = fit(place(j.srcCompany, j.srcCity) + "  \u2192  " + place(j.destCompany, j.destCity), jobPaintBold, avail)
        c.drawText(line1, right, h / 2 - 5 * d, jobPaintBold)
        val parts = ArrayList<String>()
        if (j.cargo.isNotEmpty()) parts.add(j.cargo + if (j.massKg > 0) String.format(java.util.Locale.US, " %.1f t", j.massKg / 1000f) else "")
        if (j.income > 0) parts.add("\u20ac" + String.format(java.util.Locale.US, "%,d", j.income))
        var due = ""
        var late = false
        if (j.dueMin >= 0) {
            due = "Due " + dur(j.dueMin)
            if (etaMin >= 0) { due += " (ETA " + dur(etaMin) + ")"; late = etaMin > j.dueMin }
        }
        val tail = if (due.isEmpty()) emptyList() else listOf(due)
        var p = parts.size
        var text = (parts + tail).joinToString("  \u00b7  ")
        while (jobPaint.measureText(text) > avail && p > 0) {
            p--
            text = (parts.take(p) + tail).joinToString("  \u00b7  ")
        }
        jobPaint.color = if (late) Color.rgb(255, 160, 150) else Color.argb(235, 255, 255, 255)
        c.drawText(fit(text, jobPaint, avail), right, h / 2 + 17 * d, jobPaint)
    }
}
