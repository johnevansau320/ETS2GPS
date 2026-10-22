package com.ets2gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
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
    var routeLift = ByteArray(0) // per route point: height above the road it crosses (0.5 m units)
    var stops = FloatArray(0) // waypoint positions, x,z pairs (drawn as numbered markers)
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
    // road atlas look: steel-blue roads with a darker outline on a slate background
    private val colors = intArrayOf(
        Color.rgb(70, 96, 128), Color.rgb(84, 114, 150), Color.rgb(236, 190, 90), Color.rgb(64, 88, 118),
    )
    private val casings = intArrayOf(Color.rgb(26, 32, 44), Color.rgb(26, 32, 44), Color.rgb(120, 84, 24), Color.rgb(26, 32, 44))
    private val areaColors = intArrayOf(Color.rgb(52, 64, 84), Color.rgb(226, 190, 136), Color.rgb(212, 160, 92), Color.rgb(112, 160, 104))
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val areaPath = Path()
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shieldEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val shieldText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 36, 48); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val rect = RectF()
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
    private val poiEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(235, 255, 255, 255) }
    private val poiShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private val poiGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val poiRect = RectF()
    private val poiShapes = HashMap<Int, Path>()
    private fun poiShape(type: Int): Path? = poiShapes.getOrPut(type) {
        val spec = PoiIcons.paths[type] ?: return null
        try { SvgPath.parse(spec) } catch (e: Exception) { Path() }
    }

    /** A point-of-interest badge: coloured rounded square with a white pictogram. */
    private fun drawPoi(canvas: Canvas, type: Int, x: Float, y: Float, r: Float) {
        poiRect.set(x - r, y - r + r * 0.14f, x + r, y + r + r * 0.14f)
        canvas.drawRoundRect(poiRect, r * 0.42f, r * 0.42f, poiShadow)
        poiRect.set(x - r, y - r, x + r, y + r)
        poiBg.color = PoiIcons.colors[type] ?: Color.GRAY
        canvas.drawRoundRect(poiRect, r * 0.42f, r * 0.42f, poiBg)
        poiEdge.strokeWidth = max(1.5f * d, r * 0.1f)
        canvas.drawRoundRect(poiRect, r * 0.42f, r * 0.42f, poiEdge)
        val shape = poiShape(type) ?: return
        val k = 2f * r * 0.66f / 24f
        val sv = canvas.save()
        canvas.translate(x - 12f * k, y - 12f * k)
        canvas.scale(k, k)
        canvas.drawPath(shape, poiGlyph)
        canvas.restoreToCount(sv)
    }
    var showPoiTypes: Set<Int> = setOf(Poi.FUEL, Poi.PARKING, Poi.SERVICE, Poi.WEIGH, Poi.GARAGE, Poi.DEALER, Poi.FERRY, Poi.TRAIN)

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            userZoom = (userZoom * det.scaleFactor).coerceIn(minZoom, 10f)
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
            if (!planMode) userZoom = 1f
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress || e2.pointerCount > 1 || (!hasFix && !planMode) || map == null) return false
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
        userZoom = (userZoom * f).coerceIn(minZoom, 10f)
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
        if (!hasFix && !planMode) return null
        val s = pxPerM()
        val px = if (free) width / 2f else pivotX()
        val py = if (free) height / 2f else pivotY()
        var fxs = sx; var fys = sy
        if (tilt >= 0.001f) {
            val e = ecam(height.toFloat(), py)
            val sT = sin(tilt); val cT = cos(tilt)
            val yp = py - sy
            val den = e * cT - yp * sT
            if (den <= 1e-6f) return null
            val y0 = yp * e / den
            val sc = e / (e + y0 * sT)
            fxs = px + (sx - px) / sc; fys = py - y0
        }
        val b = viewBearing
        val u = (fxs - px) / s
        val v = -(fys - py) / s
        val fx = sin(b); val fz = -cos(b)
        val rx = cos(b); val rz = sin(b)
        val cx = if (free) freeCx else truckX
        val cz = if (free) freeCz else truckZ
        return Pair(cx + u * rx + v * fx, cz + u * rz + v * fz)
    }

    private fun grow(c: Int, need: Int) {
        if (counts[c] + need > bufs[c].size) bufs[c] = bufs[c].copyOf(max(bufs[c].size * 2, counts[c] + need))
    }

    // ------------------------------------------------------------------------ 3D tilt --

    private val tiltMax = (52.0 * Math.PI / 180.0).toFloat()
    private var tilt = 0f
    private var driving = false
    private var lastTick = 0L
    var speedKmh = 0f
    var assets: MapAssets? = null

    // plan mode: the map shows a planned trip (north up, any zoom, no truck) instead of following the truck
    var planMode = false
        private set
    var planPts = FloatArray(0)
    var planEdgePt = IntArray(0)
    var planLift = ByteArray(0)
    class Pin(val x: Float, val z: Float, val label: String, val color: Int)
    var pins: List<Pin> = emptyList()
    private val minZoom get() = if (planMode) 0.004f else 0.35f

    fun showPlan(cx: Float, cz: Float, zoom: Float) {
        planMode = true; free = true; freeBearing = 0f; freeCx = cx; freeCz = cz; userZoom = zoom
        invalidate()
    }

    fun hidePlan() {
        planMode = false; free = false; pins = emptyList(); planPts = FloatArray(0); planEdgePt = IntArray(0); planLift = ByteArray(0); userZoom = 1f
        invalidate()
    }

    private fun ecam(h: Float, py: Float) = (py + 0.12f * h) * kotlin.math.tan(tiltMax)

    // per-frame projection state, and the result of the last proj() call
    private var vpx = 0f; private var vpy = 0f; private var vcs = 1f; private var vsn = 0f
    private var vcx = 0f; private var vcz = 0f; private var vs = 1f; private var vE = 1f
    private var qx = 0f; private var qy = 0f; private var qs = 1f

    private fun proj(wx: Float, wz: Float) {
        val a = wx - vcx; val b = wz - vcz
        val fxp = vpx + (a * vcs + b * vsn) * vs
        val fyp = vpy - (a * vsn - b * vcs) * vs
        if (tilt < 0.001f) { qx = fxp; qy = fyp; qs = 1f; return }
        val x0 = fxp - vpx
        var y0 = vpy - fyp
        val sT = sin(tilt); val cT = cos(tilt)
        val lim = -vE / sT * 0.8f
        if (y0 < lim) y0 = lim
        val sc = vE / (vE + y0 * sT)
        qx = vpx + x0 * sc; qy = vpy - y0 * cT * sc; qs = sc
    }

    private val bandFactor = floatArrayOf(1f, 0.72f, 0.55f, 0.42f, 0.3f)
    private fun band(sc: Float) = if (sc >= 0.8f) 0 else if (sc >= 0.62f) 1 else if (sc >= 0.48f) 2 else if (sc >= 0.36f) 3 else 4
    private fun fscale(sc: Float) = if (tilt < 0.001f) 1f else Math.pow(sc.toDouble(), 0.6).toFloat().coerceIn(0.5f, 1.1f)

    private inline fun scaled(x: Float, y: Float, f: Float, draw: () -> Unit) {
        if (abs(f - 1f) < 0.02f) { draw(); return }
        val sv = canvasRef!!.save()
        canvasRef!!.scale(f, f, x, y)
        draw()
        canvasRef!!.restoreToCount(sv)
    }
    private var canvasRef: Canvas? = null

    private val cityShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 10, 12, 20); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val cityText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 236, 224, 228); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val borderRed = Color.rgb(200, 40, 40)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Road segments per class and depth band, ready for drawLines. */
    private class SegSet {
        val bufs = Array(20) { FloatArray(2048) }
        val counts = IntArray(20)
        fun clear() = counts.fill(0)
        fun add(k: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            var buf = bufs[k]
            val n = counts[k]
            if (n + 4 > buf.size) { buf = buf.copyOf(buf.size * 2); bufs[k] = buf }
            buf[n] = x0; buf[n + 1] = y0; buf[n + 2] = x1; buf[n + 3] = y1
            counts[k] = n + 4
        }
    }
    private val groundSet = SegSet()
    private val deckSet = SegSet()
    private val shadowSet = SegSet()
    private val deckShadow = Color.argb(105, 6, 8, 14)
    private val routeGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.argb(120, 36, 132, 255)
    }
    private val routePaths = Array(5) { Path() }
    private val routeUsed = BooleanArray(5)

    // ------------------------------------------------------------------------ drawing --

    override fun onDraw(canvas: Canvas) {
        val m = map
        canvasRef = canvas
        // tilt: eases in above 15 km/h while following the truck, back to flat when slow or looking around
        val nowT = android.os.SystemClock.uptimeMillis()
        val dtT = min(0.1f, max(0f, (nowT - lastTick) / 1000f)); lastTick = nowT
        if (speedKmh > 15f) driving = true else if (speedKmh < 12f) driving = false
        val target = if (driving && !free && hasFix && m != null && !planMode) tiltMax else 0f
        if (abs(tilt - target) > 0.0005f) {
            tilt += (target - tilt).coerceIn(-dtT * 1.6f, dtT * 1.6f)
            postInvalidateOnAnimation()
        }
        val tilted = tilt >= 0.001f
        canvas.drawColor(Color.rgb(34, 40, 54))
        if (m == null) {
            canvas.drawText("No map loaded - open settings to download it from your PC", width / 2f, height / 2f, msg)
            return
        }
        if (!hasFix && !planMode) {
            canvas.drawText("Waiting for the game...", width / 2f, height * 0.5f, msg)
            return
        }
        val s = pxPerM()
        val isFree = free
        val vb = viewBearing
        val px = if (isFree) width / 2f else pivotX()
        val py = if (isFree) height / 2f else pivotY()
        val cx = if (isFree) freeCx else truckX
        val cz = if (isFree) freeCz else truckZ
        vpx = px; vpy = py; vcs = cos(vb); vsn = sin(vb); vcx = cx; vcz = cz; vs = s
        val hh = height.toFloat()
        vE = ecam(hh, py)
        val yMax = 3.2f * hh
        var radius = hypot(max(px, width - px), max(py, height - py)) / s + 20f
        if (tilted) radius = max(radius, yMax / s * 1.05f + 20f)
        val zoomFactor = s / (d * 0.55f)
        val showLocal = zoomFactor > 0.3f
        val showJunction = zoomFactor > 0.45f
        groundSet.clear(); deckSet.clear(); shadowSet.clear()
        val hasLift = m.eHasLift.isNotEmpty()
        val liftK = if (tilted) s * sin(tilt) else 0f   // screen px for one metre of height
        val drawRoads = !(planMode && zoomFactor < 0.06f) // zoomed far out while planning: just the route and the towns
        if (drawRoads) m.queryEdges(cx, cz, radius) { e ->
            val cls = m.eCls[e].toInt().coerceIn(0, 3)
            if ((cls == 0 && !showLocal) || (cls == 3 && !showJunction)) return@queryEdges
            val a = m.ePtStart[e]
            val b = min(m.ePtStart[e + 1], a + 255)
            if (b - a < 2) return@queryEdges
            // roads on flyovers and bridges are drawn afterwards, raised and with a shadow, so the roads below pass underneath
            val elev = hasLift && m.eHasLift[e]
            var lx = 0f; var ly = 0f; var ls = 1f; var sx = 0f; var sy = 0f
            for (p in a until b) {
                proj(m.ptX[p], m.ptZ[p])
                var deckY = qy; var shx = qx; var shy = qy
                if (elev) {
                    val lf = 0.5f * (m.ptLift[p].toInt() and 0xFF)
                    deckY = qy - lf * liftK * qs
                    if (!tilted) { val o = min(12f * d, lf * s * 0.3f); shx = qx + o; shy = qy + o }
                }
                if (p > a) {
                    val sc = (ls + qs) / 2f
                    if (!tilted || sc >= 0.28f) {
                        val k = cls * 5 + (if (tilted) band(sc) else 0)
                        if (!elev) groundSet.add(k, lx, ly, qx, qy)
                        else { deckSet.add(k, lx, ly, qx, deckY); shadowSet.add(k, sx, sy, shx, shy) }
                    }
                }
                lx = qx; ly = deckY; ls = qs; sx = shx; sy = shy
            }
        }

        // lots, yards and green patches under the roads (one path per colour class)
        if (zoomFactor > 0.4f) {
            for (col in 0..3) {
                areaPath.reset()
                var any = false
                for (a in m.areas) {
                    if (a.color != col || a.maxX < cx - radius || a.minX > cx + radius || a.maxZ < cz - radius || a.minZ > cz + radius) continue
                    var ok = true
                    val start = areaPath.isEmpty
                    for (k in a.xs.indices) {
                        proj(a.xs[k], a.zs[k])
                        if (tilted && qs < 0.28f) { ok = false; break }
                        if (k == 0) areaPath.moveTo(qx, qy) else areaPath.lineTo(qx, qy)
                    }
                    if (ok) { areaPath.close(); any = true } else if (start) areaPath.reset()
                }
                if (any) { areaPaint.color = areaColors[col]; canvas.drawPath(areaPath, areaPaint) }
            }
        }
        fun roadWidth(c: Int, bd: Int) = max(widthsM[c] * s, if (c == 2) 3.4f * d else 2.4f * d) * bandFactor[bd]
        fun drawSegs(set: SegSet, casing: Boolean, shadow: Boolean = false) {
            for (c in intArrayOf(3, 0, 1, 2)) for (bd in 0..4) {
                val k = c * 5 + bd
                if (set.counts[k] == 0) continue
                roadPaint.color = if (shadow) deckShadow else if (casing) casings[c] else colors[c]
                roadPaint.strokeWidth = roadWidth(c, bd) + (if (shadow) 5f * d else if (casing) 2.6f * d else 0f)
                canvas.drawLines(set.bufs[k], 0, set.counts[k], roadPaint)
            }
        }
        drawSegs(groundSet, true); drawSegs(groundSet, false)

        // route
        val rp0 = if (planMode) planPts else routePts
        val re0 = if (planMode) planEdgePt else routeEdgePt
        var rl = if (planMode) planLift else routeLift
        if (rl.size != rp0.size / 2) rl = ByteArray(0)
        var rFirst = 0; var rLast = 0; var routeLifted = false
        val rw = max(10f * s, 6f * d)
        if (rp0.isNotEmpty() && re0.isNotEmpty()) {
            val total = rp0.size / 2
            val startEdge = max(0, routeIdx - 1)
            rFirst = if (planMode) 0 else re0[min(startEdge, re0.size - 1)]
            rLast = if (planMode) total else min(total, rFirst + 6000)
            if (rl.isNotEmpty()) for (p in rFirst until rLast) if (rl[p].toInt() != 0) { routeLifted = true; break }
        }
        // deck false: the route on the ground; deck true: the parts on flyovers / bridges, raised; ghost: the ground route again, faint
        fun drawRoute(deck: Boolean, ghost: Boolean) {
            if (rLast <= rFirst) return
            for (pth in routePaths) pth.reset()
            routeUsed.fill(false)
            var cur = -1; var lx = 0f; var ly = 0f; var ls = 1f
            for (p in rFirst until rLast) {
                proj(rp0[p * 2], rp0[p * 2 + 1])
                val y = if (deck && rl.isNotEmpty()) qy - 0.5f * (rl[p].toInt() and 0xFF) * liftK * qs else qy
                if (p > rFirst) {
                    val segUp = rl.isNotEmpty() && (rl[p].toInt() != 0 || rl[p - 1].toInt() != 0)
                    val sc = (ls + qs) / 2f
                    if (segUp != deck || sc < 0.28f) cur = -1
                    else {
                        val bd = if (tilted) band(sc) else 0
                        if (bd != cur) { routePaths[bd].moveTo(lx, ly); cur = bd; routeUsed[bd] = true }
                        routePaths[bd].lineTo(qx, y)
                    }
                }
                lx = qx; ly = y; ls = qs
            }
            if (!ghost) for (bd in 0..4) if (routeUsed[bd]) { routeCasing.strokeWidth = (rw + 5f * d) * bandFactor[bd]; canvas.drawPath(routePaths[bd], routeCasing) }
            for (bd in 0..4) if (routeUsed[bd]) {
                val pt = if (ghost) routeGhost else routePaint
                pt.strokeWidth = rw * bandFactor[bd]
                canvas.drawPath(routePaths[bd], pt)
            }
        }
        drawRoute(false, false)
        if (hasLift) {
            drawSegs(shadowSet, false, shadow = true)
            drawSegs(deckSet, true); drawSegs(deckSet, false)
            if (routeLifted) { drawRoute(true, false); drawRoute(false, true) } // the route below shows faintly through the deck above it
        }

        fun visible(mx: Float, my: Float) = !((tilted && qs < 0.3f) || qx < -mx || qx > width + mx || qy < -my || qy > height + my)

        // big area (city) names
        if (true) { // area (city) names stay on at every zoom level
            val size = (26f * Math.pow(zoomFactor.toDouble(), 0.35).toFloat()).coerceIn(if (planMode) 11f else 20f, 34f) * d
            cityText.textSize = size; cityShadow.textSize = size
            for (c in m.cities) {
                if (abs2(c.x - cx, c.z - cz) > radius * radius) continue
                proj(c.x, c.z)
                if (!visible(120f * d, 30f * d)) continue
                val name = c.name.uppercase()
                val x = qx; val y = qy
                scaled(x, y, fscale(qs)) {
                    canvas.drawText(name, x + 1.5f * d, y + size * 0.35f + 1.5f * d, cityShadow)
                    canvas.drawText(name, x, y + size * 0.35f, cityText)
                }
            }
        }

        // border crossings: checkpoint symbol, or the flag of the country being entered
        if (zoomFactor > 0.35f) {
            for (bp in m.borders) {
                val wx = bp.x - cx; val wz = bp.z - cz
                if (wx > radius || wx < -radius || wz > radius || wz < -radius) continue
                proj(bp.x, bp.z)
                if (!visible(140f * d, 90f * d)) continue
                val x = qx; val y = qy
                if (bp.kind == 0) {
                    if (zoomFactor < 0.6f) continue
                    scaled(x, y, fscale(qs)) {
                        shieldText.textSize = 12f * d
                        rect.set(x - 34f * d, y - 12f * d, x + 34f * d, y + 12f * d)
                        shieldPaint.color = Color.WHITE
                        canvas.drawRoundRect(rect, 4f * d, 4f * d, shieldPaint)
                        shieldEdge.color = borderRed; shieldEdge.strokeWidth = 2.2f * d
                        canvas.drawRoundRect(rect, 4f * d, 4f * d, shieldEdge)
                        shieldEdge.color = Color.WHITE
                        shieldText.color = borderRed
                        canvas.drawText("BORDER", x, y + 4.5f * d, shieldText)
                        shieldText.color = Color.WHITE
                    }
                } else {
                    val flag = assets?.flag(bp.cc) ?: continue
                    val country = CountryNames.of(bp.cc)
                    scaled(x, y, fscale(qs)) {
                        // the flag (2x) with the name of the country being entered right under it
                        canvas.drawCircle(x, y, 42f * d, whitePaint)
                        rect.set(x - 38f * d, y - 38f * d, x + 38f * d, y + 38f * d)
                        canvas.drawBitmap(flag, null, rect, bmpPaint)
                        val sv = pillText.textSize
                        pillText.textSize = 22f * d
                        val hw = pillText.measureText(country) / 2 + 8f * d; val hgt = 14f * d
                        rect.set(x - hw, y + 36f * d, x + hw, y + 36f * d + 2 * hgt)
                        canvas.drawRoundRect(rect, 6f * d, 6f * d, shieldPaint)
                        canvas.drawText(country, x, y + 36f * d + hgt + 7.5f * d, pillText)
                        pillText.textSize = sv
                    }
                }
            }
        }

        // road-number shields and company logos / names
        if (zoomFactor > 0.5f) {
            shieldText.textSize = 17f * d; shieldEdge.strokeWidth = 1.6f * d
            for (rl in m.roadLabels) {
                val wx = rl.x - cx; val wz = rl.z - cz
                if (wx > radius || wx < -radius || wz > radius || wz < -radius) continue
                proj(rl.x, rl.z)
                if (!visible(40f * d, 20f * d)) continue
                val x = qx; val y = qy
                scaled(x, y, fscale(qs)) {
                    val hw = shieldText.measureText(rl.text) / 2 + 6f * d; val hgt = 12f * d
                    rect.set(x - hw, y - hgt, x + hw, y + hgt)
                    shieldPaint.color = if (rl.text.startsWith("E")) Color.rgb(0, 128, 72) else Color.rgb(0, 64, 176)
                    canvas.drawRoundRect(rect, 3f * d, 3f * d, shieldPaint)
                    canvas.drawRoundRect(rect, 3f * d, 3f * d, shieldEdge)
                    canvas.drawText(rl.text, x, y + 6f * d, shieldText)
                }
            }
        }
        if (zoomFactor > 0.75f) {
            pillText.textSize = 15f * d
            for (co in m.companies) {
                val wx = co.x - cx; val wz = co.z - cz
                if (wx > radius || wx < -radius || wz > radius || wz < -radius) continue
                proj(co.x, co.z)
                if (!visible(90f * d, 30f * d) || (tilted && qs < 0.35f)) continue
                val x = qx; val y = qy
                val logo = assets?.logo(co.id)
                scaled(x, y, fscale(qs)) {
                    shieldPaint.color = Color.argb(240, 250, 250, 252)
                    if (logo != null) {
                        rect.set(x - 56f * d, y - 16f * d, x + 56f * d, y + 16f * d)
                        canvas.drawRoundRect(rect, 5f * d, 5f * d, shieldPaint)
                        rect.set(x - 52f * d, y - 13f * d, x + 52f * d, y + 13f * d)
                        canvas.drawBitmap(logo, null, rect, bmpPaint)
                    } else {
                        val name = m.companyNames[co.id.lowercase()] ?: co.id
                        val hw = pillText.measureText(name) / 2 + 5f * d; val hgt = 11f * d
                        rect.set(x - hw, y - hgt, x + hw, y + hgt)
                        canvas.drawRoundRect(rect, 4f * d, 4f * d, shieldPaint)
                        canvas.drawText(name, x, y + 5f * d, pillText)
                    }
                }
            }
        }

        // points of interest (fuel, rest areas, service, garages, dealers, weigh stations, ferries ...)
        if (zoomFactor > 0.3f) {
            val r = if (zoomFactor > 0.8f) 24f * d else 18f * d
            for (p in m.pois) {
                if (p.type !in showPoiTypes) continue
                val wx = p.x - cx; val wz = p.z - cz
                if (wx > radius || wx < -radius || wz > radius || wz < -radius) continue
                proj(p.x, p.z)
                if (!visible(r, r)) continue
                val x = qx; val y = qy
                scaled(x, y, fscale(qs)) { drawPoi(canvas, p.type, x, y, r) }
            }
        }

        // planned trip: start, stops and destination pins
        if (planMode) for (pin in pins) {
            proj(pin.x, pin.z)
            if (qx < -60 || qx > width + 60 || qy < -60 || qy > height + 60) continue
            stopBg.color = pin.color
            canvas.drawCircle(qx, qy, 20f * d, stopBg)
            canvas.drawCircle(qx, qy, 20f * d, markerEdge)
            poiTxt.textSize = 22f * d
            canvas.drawText(pin.label, qx, qy + 8f * d, poiTxt)
        }

        // waypoints
        for (i in 0 until (if (planMode) 0 else stops.size / 2)) {
            proj(stops[i * 2], stops[i * 2 + 1])
            if (qx < -60 || qx > width + 60 || qy < -60 || qy > height + 60) continue
            val x = qx; val y = qy
            scaled(x, y, fscale(qs)) {
                val r = 17f * d
                stopBg.color = Color.rgb(25, 118, 210)
                canvas.drawCircle(x, y, r, stopBg)
                canvas.drawCircle(x, y, r, markerEdge)
                poiTxt.textSize = r * 1.2f
                canvas.drawText((i + 1).toString(), x, y + r * 0.42f, poiTxt)
            }
        }

        // next maneuver marker on the road
        val nm = if (planMode) null else nextManeuver
        if (nm != null) {
            proj(nm.x, nm.z)
            if (qx > -80 && qx < width + 80 && qy > -80 && qy < height + 80) {
                val x = qx; val y = qy
                scaled(x, y, fscale(qs)) {
                    val r = 26f * d
                    canvas.drawCircle(x, y, r, markerBg)
                    canvas.drawCircle(x, y, r, markerEdge)
                    // the glyph is drawn relative to the travel direction; with a frozen free-look view that no longer
                    // lines up with the screen, so show the pictogram only while following the truck
                    if (!isFree) Glyphs.draw(canvas, nm.turn, nm.exitNo, nm.angleDeg, x, y, r * 1.55f, Color.rgb(18, 92, 58))
                    else canvas.drawCircle(x, y, r * 0.35f, markerEdge)
                }
            }
        }

        // haze over the far distance
        if (tilted) {
            val sT = sin(tilt); val cT = cos(tilt)
            val yFar = py - yMax * cT * (vE / (vE + yMax * sT))
            val fadeTo = max(yFar + 60f, hh * 0.2f)
            val a = (255f * min(1f, tilt / tiltMax)).toInt()
            hazePaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, fadeTo,
                intArrayOf(Color.rgb(34, 40, 54), Color.argb(a, 34, 40, 54), Color.argb(0, 34, 40, 54)), floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), fadeTo, hazePaint)
        }

        if (planMode) return

        // truck (flat, at the pivot, heading relative to the current view)
        val tx = px; val ty = py
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

    private val hazePaint = Paint()
    private val stopBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun abs2(dx: Float, dz: Float) = dx * dx + dz * dz
}
