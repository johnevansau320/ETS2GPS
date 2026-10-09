package com.ets2gps

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Vector maneuver pictograms (turn arrows, roundabout, U-turn, arrival) drawn on a Canvas. */
object Glyphs {
    private val path = Path()
    private val rect = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun arrowHead(c: Canvas, tx: Float, ty: Float, dx: Float, dy: Float, s: Float, fill: Paint) {
        val h = s * 0.26f
        val w = s * 0.2f
        val bx = tx - dx * h
        val by = ty - dy * h
        path.reset()
        path.moveTo(tx + dx * h * 0.35f, ty + dy * h * 0.35f)
        path.lineTo(bx - dy * w, by + dx * w)
        path.lineTo(bx + dy * w, by - dx * w)
        path.close()
        c.drawPath(path, fill)
    }

    /**
     * Draws [turn] centred at (cx, cy) inside a square of side [size].
     * [angleDeg] is only used for the roundabout exit direction.
     */
    fun draw(c: Canvas, turn: Turn, exitNo: Int, angleDeg: Float, cx: Float, cy: Float, size: Float, color: Int) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = size * 0.13f
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }
        val dim = Paint(stroke).apply { alpha = 90 }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val s = size
        when (turn) {
            Turn.ARRIVE -> {
                stroke.strokeWidth = s * 0.1f
                c.drawCircle(cx, cy - s * 0.08f, s * 0.26f, stroke)
                c.drawCircle(cx, cy - s * 0.08f, s * 0.09f, fill)
                c.drawLine(cx, cy + s * 0.18f, cx, cy + s * 0.42f, stroke)
            }
            Turn.UTURN -> {
                val right = angleDeg > 0
                c.save()
                if (right) c.scale(-1f, 1f, cx, cy)
                path.reset()
                path.moveTo(cx + s * 0.16f, cy + s * 0.42f)
                path.lineTo(cx + s * 0.16f, cy - s * 0.14f)
                rect.set(cx - s * 0.16f, cy - s * 0.46f, cx + s * 0.16f, cy - s * 0.14f)
                path.arcTo(rect, 0f, -180f, false)
                path.lineTo(cx - s * 0.16f, cy + s * 0.06f)
                c.drawPath(path, stroke)
                arrowHead(c, cx - s * 0.16f, cy + s * 0.10f, 0f, 1f, s, fill)
                c.restore()
            }
            Turn.ROUNDABOUT -> {
                val ccx = cx
                val ccy = cy - s * 0.06f
                val r = s * 0.2f
                stroke.strokeWidth = s * 0.1f
                dim.strokeWidth = s * 0.1f
                c.drawCircle(ccx, ccy, r, dim)
                // entry
                c.drawLine(cx, cy + s * 0.44f, cx, ccy + r, stroke)
                // exit direction (0 = straight ahead, + = right)
                val th = Math.toRadians(angleDeg.toDouble())
                val dx = sin(th).toFloat()
                val dy = -cos(th).toFloat()
                val sx = ccx + dx * r
                val sy = ccy + dy * r
                val ex = ccx + dx * (r + s * 0.26f)
                val ey = ccy + dy * (r + s * 0.26f)
                c.drawLine(sx, sy, ex, ey, stroke)
                arrowHead(c, ex, ey, dx, dy, s * 0.9f, fill)
                if (exitNo > 0) {
                    text.color = color
                    text.textSize = s * 0.24f
                    text.isFakeBoldText = true
                    c.drawText(exitNo.toString(), ccx, ccy + s * 0.085f, text)
                }
            }
            else -> {
                val signed = when (turn) {
                    Turn.KEEP_LEFT -> -22.0
                    Turn.KEEP_RIGHT -> 22.0
                    Turn.BEAR_LEFT -> -48.0
                    Turn.BEAR_RIGHT -> 48.0
                    Turn.TURN_LEFT -> -90.0
                    else -> 90.0
                }
                val th = Math.toRadians(signed)
                val jx = cx
                val jy = cy + s * 0.06f
                // the road that continues straight (dim) and the stem we arrive on
                c.drawLine(jx, jy, jx, cy - s * 0.4f, dim)
                c.drawLine(jx, cy + s * 0.44f, jx, jy, stroke)
                val dx = sin(th).toFloat()
                val dy = -cos(th).toFloat()
                val len = s * 0.34f
                val ex = jx + dx * len
                val ey = jy + dy * len
                path.reset()
                path.moveTo(jx, jy)
                path.lineTo(ex, ey)
                c.drawPath(path, stroke)
                arrowHead(c, ex, ey, dx, dy, s, fill)
            }
        }
    }
}
