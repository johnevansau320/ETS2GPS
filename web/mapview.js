'use strict';
/* Heading-up road map on a canvas: roads, route, waypoints, POIs, next-maneuver marker and the truck.
 * Drag to look around (Recentre returns), pinch or +/- to zoom, long-press to pick a point. */

class MapView {
  constructor(canvas) {
    this.canvas = canvas;
    this.map = null;
    this.truckX = 0; this.truckZ = 0; this.bearing = 0; this.hasFix = false;
    this.routePts = new Float32Array(0); this.routeEdgePt = new Int32Array(0); this.routeIdx = 0;
    this.stops = []; // [{x,z}]
    this.nextManeuver = null;
    this.onLongPress = null; this.onFreeChanged = null;
    this.userZoom = 1;
    this.free = false; this.freeCx = 0; this.freeCz = 0; this.freeBearing = 0;
    this.showPoiTypes = new Set([POI.FUEL, POI.PARKING, POI.SERVICE, POI.WEIGH]);
    this.widthsM = [7, 11, 18, 8];
    this.colors = ['rgb(70,82,98)', 'rgb(104,118,138)', 'rgb(222,178,84)', 'rgb(82,94,112)'];
    this.poiColors = { 1: '#e67814', 3: '#286edc', 2: '#788291', 4: '#965abe', 5: '#be3c3c', 6: '#3c8c78', 7: '#a08c28', 8: '#5a5aaa' };
    this.poiLetters = { 1: 'F', 3: 'P', 2: 'S', 4: 'W', 5: 'D', 6: 'G', 7: 'R', 8: 'H' };
    this.bufs = [[], [], [], []];
    this.W = 0; this.H = 0;
    this.pointers = new Map();
    this.attach();
  }

  get viewBearing() { return this.free ? this.freeBearing : this.bearing; }
  pxPerM() { return this.userZoom * 0.55; }
  pivotX() { return this.W * 0.5; }
  pivotY() { return this.W > this.H ? this.H * 0.68 : this.H * 0.72; }

  resize() {
    const dpr = window.devicePixelRatio || 1;
    this.W = this.canvas.clientWidth; this.H = this.canvas.clientHeight;
    this.canvas.width = Math.round(this.W * dpr); this.canvas.height = Math.round(this.H * dpr);
    this.dpr = dpr;
  }

  zoomBy(f) { this.userZoom = Math.min(4, Math.max(0.35, this.userZoom * f)); }

  enterFree() {
    const s = this.pxPerM(), ahead = -(this.H / 2 - this.pivotY()) / s;
    this.freeBearing = this.bearing;
    this.freeCx = this.truckX + ahead * Math.sin(this.bearing);
    this.freeCz = this.truckZ - ahead * Math.cos(this.bearing);
    this.free = true;
    if (this.onFreeChanged) this.onFreeChanged(true);
  }
  recentre() {
    if (!this.free) return;
    this.free = false;
    if (this.onFreeChanged) this.onFreeChanged(false);
  }

  toWorld(sx, sy) {
    if (!this.hasFix) return null;
    const s = this.pxPerM(), px = this.free ? this.W / 2 : this.pivotX(), py = this.free ? this.H / 2 : this.pivotY();
    const b = this.viewBearing, u = (sx - px) / s, v = -(sy - py) / s;
    const cx = this.free ? this.freeCx : this.truckX, cz = this.free ? this.freeCz : this.truckZ;
    return { x: cx + u * Math.cos(b) + v * Math.sin(b), z: cz + u * Math.sin(b) - v * Math.cos(b) };
  }

