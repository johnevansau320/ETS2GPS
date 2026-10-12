'use strict';
/* Canvas drawing helpers: maneuver pictograms, lane panel, compass. */

const Glyphs = {
  arrowHead(c, tx, ty, dx, dy, s) {
    const h = s * 0.26, w = s * 0.2, bx = tx - dx * h, by = ty - dy * h;
    c.beginPath();
    c.moveTo(tx + dx * h * 0.35, ty + dy * h * 0.35);
    c.lineTo(bx - dy * w, by + dx * w);
    c.lineTo(bx + dy * w, by - dx * w);
    c.closePath(); c.fill();
  },

  /** Draws a maneuver centred at (cx, cy) inside a square of side size. */
  draw(c, turn, exitNo, angleDeg, cx, cy, size, color) {
    const s = size;
    c.save();
    c.strokeStyle = color; c.fillStyle = color; c.lineWidth = s * 0.13; c.lineCap = 'butt'; c.lineJoin = 'round';
    const dim = 'rgba(255,255,255,0.35)';
    const line = (x0, y0, x1, y1) => { c.beginPath(); c.moveTo(x0, y0); c.lineTo(x1, y1); c.stroke(); };
    if (turn === Turn.ARRIVE) {
      c.lineWidth = s * 0.1;
      c.beginPath(); c.arc(cx, cy - s * 0.08, s * 0.26, 0, Math.PI * 2); c.stroke();
      c.beginPath(); c.arc(cx, cy - s * 0.08, s * 0.09, 0, Math.PI * 2); c.fill();
      line(cx, cy + s * 0.18, cx, cy + s * 0.42);
    } else if (turn === Turn.UTURN) {
      if (angleDeg > 0) { c.translate(cx, 0); c.scale(-1, 1); c.translate(-cx, 0); }
      c.beginPath();
      c.moveTo(cx + s * 0.16, cy + s * 0.42);
      c.lineTo(cx + s * 0.16, cy - s * 0.14);
      c.arc(cx, cy - s * 0.14, s * 0.16, 0, Math.PI, true);
      c.lineTo(cx - s * 0.16, cy + s * 0.06);
      c.stroke();
      this.arrowHead(c, cx - s * 0.16, cy + s * 0.10, 0, 1, s);
    } else if (turn === Turn.ROUNDABOUT) {
      const ry = cy - s * 0.06, r = s * 0.2;
      c.lineWidth = s * 0.1;
      c.strokeStyle = dim; c.beginPath(); c.arc(cx, ry, r, 0, Math.PI * 2); c.stroke();
      c.strokeStyle = color;
      line(cx, cy + s * 0.44, cx, ry + r);
      const th = angleDeg * Math.PI / 180, dx = Math.sin(th), dy = -Math.cos(th);
      line(cx + dx * r, ry + dy * r, cx + dx * (r + s * 0.26), ry + dy * (r + s * 0.26));
      this.arrowHead(c, cx + dx * (r + s * 0.26), ry + dy * (r + s * 0.26), dx, dy, s * 0.9);
      if (exitNo > 0) {
        c.font = 'bold ' + (s * 0.24) + 'px sans-serif'; c.textAlign = 'center';
        c.fillText(String(exitNo), cx, ry + s * 0.085);
      }
    } else {
      const signed = { KEEP_LEFT: -22, KEEP_RIGHT: 22, BEAR_LEFT: -48, BEAR_RIGHT: 48, TURN_LEFT: -90, TURN_RIGHT: 90 }[turn] || 90;
      const th = signed * Math.PI / 180, jx = cx, jy = cy + s * 0.06;
      c.strokeStyle = dim; line(jx, jy, jx, cy - s * 0.4);
      c.strokeStyle = color; line(jx, cy + s * 0.44, jx, jy);
      const dx = Math.sin(th), dy = -Math.cos(th), len = s * 0.34;
      line(jx, jy, jx + dx * len, jy + dy * len);
      this.arrowHead(c, jx + dx * len, jy + dy * len, dx, dy, s);
    }
    c.restore();
  },
};

