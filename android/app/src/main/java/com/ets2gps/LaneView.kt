package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lane guidance for the next junction: one arrow per lane of the road you are on, left to right.
 * Lanes that lead where the route goes are green (the ones to be in), other lanes are white with the
 * direction they take, and a lane that also serves a second direction shows it as a faint extra arrow.
 */
class LaneView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 38, 46, 62) }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * d, 5f * d), 0f)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()
    private var lanes: List<Lane>? = null

    private var caption = ""
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255); textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    /** [l] = one entry per lane, left to right (null hides the panel); [dist] = distance to the junction for the caption. */
    fun setLanes(l: List<Lane>?, dist: String = "") {
        if (dist != caption) { caption = dist; invalidate() }
        if (l === lanes) return
        lanes = l
        visibility = if (l == null) GONE else VISIBLE
        requestLayout()
        invalidate()
    }

    private val cell get() = 50f * d
    private val pad get() = 10f * d

    override fun onMeasure(w: Int, h: Int) {
        val n = lanes?.size ?: 0
        val textW = capPaint.apply { textSize = 14f * d }.measureText(caption.ifEmpty { "00.0 km" }) + 2 * pad
        setMeasuredDimension(maxOf(n * cell + 2 * pad, textW).toInt(), (112f * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val ls = lanes ?: return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, 14f * d, 14f * d, bg)
        if (caption.isNotEmpty()) c.drawText(caption, width / 2f, height - 9f * d, capPaint.apply { textSize = 14f * d })
        val x0 = (width - ls.size * cell) / 2f
        for ((i, lane) in ls.withIndex()) {
            val cx = x0 + cell * (i + 0.5f)
            if (i > 0) c.drawLine(x0 + cell * i, 10f * d, x0 + cell * i, height - 30f * d, divider)
            if (lane.altDeg != null) arrow(c, cx, lane.altDeg, Color.argb(95, 255, 255, 255), false)
            if (lane.angleDeg.isNaN()) continue
            if (lane.preferred) arrow(c, cx, lane.angleDeg, Color.rgb(60, 235, 130), true)
            else arrow(c, cx, lane.angleDeg, Color.argb(235, 245, 247, 250), false)
        }
    }

    /** Arrow rising from the bottom of the lane cell: a stem, then a bend of [deg] (+ = right) ending in a head. */
    private fun arrow(c: Canvas, cx: Float, deg: Float, color: Int, bright: Boolean) {
        val w = 7f * d
        val bottom = height - 34f * d
        val top = 14f * d
        val a = deg.coerceIn(-100f, 100f)
        val th = Math.toRadians(a.toDouble())
        val dx = sin(th).toFloat(); val dy = -cos(th).toFloat()
        val straight = Math.abs(a) < 12f
        val bendY = if (straight) top + 8f * d else (top + bottom) / 2f
        val len = if (straight) 0f else 24f * d
        val ex = cx + dx * len; val ey = bendY + dy * len
        path.reset()
        path.moveTo(cx, bottom)
        path.lineTo(cx, bendY)
        if (!straight) path.lineTo(ex, ey)
        val tipX: Float; val tipY: Float; val fx: Float; val fy: Float
        if (straight) { tipX = cx; tipY = top; fx = 0f; fy = -1f } else { tipX = ex; tipY = ey; fx = dx; fy = dy }
        if (bright) {
            glow.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
            glow.strokeWidth = w + 7f * d
            c.drawPath(path, glow)
        }
        line.color = color; line.strokeWidth = w
        // stop the shaft short of the head so the tip stays sharp
        val hl = 13f * d; val hw = 11f * d
        path.reset()
        path.moveTo(cx, bottom)
        if (straight) path.lineTo(cx, tipY + hl * 0.6f)
        else { path.lineTo(cx, bendY); path.lineTo(ex - fx * hl * 0.5f, ey - fy * hl * 0.5f) }
        c.drawPath(path, line)
        head.color = color
        val bx = tipX - fx * hl; val by = tipY - fy * hl
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(bx - fy * hw, by + fx * hw)
        path.lineTo(bx + fy * hw, by - fx * hw)
        path.close()
        c.drawPath(path, head)
    }
}