  // ----------------------------------------------------------------------- touch --
  attach() {
    const cv = this.canvas;
    let lp = null, moved = false, startX = 0, startY = 0, lastTap = 0, pinch0 = 0, zoom0 = 1;
    const dist = () => { const p = [...this.pointers.values()]; return Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y); };
    cv.addEventListener('pointerdown', (e) => {
      cv.setPointerCapture(e.pointerId);
      this.pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (this.pointers.size === 1) {
        moved = false; startX = e.offsetX; startY = e.offsetY;
        clearTimeout(lp);
        lp = setTimeout(() => {
          if (!moved && this.pointers.size === 1 && this.onLongPress) { const w = this.toWorld(startX, startY); if (w) this.onLongPress(w.x, w.z); moved = true; }
        }, 600);
      } else if (this.pointers.size === 2) { clearTimeout(lp); moved = true; pinch0 = dist(); zoom0 = this.userZoom; }
    });
    cv.addEventListener('pointermove', (e) => {
      const p = this.pointers.get(e.pointerId);
      if (!p) return;
      const dx = e.offsetX - p.x, dy = e.offsetY - p.y;
      p.x = e.offsetX; p.y = e.offsetY;
      if (this.pointers.size === 2) {
        if (pinch0 > 0) this.userZoom = Math.min(4, Math.max(0.35, zoom0 * dist() / pinch0));
        return;
      }
      if (!moved && Math.hypot(e.offsetX - startX, e.offsetY - startY) < 8) return;
      moved = true; clearTimeout(lp);
      if (!this.hasFix || !this.map) return;
      if (!this.free) this.enterFree();
      const s = this.pxPerM(), b = this.freeBearing, u = -dx / s, v = dy / s;
      this.freeCx += u * Math.cos(b) + v * Math.sin(b);
      this.freeCz += u * Math.sin(b) - v * Math.cos(b);
    });
    const up = (e) => {
      const had = this.pointers.has(e.pointerId);
      this.pointers.delete(e.pointerId);
      if (this.pointers.size === 0) {
        clearTimeout(lp);
        if (had && !moved) {
          const now = Date.now();
          if (now - lastTap < 320) { this.userZoom = 1; lastTap = 0; } else lastTap = now;
        }
      }
    };
    cv.addEventListener('pointerup', up);
    cv.addEventListener('pointercancel', up);
    cv.addEventListener('contextmenu', (e) => e.preventDefault());
    cv.addEventListener('wheel', (e) => { e.preventDefault(); this.zoomBy(e.deltaY < 0 ? 1.15 : 1 / 1.15); }, { passive: false });
  }

  // --------------------------------------------------------------------- drawing --
  draw() {
    const c = this.canvas.getContext('2d');
    const dpr = this.dpr || 1, W = this.W, H = this.H;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.fillStyle = 'rgb(16,21,28)'; c.fillRect(0, 0, W, H);
    const m = this.map;
    c.textAlign = 'center'; c.font = '16px sans-serif'; c.fillStyle = 'rgba(220,226,236,0.86)';
    if (!m) { c.fillText('Loading the map from your PC…', W / 2, H / 2); return; }
    if (!this.hasFix) { c.fillText('Waiting for the game…', W / 2, H / 2); return; }
    const s = this.pxPerM(), isFree = this.free, vb = this.viewBearing;
    const px = isFree ? W / 2 : this.pivotX(), py = isFree ? H / 2 : this.pivotY();
    const cs = Math.cos(vb), sn = Math.sin(vb);
    const cx = isFree ? this.freeCx : this.truckX, cz = isFree ? this.freeCz : this.truckZ;
    const proj = (wx, wz) => { const a = wx - cx, b = wz - cz; return [px + (a * cs + b * sn) * s, py - (a * sn - b * cs) * s]; };
    const radius = Math.hypot(Math.max(px, W - px), Math.max(py, H - py)) / s + 20;
    const zoomFactor = this.userZoom;
    const showLocal = zoomFactor > 0.3, showJunction = zoomFactor > 0.45;
    for (const b of this.bufs) b.length = 0;
    m.queryEdges(cx, cz, radius, (e) => {
      const cls = Math.min(3, m.eCls[e]);
      if ((cls === 0 && !showLocal) || (cls === 3 && !showJunction)) return;
      if (m.ePtStart[e + 1] - m.ePtStart[e] < 2) return;
      this.bufs[cls].push(e);
    });
    c.save();
    c.translate(px, py); c.rotate(-vb); c.scale(s, s); c.translate(-cx, -cz);
    c.lineCap = 'round'; c.lineJoin = 'round';
    for (const cls of [3, 0, 1, 2]) {
      const list = this.bufs[cls];
      if (!list.length) continue;
      c.strokeStyle = this.colors[cls];
      c.lineWidth = Math.max(this.widthsM[cls] * s, cls === 2 ? 3.2 : 2.2) / s;
      c.beginPath();
      for (const e of list) {
        const a = m.ePtStart[e], b = m.ePtStart[e + 1];
        c.moveTo(m.ptX[a], m.ptZ[a]);
        for (let p = a + 1; p < b; p++) c.lineTo(m.ptX[p], m.ptZ[p]);
      }
      c.stroke();
    }
    // route
    if (this.routePts.length && this.routeEdgePt.length) {
      const total = this.routePts.length / 2;
      const first = this.routeEdgePt[Math.min(Math.max(0, this.routeIdx - 1), this.routeEdgePt.length - 1)];
      const last = Math.min(total, first + 6000);
      c.beginPath();
      for (let p = first; p < last; p++) { if (p === first) c.moveTo(this.routePts[p * 2], this.routePts[p * 2 + 1]); else c.lineTo(this.routePts[p * 2], this.routePts[p * 2 + 1]); }
      const w = Math.max(10 * s, 6);
      c.strokeStyle = 'rgba(255,255,255,0.92)'; c.lineWidth = (w + 5) / s; c.stroke();
      c.strokeStyle = 'rgb(36,132,255)'; c.lineWidth = w / s; c.stroke();
    }
    c.restore();
    c.setTransform(dpr, 0, 0, dpr, 0, 0);

    // city names
    if (zoomFactor < 0.9) {
      c.font = '13px sans-serif'; c.fillStyle = 'rgba(220,226,236,0.9)'; c.shadowColor = '#000'; c.shadowBlur = 3;
      for (const ci of m.cities) {
        const dx = ci.x - cx, dz = ci.z - cz;
        if (dx * dx + dz * dz > radius * radius) continue;
        const [sx, sy] = proj(ci.x, ci.z);
        if (sx < -50 || sx > W + 50 || sy < -20 || sy > H + 20) continue;
        c.fillText(ci.name, sx, sy);
      }
      c.shadowBlur = 0;
    }
    // points of interest
    if (zoomFactor > 0.3) {
      const r = zoomFactor > 0.8 ? 24 : 18;
      c.font = 'bold ' + (r * 1.15) + 'px sans-serif'; c.textBaseline = 'middle';
      for (const p of m.pois) {
        if (!this.showPoiTypes.has(p.type)) continue;
        const dx = p.x - cx, dz = p.z - cz;
        if (dx > radius || dx < -radius || dz > radius || dz < -radius) continue;
        const [sx, sy] = proj(p.x, p.z);
        if (sx < -r || sx > W + r || sy < -r || sy > H + r) continue;
        c.fillStyle = this.poiColors[p.type] || '#888';
        c.beginPath(); c.arc(sx, sy, r, 0, Math.PI * 2); c.fill();
        c.fillStyle = '#fff'; c.fillText(this.poiLetters[p.type] || '?', sx, sy + 1);
      }
      c.textBaseline = 'alphabetic';
    }
    // waypoints
    this.stops.forEach((st, i) => {
      const [sx, sy] = proj(st.x, st.z);
      if (sx < -60 || sx > W + 60 || sy < -60 || sy > H + 60) return;
      c.fillStyle = 'rgb(25,118,210)'; c.beginPath(); c.arc(sx, sy, 17, 0, Math.PI * 2); c.fill();
      c.strokeStyle = 'rgb(18,92,58)'; c.lineWidth = 3; c.stroke();
      c.fillStyle = '#fff'; c.font = 'bold 20px sans-serif'; c.textBaseline = 'middle'; c.fillText(String(i + 1), sx, sy + 1); c.textBaseline = 'alphabetic';
    });
    // next maneuver marker
    const nm = this.nextManeuver;
    if (nm) {
      const [sx, sy] = proj(nm.x, nm.z);
      if (sx > -80 && sx < W + 80 && sy > -80 && sy < H + 80) {
        const r = 26;
        c.fillStyle = '#fff'; c.beginPath(); c.arc(sx, sy, r, 0, Math.PI * 2); c.fill();
        c.strokeStyle = 'rgb(18,92,58)'; c.lineWidth = 3; c.stroke();
        if (!isFree) Glyphs.draw(c, nm.turn, nm.exitNo, nm.angleDeg, sx, sy, r * 1.55, 'rgb(18,92,58)');
        else { c.fillStyle = 'rgb(18,92,58)'; c.beginPath(); c.arc(sx, sy, r * 0.35, 0, Math.PI * 2); c.fill(); }
      }
    }
    // truck
    const [tx, ty] = proj(this.truckX, this.truckZ);
    const t = 15;
    c.save(); c.translate(tx, ty); c.rotate(this.bearing - vb);
    c.beginPath(); c.moveTo(0, -t * 1.2); c.lineTo(t * 0.85, t * 0.85); c.lineTo(0, t * 0.4); c.lineTo(-t * 0.85, t * 0.85); c.closePath();
    c.fillStyle = 'rgb(0,214,120)'; c.fill(); c.strokeStyle = '#fff'; c.lineWidth = 2.5; c.stroke();
    c.restore();
  }
}