/** Lane panel: one arrow per lane, green for the lanes the route uses, white for others, faint second arrow if a lane serves two directions. */
const LanePanel = {
  CELL: 50, PAD: 10, H: 112,
  width(n, caption) { return Math.max(n * this.CELL + 2 * this.PAD, caption ? 80 : 0); },

  draw(canvas, lanes, caption) {
    const dpr = window.devicePixelRatio || 1;
    const W = this.width(lanes.length, caption), H = this.H;
    if (canvas.width !== Math.round(W * dpr) || canvas.height !== Math.round(H * dpr)) {
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
    }
    const c = canvas.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, W, H);
    c.fillStyle = 'rgba(38,46,62,0.92)';
    c.beginPath(); c.roundRect(0, 0, W, H, 14); c.fill();
    if (caption) {
      c.fillStyle = 'rgba(255,255,255,0.9)'; c.font = 'bold 14px sans-serif'; c.textAlign = 'center';
      c.fillText(caption, W / 2, H - 9);
    }
    const x0 = (W - lanes.length * this.CELL) / 2;
    lanes.forEach((lane, i) => {
      const cx = x0 + this.CELL * (i + 0.5);
      if (i > 0) {
        c.save(); c.setLineDash([5, 5]); c.strokeStyle = 'rgba(255,255,255,0.43)'; c.lineWidth = 1.5;
        c.beginPath(); c.moveTo(x0 + this.CELL * i, 10); c.lineTo(x0 + this.CELL * i, H - 30); c.stroke(); c.restore();
      }
      if (lane.altDeg !== null && lane.altDeg !== undefined) this.arrow(c, cx, H, lane.altDeg, 'rgba(255,255,255,0.37)', false);
      if (Number.isNaN(lane.angleDeg)) return;
      if (lane.preferred) this.arrow(c, cx, H, lane.angleDeg, 'rgb(60,235,130)', true);
      else this.arrow(c, cx, H, lane.angleDeg, 'rgba(245,247,250,0.92)', false);
    });
  },

  /** Arrow rising from the bottom of the lane cell: a stem, then a bend of deg (+ = right) ending in a head. */
  arrow(c, cx, H, deg, color, bright) {
    const w = 7, bottom = H - 34, top = 14;
    const a = Math.max(-100, Math.min(100, deg)), th = a * Math.PI / 180;
    const dx = Math.sin(th), dy = -Math.cos(th);
    const straight = Math.abs(a) < 12;
    const bendY = straight ? top + 8 : (top + bottom) / 2, len = straight ? 0 : 24;
    const ex = cx + dx * len, ey = bendY + dy * len;
    const tipX = straight ? cx : ex, tipY = straight ? top : ey, fx = straight ? 0 : dx, fy = straight ? -1 : dy;
    const hl = 13, hw = 11;
    const shaft = () => {
      c.beginPath(); c.moveTo(cx, bottom);
      if (straight) c.lineTo(cx, tipY + hl * 0.6);
      else { c.lineTo(cx, bendY); c.lineTo(ex - fx * hl * 0.5, ey - fy * hl * 0.5); }
    };
    c.lineJoin = 'round'; c.lineCap = 'butt';
    if (bright) { c.strokeStyle = 'rgba(60,235,130,0.31)'; c.lineWidth = w + 7; shaft(); c.stroke(); }
    c.strokeStyle = color; c.lineWidth = w; shaft(); c.stroke();
    c.fillStyle = color;
    const bx = tipX - fx * hl, by = tipY - fy * hl;
    c.beginPath(); c.moveTo(tipX, tipY); c.lineTo(bx - fy * hw, by + fx * hw); c.lineTo(bx + fy * hw, by - fx * hw); c.closePath(); c.fill();
  },
};

/** Rotating compass rose (N points to true north on the heading-up map). */
const Compass = {
  draw(canvas, headingRad, valid) {
    const dpr = window.devicePixelRatio || 1, S = canvas.clientWidth || 100;
    if (canvas.width !== Math.round(S * dpr)) { canvas.width = Math.round(S * dpr); canvas.height = Math.round(S * dpr); }
    const c = canvas.getContext('2d');
    c.setTransform(dpr * S / 100, 0, 0, dpr * S / 100, 0, 0);
    c.clearRect(0, 0, 100, 100);
    const cx = 50, cy = 50, r = 46.5;
    c.fillStyle = 'rgba(14,18,24,0.75)'; c.beginPath(); c.arc(cx, cy, r, 0, Math.PI * 2); c.fill();
    c.strokeStyle = '#fff'; c.lineWidth = 3; c.stroke();
    const deg = headingRad * 180 / Math.PI;
    c.save(); c.translate(cx, cy); c.rotate(-deg * Math.PI / 180);
    c.font = 'bold ' + (r * 0.34) + 'px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
    const off = r * 0.72;
    c.fillStyle = 'rgb(255,120,120)'; c.fillText('N', 0, -off);
    c.fillStyle = 'rgba(255,255,255,0.82)'; c.fillText('S', 0, off); c.fillText('E', off, 0); c.fillText('W', -off, 0);
    c.restore();
    if (valid) {
      const names = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
      const idx = Math.floor((((deg % 360) + 360 + 22.5) / 45)) % 8;
      c.fillStyle = '#fff'; c.font = 'bold ' + (r * 0.42) + 'px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
      c.fillText(names[idx], cx, cy);
    }
    c.fillStyle = '#fff'; c.beginPath(); c.moveTo(cx, cy - r - 4); c.lineTo(cx + 6, cy - r + 6); c.lineTo(cx - 6, cy - r + 6); c.closePath(); c.fill();
  },
};
