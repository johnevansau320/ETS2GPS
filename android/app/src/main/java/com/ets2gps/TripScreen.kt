package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TripRow(val label: String, val value: String, val color: Int = Color.WHITE)

/** [bar] is 0..1 (or null); [barColor] tints it. */
class TripCard(val title: String, val rows: List<TripRow>, val bar: Float? = null, val barColor: Int = Color.rgb(90, 190, 255), val barNote: String = "")

/** A headline card of the dashboard layout: big value, optional small text beside it, optional badge, then rows. */
class DashCard(
    val title: String,
    val big: String,
    val bigColor: Int = Color.WHITE,
    val bigSub: String = "",
    val badge: String = "",
    val badgeColor: Int = Color.rgb(40, 190, 110),
    val rows: List<TripRow> = emptyList(),
)

class DashData(
    val fromCity: String, val fromCompany: String,
    val toCity: String, val toCompany: String,
    val leftText: String, val ofText: String, val progress: Float,
    val local: String, val sim: String, val onJob: Boolean,
    val row1: List<DashCard>,
    val row2: List<TripCard>,
)

/**
 * Second screen. On wide displays (16:9 and wider) it draws a dashboard on a fixed 1000 x 562 (16:9) design canvas that is
 * scaled to fit, so it looks the same on any wide screen; on taller displays it falls back to a scrollable card grid.
 */
