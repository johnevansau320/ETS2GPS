package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Rotating compass rose: N points to true north on the heading-up map; the centre shows the truck's heading. */
class CompassView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 14, 18, 24) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val dirText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private var headingRad = 0f
    private var valid = false

    fun setHeading(bearing: Float, valid: Boolean) {
        headingRad = bearing
        this.valid = valid
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (100 * d).toInt() // 1.5x the old 62 dp dial plus room for the pointer mark
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 46.5f * d
        c.drawCircle(cx, cy, r, fill)
        ring.strokeWidth = 3f * d
        c.drawCircle(cx, cy, r, ring)
        val deg = Math.toDegrees(headingRad.toDouble()).toFloat()
        c.save()
        c.rotate(-deg, cx, cy)
        tick.textSize = r * 0.34f
        val off = r * 0.72f
        val base = tick.textSize * 0.35f
        tick.color = Color.rgb(255, 120, 120)
        c.drawText("N", cx, cy - off + base, tick)
        tick.color = Color.argb(210, 255, 255, 255)
        c.drawText("S", cx, cy + off + base, tick)
        c.drawText("E", cx + off, cy + base, tick)
        c.drawText("W", cx - off, cy + base, tick)
        c.restore()
        if (valid) {
            val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val idx = (((deg % 360f) + 360f + 22.5f) / 45f).toInt() % 8
            dirText.textSize = r * 0.42f
            c.drawText(names[idx], cx, cy + dirText.textSize * 0.36f, dirText)
        }
        // pointer mark: the way the truck is facing
        path.reset()
        path.moveTo(cx, cy - r - 4 * d); path.lineTo(cx + 6 * d, cy - r + 6 * d); path.lineTo(cx - 6 * d, cy - r + 6 * d); path.close()
        c.drawPath(path, mark)
    }
}
