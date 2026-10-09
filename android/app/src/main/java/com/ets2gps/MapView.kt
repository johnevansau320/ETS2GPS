package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Heading-up road map. Draws the loaded network around the truck, the active route and the next
 * maneuver pictogram. Pinch to zoom, long-press to pick a destination.
 */
class MapView(ctx: Context) : View(ctx) {
    var map: MapData? = null
    var truckX = 0f
    var truckZ = 0f
    var bearing = 0f // radians, clockwise from north
    var speedMps = 0f
    var hasFix = false

    var routePts = FloatArray(0) // x,z pairs
    var routeEdgePt = IntArray(0) // point index (pair index) where each route edge starts
    var routeIdx = 0
    var nextManeuver: Maneuver? = null
    var onLongPress: ((Float, Float) -> Unit)? = null

    private var userZoom = 1f

    // free-look: one-finger drag detaches the view from the truck until recentre() is called
    var free = false
        private set
    private var freeCx = 0f
    private var freeCz = 0f
    private var freeBearing = 0f
    var onFreeChanged: ((Boolean) -> Unit)? = null

    /** Orientation of the view: the truck's heading while following, frozen while free-looking. */
    val viewBearing: Float get() = if (free) freeBearing else bearing
    private val d = resources.displayMetrics.density

    private val bufs = Array(4) { FloatArray(8192) }
    private val counts = IntArray(4)
    private val widthsM = floatArrayOf(7f, 11f, 18f, 8f)
    private val colors = intArrayOf(
        Color.rgb(70, 82, 98), Color.rgb(104, 118, 138), Color.rgb(222, 178, 84), Color.rgb(82, 94, 112),
    )
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.argb(235, 255, 255, 255)
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.rgb(36, 132, 255)
    }
    private val truckFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 214, 120) }
    private val truckEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 220, 226, 236); textSize = 13f * d; textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }
    private val markerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 92, 58); style = Paint.Style.STROKE; strokeWidth = 3f * d
    }
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 220, 226, 236); textSize = 16f * d; textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val poiBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val poiTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val poiColors = mapOf(
        Poi.FUEL to Color.rgb(230, 120, 20), Poi.PARKING to Color.rgb(40, 110, 220), Poi.SERVICE to Color.rgb(120, 130, 145),
        Poi.WEIGH to Color.rgb(150, 90, 190), Poi.DEALER to Color.rgb(190, 60, 60), Poi.GARAGE to Color.rgb(60, 140, 120),
        Poi.RECRUIT to Color.rgb(160, 140, 40), Poi.HOTEL to Color.rgb(90, 90, 170),
    )
    private val poiLetters = mapOf(
        Poi.FUEL to "F", Poi.PARKING to "P", Poi.SERVICE to "S", Poi.WEIGH to "W",
        Poi.DEALER to "D", Poi.GARAGE to "G", Poi.RECRUIT to "R", Poi.HOTEL to "H",
    )
    var showPoiTypes: Set<Int> = setOf(Poi.FUEL, Poi.PARKING, Poi.SERVICE, Poi.WEIGH)

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            userZoom = (userZoom * det.scaleFactor).coerceIn(0.35f, 4f)
            invalidate()
            return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val w = toWorld(e.x, e.y) ?: return
            onLongPress?.invoke(w.first, w.second)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            userZoom = 1f
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress || e2.pointerCount > 1 || !hasFix || map == null) return false
            if (!free) enterFree()
            val s = pxPerM()
            val b = freeBearing
            val u = distanceX / s // finger left -> view moves right
            val v = -distanceY / s // finger up -> view moves back
            freeCx += u * cos(b) + v * sin(b)
            freeCz += u * sin(b) - v * cos(b)
            invalidate()
            return true
        }
    })

    private fun enterFree() {
        // the view centre is a little ahead of the truck (the truck sits low on screen)
        val s = pxPerM()
        val ahead = -(height / 2f - pivotY()) / s
        freeBearing = bearing
        freeCx = truckX + ahead * sin(bearing)
        freeCz = truckZ - ahead * cos(bearing)
        free = true
        onFreeChanged?.invoke(true)
    }

    /** Back to following the truck. */
    fun recentre() {
        if (!free) return
        free = false
        onFreeChanged?.invoke(false)
        invalidate()
    }

    fun zoomBy(f: Float) {
        userZoom = (userZoom * f).coerceIn(0.35f, 4f)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    // -------------------------------------------------------------------- projection --

    private fun pivotX() = width * 0.5f
    private fun pivotY() = if (width > height) height * 0.68f else height * 0.72f

    private fun pxPerM(): Float {
        // fixed zoom: only pinch / +/- buttons change it (auto speed-zoom made the map breathe in and out)
        return 1.0f * userZoom * d * 0.55f
    }

    private fun toWorld(sx: Float, sy: Float): Pair<Float, Float>? {
        if (!hasFix) return null
        val s = pxPerM()
        val px = if (free) width / 2f else pivotX()
        val py = if (free) height / 2f else pivotY()
        val b = viewBearing
        val u = (sx - px) / s
        val v = -(sy - py) / s
        val fx = sin(b); val fz = -cos(b)
        val rx = cos(b); val rz = sin(b)
        val cx = if (free) freeCx else truckX
        val cz = if (free) freeCz else truckZ
        return Pair(cx + u * rx + v * fx, cz + u * rz + v * fz)
    }

    private fun grow(c: Int, need: Int) {
        if (counts[c] + need > bufs[c].size) bufs[c] = bufs[c].copyOf(max(bufs[c].size * 2, counts[c] + need))
    }

    // ------------------------------------------------------------------------ drawing --

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 21, 28))
        val m = map
        if (m == null) {
            canvas.drawText("No map loaded - open settings to download it from your PC", width / 2f, height / 2f, msg)
            return
        }
        if (!hasFix) {
            canvas.drawText("Waiting for the game...", width / 2f, height * 0.5f, msg)
            return
        }
        val s = pxPerM()
        val isFree = free
        val vb = viewBearing
        val px = if (isFree) width / 2f else pivotX()
        val py = if (isFree) height / 2f else pivotY()
        val cs = cos(vb); val sn = sin(vb)
        val fx = sn; val fz = -cs
        val rx = cs; val rz = sn
        val cx = if (isFree) freeCx else truckX
        val cz = if (isFree) freeCz else truckZ

        val radius = hypot(max(px, width - px), max(py, height - py)) / s + 20f
        counts.fill(0)
        val zoomFactor = s / (d * 0.55f)
        val showLocal = zoomFactor > 0.3f
        val showJunction = zoomFactor > 0.45f
        m.queryEdges(cx, cz, radius) { e ->
            val cls = m.eCls[e].toInt().coerceIn(0, 3)
            if ((cls == 0 && !showLocal) || (cls == 3 && !showJunction)) return@queryEdges
            val a = m.ePtStart[e]
            val b = m.ePtStart[e + 1]
            if (b - a < 2) return@queryEdges
            grow(cls, (b - a) * 4)
            val buf = bufs[cls]
            var n = counts[cls]
            var lx = 0f; var ly = 0f
            for (p in a until b) {
                val wx = m.ptX[p] - cx; val wz = m.ptZ[p] - cz
                val sx = px + (wx * rx + wz * rz) * s
                val sy = py - (wx * fx + wz * fz) * s
                if (p > a) {
                    buf[n++] = lx; buf[n++] = ly; buf[n++] = sx; buf[n++] = sy
                }
                lx = sx; ly = sy
            }
            counts[cls] = n
        }
        for (c in intArrayOf(3, 0, 1, 2)) {
            if (counts[c] == 0) continue
            roadPaint.color = colors[c]
            roadPaint.strokeWidth = max(widthsM[c] * s, if (c == 2) 3.2f * d else 2.2f * d)
            canvas.drawLines(bufs[c], 0, counts[c], roadPaint)
        }

        // route
        if (routePts.isNotEmpty() && routeEdgePt.isNotEmpty()) {
            val from = routePts.size / 2
            val startEdge = max(0, routeIdx - 1)
            val first = routeEdgePt[min(startEdge, routeEdgePt.size - 1)]
            val last = min(from, first + 6000)
            path.reset()
            var started = false
            for (p in first until last) {
                val wx = routePts[p * 2] - cx; val wz = routePts[p * 2 + 1] - cz
                val sx = px + (wx * rx + wz * rz) * s
                val sy = py - (wx * fx + wz * fz) * s
                if (!started) { path.moveTo(sx, sy); started = true } else path.lineTo(sx, sy)
            }
            val w = max(10f * s, 6f * d)
            routeCasing.strokeWidth = w + 5f * d
            routePaint.strokeWidth = w
            canvas.drawPath(path, routeCasing)
            canvas.drawPath(path, routePaint)
        }

        // city names when zoomed out a bit
        if (zoomFactor < 0.9f) {
            for (c in m.cities) {
                if (abs2(c.x - cx, c.z - cz) > radius * radius) continue
                val wx = c.x - cx; val wz = c.z - cz
                val sx = px + (wx * rx + wz * rz) * s
                val sy = py - (wx * fx + wz * fz) * s
                if (sx < -50 || sx > width + 50 || sy < -20 || sy > height + 20) continue
                canvas.drawText(c.name, sx, sy, label)
            }
        }

        // points of interest (fuel, rest areas, service, weigh stations ...)
        if (zoomFactor > 0.3f) {
            val r = if (zoomFactor > 0.8f) 24f * d else 18f * d
            poiTxt.textSize = r * 1.15f
            val lim = radius
            for (p in m.pois) {
                if (p.type !in showPoiTypes) continue
                val wx = p.x - cx; val wz = p.z - cz
                if (wx > lim || wx < -lim || wz > lim || wz < -lim) continue
                val sx = px + (wx * rx + wz * rz) * s
                val sy = py - (wx * fx + wz * fz) * s
                if (sx < -r || sx > width + r || sy < -r || sy > height + r) continue
                poiBg.color = poiColors[p.type] ?: Color.GRAY
                canvas.drawCircle(sx, sy, r, poiBg)
                canvas.drawText(poiLetters[p.type] ?: "?", sx, sy + r * 0.4f, poiTxt)
            }
        }

        // next maneuver marker on the road
        val nm = nextManeuver
        if (nm != null) {
            val wx = nm.x - cx; val wz = nm.z - cz
            val sx = px + (wx * rx + wz * rz) * s
            val sy = py - (wx * fx + wz * fz) * s
            if (sx > -80 && sx < width + 80 && sy > -80 && sy < height + 80) {
                val r = 26f * d
                canvas.drawCircle(sx, sy, r, markerBg)
                canvas.drawCircle(sx, sy, r, markerEdge)
                // the glyph is drawn relative to the travel direction; with a frozen free-look view that no longer
                // lines up with the screen, so show the pictogram only while following the truck
                if (!isFree) Glyphs.draw(canvas, nm.turn, nm.exitNo, nm.angleDeg, sx, sy, r * 1.55f, Color.rgb(18, 92, 58))
                else canvas.drawCircle(sx, sy, r * 0.35f, markerEdge)
            }
        }

        // truck (at its real position and heading relative to the current view)
        val twx = truckX - cx; val twz = truckZ - cz
        val tx = px + (twx * rx + twz * rz) * s
        val ty = py - (twx * fx + twz * fz) * s
        val t = 15f * d
        canvas.save()
        canvas.rotate(Math.toDegrees((bearing - vb).toDouble()).toFloat(), tx, ty)
        path.reset()
        path.moveTo(tx, ty - t * 1.2f)
        path.lineTo(tx + t * 0.85f, ty + t * 0.85f)
        path.lineTo(tx, ty + t * 0.4f)
        path.lineTo(tx - t * 0.85f, ty + t * 0.85f)
        path.close()
        canvas.drawPath(path, truckFill)
        canvas.drawPath(path, truckEdge)
        canvas.restore()
    }

    private fun abs2(dx: Float, dz: Float) = dx * dx + dz * dz
}
