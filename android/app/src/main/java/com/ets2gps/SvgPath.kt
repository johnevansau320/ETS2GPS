package com.ets2gps

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Tiny SVG path reader (M L H V A Z, absolute and relative) for the point-of-interest pictograms; no libraries needed. */
object SvgPath {
    private val token = Regex("[a-zA-Z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun parse(d: String): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        val t = token.findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = ' '
        var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0
        fun num() = t[i++].toDouble()
        while (i < t.size) {
            if (t[i][0].isLetter()) cmd = t[i++][0]
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    path.moveTo(x.toFloat(), y.toFloat()); cx = x; cy = y; sx = x; sy = y
                    cmd = if (rel) 'l' else 'L'   // further pairs are line-tos
                }
                'L' -> {
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    path.lineTo(x.toFloat(), y.toFloat()); cx = x; cy = y
                }
                'H' -> { var x = num(); if (rel) x += cx; path.lineTo(x.toFloat(), cy.toFloat()); cx = x }
                'V' -> { var y = num(); if (rel) y += cy; path.lineTo(cx.toFloat(), y.toFloat()); cy = y }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0.0; val sweep = num() != 0.0
                    var x = num(); var y = num()
                    if (rel) { x += cx; y += cy }
                    arc(path, cx, cy, rx, ry, rot, large, sweep, x, y)
                    cx = x; cy = y
                }
                'Z' -> { path.close(); cx = sx; cy = sy }
                else -> throw IllegalArgumentException("unsupported path command $cmd")
            }
        }
        return path
    }

    /** SVG elliptical arc, converted from its endpoint form to cubic curves. */
    private fun arc(path: Path, x0: Double, y0: Double, rxIn: Double, ryIn: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x1: Double, y1: Double) {
        var rx = abs(rxIn); var ry = abs(ryIn)
        if (rx == 0.0 || ry == 0.0 || (x0 == x1 && y0 == y1)) { path.lineTo(x1.toFloat(), y1.toFloat()); return }
        val phi = rotDeg * PI / 180; val cp = cos(phi); val sp = sin(phi)
        val dx2 = (x0 - x1) / 2; val dy2 = (y0 - y1) / 2
        val x1p = cp * dx2 + sp * dy2; val y1p = -sp * dx2 + cp * dy2
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { val f = sqrt(lam); rx *= f; ry *= f }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = (if (large == sweep) -1.0 else 1.0) * sqrt(maxOf(0.0, num / den))
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val ccx = cp * cxp - sp * cyp + (x0 + x1) / 2; val ccy = sp * cxp + cp * cyp + (y0 + y1) / 2
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val n = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val delta = dth / n
        val k = 4.0 / 3.0 * tan(delta / 4)
        fun px(t: Double) = ccx + cp * rx * cos(t) - sp * ry * sin(t)
        fun py(t: Double) = ccy + sp * rx * cos(t) + cp * ry * sin(t)
        fun dx(t: Double) = -cp * rx * sin(t) - sp * ry * cos(t)
        fun dy(t: Double) = -sp * rx * sin(t) + cp * ry * cos(t)
        var t = th1
        for (s in 0 until n) {
            val t2 = t + delta
            path.cubicTo(
                (px(t) + k * dx(t)).toFloat(), (py(t) + k * dy(t)).toFloat(),
                (px(t2) - k * dx(t2)).toFloat(), (py(t2) - k * dy(t2)).toFloat(),
                px(t2).toFloat(), py(t2).toFloat(),
            )
            t = t2
        }
    }
}