class TripScreen(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var cards: List<TripCard> = emptyList()
    private var dash: DashData? = null
    private var scrollY = 0f
    private var contentH = 0f

    var onReset: (() -> Unit)? = null
    private val resetRect = RectF() // in view pixels
    private val resetLabel = "RESET TRIP"

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 13, 19) }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 27, 37) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 52, 68); style = Paint.Style.STROKE }
    private val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 160, 200); isFakeBoldText = true; letterSpacing = 0.06f }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(135, 148, 165) }
    private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 195); textAlign = Paint.Align.RIGHT }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 44, 58) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 12, 18); isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private var downY = 0f
    private var downX = 0f
    private var lastY = 0f
    private var moved = false

    fun setCards(c: List<TripCard>, dashboard: DashData?) {
        cards = c
        dash = dashboard
        invalidate()
    }

    private val wide get() = height > 0 && width.toFloat() / height >= 1.45f

    // ------------------------------------------------------------------------------ drawing --

    override fun onDraw(cv: Canvas) {
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val dd = dash
        if (wide && dd != null) drawDashboard(cv, dd) else drawCardGrid(cv)
    }

    private fun fit(t: String, p: Paint, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        val n = p.breakText(t, true, max(4f, maxW - p.measureText("…")), null)
        return t.substring(0, n.coerceAtLeast(0)).trimEnd() + "…"
    }

    private fun drawChip(cv: Canvas, text: String, right: Float, cy: Float, color: Int, size: Float) {
        chipTxt.textSize = size
        val w = chipTxt.measureText(text) + size * 1.6f
        rect.set(right - w, cy - size * 0.95f, right, cy + size * 0.95f)
        chip.color = color
        cv.drawRoundRect(rect, size, size, chip)
        cv.drawText(text, rect.centerX(), cy + size * 0.35f, chipTxt)
    }

    private fun drawDashboard(cv: Canvas, dd: DashData) {
        val w0 = 1000f
        val h0 = 562f
        val sc = min(width / w0, height / h0)
        val ox = (width - w0 * sc) / 2f
        val oy = 0f
        cv.save()
        cv.translate(ox, oy)
        cv.scale(sc, sc)
        edge.strokeWidth = 1f

        // ---- header strip (y 0..92)
        rect.set(0f, 0f, w0, 92f)
        cv.drawRoundRect(rect, 8f, 8f, cardBg)
        cv.drawRoundRect(rect, 8f, 8f, edge)
        cv.save()
        cv.translate(0f, 5f)
        titleP.textSize = 13f
        cv.drawText("TRIP DETAILS", 14f, 24f, titleP)
        drawChip(cv, if (dd.onJob) "ON JOB" else "NO JOB", 112f, 46f, if (dd.onJob) Color.rgb(40, 190, 230) else Color.rgb(120, 130, 145), 8f)
        // reset chip
        chipTxt.textSize = 7.5f
        val rw = chipTxt.measureText(resetLabel) + 14f
        val rl = 14f; val rt = 58f
        rect.set(rl, rt, rl + rw, rt + 15f)
        chip.color = Color.rgb(36, 92, 160)
        cv.drawRoundRect(rect, 7f, 7f, chip)
        chipTxt.color = Color.WHITE
        cv.drawText(resetLabel, rect.centerX(), rt + 10.5f, chipTxt)
        chipTxt.color = Color.rgb(8, 12, 18)
        resetRect.set(ox + rl * sc, oy + (rt + 5f) * sc, ox + (rl + rw) * sc, oy + (rt + 20f) * sc)

        // FROM
        labelP.textSize = 8f
        cv.drawText("FROM", 150f, 40f, labelP)
        bigP.textSize = 24f
        cv.drawText(fit(dd.fromCity, bigP, 150f), 182f, 40f, bigP)
        labelP.textSize = 11f
        cv.drawText(fit(dd.fromCompany, labelP, 160f), 182f, 60f, labelP)

        // route progress
        titleP.textSize = 8.5f
        cv.drawText("ROUTE PROGRESS", 372f, 17f, titleP)
        rect.set(372f, 24f, 640f, 44f)
        cv.drawRoundRect(rect, 4f, 4f, track)
        fill.color = Color.rgb(40, 190, 230)
        rect.set(372f, 24f, 372f + 268f * dd.progress.coerceIn(0f, 1f), 44f)
        if (dd.progress > 0.005f) cv.drawRoundRect(rect, 4f, 4f, fill)
        bigP.textSize = 12f
        cv.drawText(dd.leftText, 372f, 66f, bigP)
        subP.textSize = 10f
        cv.drawText(dd.ofText, 640f, 66f, subP)

        // TO
        labelP.textSize = 8f
        cv.drawText("TO", 668f, 40f, labelP)
        bigP.textSize = 24f
        cv.drawText(fit(dd.toCity, bigP, 160f), 686f, 40f, bigP)
        labelP.textSize = 11f
        cv.drawText(fit(dd.toCompany, labelP, 170f), 686f, 60f, labelP)

        // clocks
        labelP.textSize = 8f
        cv.drawText("LOCAL", 860f, 26f, labelP)
        cv.drawText("ETS2", 860f, 56f, labelP)
        bigP.textSize = 15f
        bigP.textAlign = Paint.Align.RIGHT
        cv.drawText(dd.local, 988f, 28f, bigP)
        bigP.color = Color.rgb(255, 176, 40)
        cv.drawText(dd.sim, 988f, 58f, bigP)
        bigP.color = Color.WHITE
        bigP.textAlign = Paint.Align.LEFT

        cv.restore() // end of header translate

        // ---- row 1: five headline cards (y 102..332)
        val gap = 8f
        val cw = (w0 - gap * 4) / 5f
        for ((i, c) in dd.row1.take(5).withIndex()) drawDashCard(cv, c, i * (cw + gap), 102f, cw, 230f)

        // ---- row 2: detail cards (y 342..562)
        val n2 = max(1, min(5, dd.row2.size))
        val cw2 = (w0 - gap * (n2 - 1)) / n2
        for ((i, c) in dd.row2.take(5).withIndex()) drawSmallCard(cv, c, i * (cw2 + gap), 342f, cw2, 220f)
        cv.restore()
    }

    private fun drawDashCard(cv: Canvas, c: DashCard, x: Float, y: Float, w: Float, h: Float) {
        rect.set(x, y, x + w, y + h)
        cv.drawRoundRect(rect, 8f, 8f, cardBg)
        cv.drawRoundRect(rect, 8f, 8f, edge)
        titleP.textSize = 8.5f
        cv.drawText(fit(c.title, titleP, w - 20f), x + 12f, y + 18f, titleP)
        if (c.badge.isNotEmpty()) drawChip(cv, c.badge, x + w - 10f, y + 15f, c.badgeColor, 6.5f)
        // big value (shrinks to fit), small text on the right
        var sz = 36f
        bigP.textSize = sz
        val maxBig = w - 24f - (if (c.bigSub.isNotEmpty()) 64f else 0f)
        while (bigP.measureText(c.big) > maxBig && sz > 14f) { sz -= 1f; bigP.textSize = sz }
        bigP.color = c.bigColor
        cv.drawText(c.big, x + 12f, y + 64f, bigP)
        bigP.color = Color.WHITE
        if (c.bigSub.isNotEmpty()) {
            subP.textSize = 10.5f
            cv.drawText(fit(c.bigSub, subP, 80f), x + w - 12f, y + 62f, subP)
        }
        var ry = y + 104f
        for (r in c.rows.take(4)) {
            labelP.textSize = 8f
            cv.drawText(fit(r.label.uppercase(), labelP, w * 0.5f), x + 12f, ry, labelP)
            valueP.textSize = 12f
            valueP.color = r.color
            cv.drawText(fit(r.value, valueP, w - 24f - labelP.measureText(r.label.uppercase()) - 8f), x + w - 12f, ry, valueP)
            ry += 30f
        }
    }

    private fun drawSmallCard(cv: Canvas, c: TripCard, x: Float, y: Float, w: Float, h: Float) {
        rect.set(x, y, x + w, y + h)
        cv.drawRoundRect(rect, 8f, 8f, cardBg)
        cv.drawRoundRect(rect, 8f, 8f, edge)
        titleP.textSize = 8.5f
        cv.drawText(fit(c.title, titleP, w - 20f), x + 12f, y + 17f, titleP)
        val rows = c.rows.take(7)
        val step = min(27f, (h - 40f) / max(1, rows.size))
        var ry = y + 42f
        for (r in rows) {
            labelP.textSize = 8f
            val lab = r.label.uppercase()
            cv.drawText(fit(lab, labelP, w * 0.5f), x + 12f, ry, labelP)
            valueP.textSize = 11f
            valueP.color = r.color
            cv.drawText(fit(r.value, valueP, w - 24f - labelP.measureText(lab) - 8f), x + w - 12f, ry, valueP)
            ry += step
        }
    }

    // ---- fallback for tall screens: scrollable grid
    private fun drawCardGrid(cv: Canvas) {
        val pad = 12f * d
        val rowH = 25f * d
        val head = 34f * d
        val wdp = width / d
        val cols = when { wdp >= 900f -> 3; wdp >= 560f -> 2; else -> 1 }
        val cw = (width - pad * (cols + 1)) / cols
        val colY = FloatArray(cols) { 44f * d + pad }
        val save = cv.save()
        cv.clipRect(0f, 40f * d, width.toFloat(), height.toFloat())
        cv.translate(0f, -scrollY)
        titleP.textSize = 13f * d
        for (c in cards) {
            var k = 0
            for (i in 1 until cols) if (colY[i] < colY[k]) k = i
            val x = pad + k * (cw + pad)
            val y = colY[k]
            val h = head + c.rows.size * rowH + (if (c.bar != null) 24f * d else 0f) + 10f * d
            rect.set(x, y, x + cw, y + h)
            cv.drawRoundRect(rect, 12f * d, 12f * d, cardBg)
            cv.drawText(c.title, x + 14f * d, y + 22f * d, titleP)
            var ry = y + head + 14f * d
            labelP.textSize = 14f * d
            valueP.textSize = 15f * d
            for (r in c.rows) {
                val lab = fit(r.label, labelP, cw * 0.42f)
                cv.drawText(lab, x + 14f * d, ry, labelP)
                valueP.color = r.color
                cv.drawText(fit(r.value, valueP, cw - 28f * d - labelP.measureText(lab) - 10f * d), x + cw - 14f * d, ry, valueP)
                ry += rowH
            }
            c.bar?.let { f ->
                val bx = x + 14f * d; val bw = cw - 28f * d; val by = ry - 6f * d
                rect.set(bx, by, bx + bw, by + 10f * d)
                cv.drawRoundRect(rect, 5f * d, 5f * d, track)
                fill.color = c.barColor
                rect.set(bx, by, bx + bw * f.coerceIn(0f, 1f), by + 10f * d)
                cv.drawRoundRect(rect, 5f * d, 5f * d, fill)
            }
            colY[k] = y + h + pad
        }
        contentH = (colY.maxOrNull() ?: 0f) + pad
        cv.restoreToCount(save)
        cv.drawRect(0f, 0f, width.toFloat(), 40f * d, bg)
        titleP.textSize = 15f * d
        cv.drawText("TRIP / JOB DETAILS", pad, 27f * d, titleP)
        chipTxt.textSize = 14f * d
        val bw = chipTxt.measureText(resetLabel) + 28f * d
        resetRect.set(width - pad - bw, 5f * d, width - pad, 35f * d)
        chip.color = Color.rgb(36, 92, 160)
        cv.drawRoundRect(resetRect, 8f * d, 8f * d, chip)
        chipTxt.color = Color.WHITE
        cv.drawText(resetLabel, resetRect.centerX(), resetRect.centerY() + 5f * d, chipTxt)
        chipTxt.color = Color.rgb(8, 12, 18)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - downY) > 8f * d) moved = true
                if (moved && !(wide && dash != null)) {
                    val maxScroll = max(0f, contentH - height)
                    scrollY = (scrollY - (e.y - lastY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val pad = 10f * d
                val hit = RectF(resetRect.left - pad, resetRect.top - pad, resetRect.right + pad, resetRect.bottom + pad)
                if (!moved && hit.contains(downX, downY)) onReset?.invoke()
            }
        }
        return true
    }

    companion object {
        fun pctColor(p: Float): Int = when {
            p >= 15f -> Color.rgb(255, 120, 110)
            p >= 5f -> Color.rgb(255, 190, 90)
            else -> Color.WHITE
        }
    }
}
