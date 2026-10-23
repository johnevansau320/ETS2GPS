'use strict';
/* Heading-up road map on a canvas: roads, route, waypoints, POIs, next-maneuver marker and the truck.
 * Drag to look around (Recentre returns), pinch or +/- to zoom, long-press to pick a point. */

const MAP_STYLE = { bg: 'rgb(34,40,54)' };

class MapView {
  constructor(canvas) {
    this.canvas = canvas;
    this.map = null;
    this.truckX = 0; this.truckZ = 0; this.bearing = 0; this.hasFix = false;
    this.flagOn = false; this.flagX = 0; this.flagZ = 0; // the finishing flag at the destination
    this.routePts = new Float32Array(0); this.routeEdgePt = new Int32Array(0); this.routeIdx = 0; this.routeLift = new Uint8Array(0); this.planLift = new Uint8Array(0);
    this.stops = []; // [{x,z}]
    this.nextManeuver = null;
    this.onLongPress = null; this.onFreeChanged = null;
    this.userZoom = 1;
    this.free = false; this.freeCx = 0; this.freeCz = 0; this.freeBearing = 0;
    this.showPoiTypes = new Set([POI.FUEL, POI.PARKING, POI.SERVICE, POI.WEIGH, POI.GARAGE, POI.DEALER, POI.FERRY, POI.TRAIN]);
    this.widthsM = [7, 11, 18, 8];
    // road atlas look: steel-blue roads with a darker outline on a slate background
    this.colors = ['rgb(70,96,128)', 'rgb(84,114,150)', 'rgb(236,190,90)', 'rgb(64,88,118)'];
    this.casings = ['rgb(26,32,44)', 'rgb(26,32,44)', 'rgb(120,84,24)', 'rgb(26,32,44)'];
    this.areaColors = ['rgb(52,64,84)', 'rgb(226,190,136)', 'rgb(212,160,92)', 'rgb(112,160,104)'];
    this.bufs = [[], [], [], []];
    this.tilt = 0; this.tiltMax = 52 * Math.PI / 180; this.driving = false; this.speedKmh = 0; this.dragging = false; this.assets = null;
    // plan mode: the map shows a planned trip (north up, any zoom, no truck) instead of following the truck
    this.planMode = false; this.planPts = new Float32Array(0); this.planEdgePt = new Int32Array(0); this.pins = [];
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

  minZoom() { return this.planMode ? 0.004 : 0.35; }
  zoomBy(f) { this.userZoom = Math.min(10, Math.max(this.minZoom(), this.userZoom * f)); }

  showPlan(cx, cz, zoom) {
    this.planMode = true; this.free = true; this.freeBearing = 0; this.freeCx = cx; this.freeCz = cz; this.userZoom = zoom;
  }
  hidePlan() {
    this.planMode = false; this.free = false; this.pins = []; this.planPts = new Float32Array(0); this.planEdgePt = new Int32Array(0); this.planLift = new Uint8Array(0); this.userZoom = 1;
  }

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
    if (!this.hasFix && !this.planMode) return null;
    const s = this.pxPerM(), px = this.free ? this.W / 2 : this.pivotX(), py = this.free ? this.H / 2 : this.pivotY();
    const fl = this.unwarp(sx, sy, px, py, this.ecam(this.H, py));
    if (!fl) return null;
    const b = this.viewBearing, u = (fl[0] - px) / s, v = -(fl[1] - py) / s;
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
        if (pinch0 > 0) this.userZoom = Math.min(10, Math.max(this.minZoom(), zoom0 * dist() / pinch0));
        return;
      }
      if (!moved && Math.hypot(e.offsetX - startX, e.offsetY - startY) < 8) return;
      moved = true; clearTimeout(lp);
      if ((!this.hasFix && !this.planMode) || !this.map) return;
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
          if (!this.planMode && now - lastTap < 320) { this.userZoom = 1; lastTap = 0; } else lastTap = now;
        }
      }
    };
    cv.addEventListener('pointerup', up);
    cv.addEventListener('pointercancel', up);
    cv.addEventListener('contextmenu', (e) => e.preventDefault());
    cv.addEventListener('wheel', (e) => { e.preventDefault(); this.zoomBy(e.deltaY < 0 ? 1.15 : 1 / 1.15); }, { passive: false });
  }

  // --------------------------------------------------------------------- drawing --
  ecam(H, py) { return (py + 0.12 * H) * Math.tan(this.tiltMax); }

  // flat screen point -> tilted screen point and its depth scale
  warp(fx, fy, px, py, E) {
    if (this.tilt < 0.001) return [fx, fy, 1];
    const x0 = fx - px; let y0 = py - fy;
    const sT = Math.sin(this.tilt), cT = Math.cos(this.tilt), lim = -E / sT * 0.8;
    if (y0 < lim) y0 = lim;
    const sc = E / (E + y0 * sT);
    return [px + x0 * sc, py - y0 * cT * sc, sc];
  }

  unwarp(sx, sy, px, py, E) {
    if (this.tilt < 0.001) return [sx, sy];
    const sT = Math.sin(this.tilt), cT = Math.cos(this.tilt), yp = py - sy, den = E * cT - yp * sT;
    if (den <= 1e-6) return null;
    const y0 = yp * E / den, sc = E / (E + y0 * sT);
    return [px + (sx - px) / sc, py - y0];
  }

  draw() {
    const c = this.canvas.getContext('2d');
    const dpr = this.dpr || 1, W = this.W, H = this.H;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    const m = this.map;
    // tilt: eases in above 15 km/h while following the truck, back to flat when slow or looking around
    const now = performance.now(), dt = Math.min(0.1, Math.max(0, (now - (this.lastT || now)) / 1000)); this.lastT = now;
    if (this.speedKmh > 15) this.driving = true; else if (this.speedKmh < 12) this.driving = false;
    const target = this.driving && !this.free && this.hasFix && m && !this.dragging && !this.planMode ? this.tiltMax : 0;
    this.tilt += Math.max(-dt * 1.6, Math.min(dt * 1.6, target - this.tilt));
    const tilted = this.tilt >= 0.001;
    const st = MAP_STYLE;
    c.fillStyle = st.bg; c.fillRect(0, 0, W, H);
    c.textAlign = 'center'; c.font = '16px sans-serif'; c.fillStyle = 'rgba(220,226,236,0.86)';
    if (!m) { c.fillText('Loading the map from your PC…', W / 2, H / 2); return; }
    if (!this.hasFix && !this.planMode) { c.fillText('Waiting for the game…', W / 2, H / 2); return; }
    const s = this.pxPerM(), isFree = this.free, vb = this.viewBearing;
    const px = isFree ? W / 2 : this.pivotX(), py = isFree ? H / 2 : this.pivotY();
    const cs = Math.cos(vb), sn = Math.sin(vb);
    const cx = isFree ? this.freeCx : this.truckX, cz = isFree ? this.freeCz : this.truckZ;
    const E = this.ecam(H, py), yMax = 3.2 * H;
    const proj = (wx, wz) => { const a = wx - cx, b = wz - cz; return this.warp(px + (a * cs + b * sn) * s, py - (a * sn - b * cs) * s, px, py, E); };
    let radius = Math.hypot(Math.max(px, W - px), Math.max(py, H - py)) * 1.0 / s + 20;
    if (tilted) radius = Math.max(radius, yMax / s * 1.05 + 20);
    const zoomFactor = this.userZoom;
    const fs = (sc) => (tilted ? Math.min(1.1, Math.max(0.5, Math.pow(sc, 0.6))) : 1);
    const showLocal = zoomFactor > 0.3, showJunction = zoomFactor > 0.45;
    for (const b of this.bufs) b.length = 0;
    const drawRoads = !(this.planMode && zoomFactor < 0.06);   // zoomed far out while planning: just the route and the towns
    if (drawRoads) m.queryEdges(cx, cz, radius, (e) => {
      const cls = Math.min(3, m.eCls[e]);
      if ((cls === 0 && !showLocal) || (cls === 3 && !showJunction)) return;
      if (m.ePtStart[e + 1] - m.ePtStart[e] < 2) return;
      this.bufs[cls].push(e);
    });
    const BF = [1, 0.72, 0.55, 0.42, 0.3];
    const band = (sc) => (sc >= 0.8 ? 0 : sc >= 0.62 ? 1 : sc >= 0.48 ? 2 : sc >= 0.36 ? 3 : 4);

    // lots, yards and green patches under the roads
    if (zoomFactor > 0.4) {
      for (const a of m.areas) {
        if (a.x1 < cx - radius || a.x0 > cx + radius || a.z1 < cz - radius || a.z0 > cz + radius) continue;
        c.beginPath();
        let ok = true;
        for (let k = 0; k < a.xs.length; k++) {
          const q = proj(a.xs[k], a.zs[k]);
          if (tilted && q[2] < 0.28) { ok = false; break; }
          if (k === 0) c.moveTo(q[0], q[1]); else c.lineTo(q[0], q[1]);
        }
        if (!ok) continue;
        c.closePath(); c.fillStyle = this.areaColors[Math.min(3, a.color)]; c.fill();
      }
    }

    // roads: outline pass, then fill pass; far roads (per depth band) are drawn thinner.
    // Roads on flyovers and bridges are drawn after the rest, raised and with a shadow, so the roads below pass underneath.
    const hasLift = !!(m.eHasLift && m.eHasLift.length);
    const elev = [[], [], [], []];
    if (hasLift) for (let cls = 0; cls < 4; cls++) { const keep = []; for (const e of this.bufs[cls]) (m.eHasLift[e] ? elev[cls] : keep).push(e); this.bufs[cls] = keep; }
    const liftK = tilted ? s * Math.sin(this.tilt) : 0;   // screen px for one metre of height
    const order = [3, 0, 1, 2];
    // mode 0: on the ground, 1: raised deck, 2: its shadow on the ground
    const buildPaths = (lists, mode) => {
      const paths = [[], [], [], []];
      for (const cls of order) {
        const set = paths[cls];
        const get = (bd) => set[bd] || (set[bd] = new Path2D());
        for (const e of lists[cls]) {
          const a = m.ePtStart[e], b = Math.min(m.ePtStart[e + 1], a + 255), n = b - a;
          const qs = [];
          for (let i = 0; i < n; i++) {
            const q = proj(m.ptX[a + i], m.ptZ[a + i]);
            if (mode) {
              const lf = 0.5 * m.ptLift[a + i];
              if (mode === 1) q[1] -= lf * liftK * q[2];
              else if (!tilted) { const o = Math.min(12, lf * s * 0.3); q[0] += o; q[1] += o; }
            }
            qs.push(q);
          }
          if (!tilted) {
            const p = get(0); p.moveTo(qs[0][0], qs[0][1]);
            for (let i = 1; i < n; i++) p.lineTo(qs[i][0], qs[i][1]);
            continue;
          }
          let cur = -1;
          for (let i = 0; i + 1 < n; i++) {
            const sc = (qs[i][2] + qs[i + 1][2]) / 2;
            if (sc < 0.28) { cur = -1; continue; }
            const bd = band(sc), p = get(bd);
            if (bd !== cur) { p.moveTo(qs[i][0], qs[i][1]); cur = bd; }
            p.lineTo(qs[i + 1][0], qs[i + 1][1]);
          }
        }
      }
      return paths;
    };
    c.lineCap = 'round'; c.lineJoin = 'round';
    const wid = (cls, bd) => Math.max(this.widthsM[cls] * s, cls === 2 ? 3.4 : 2.4) * BF[bd];
    const drawRoadSets = (lists, mode) => {
      if (mode === 2) {
        const sh = buildPaths(lists, 2);
        for (const cls of order) sh[cls].forEach((p, bd) => { if (p) { c.strokeStyle = 'rgba(6,8,14,0.41)'; c.lineWidth = wid(cls, bd) + 5; c.stroke(p); } });
        mode = 1;
      }
      const paths = buildPaths(lists, mode);
      for (const cls of order) paths[cls].forEach((p, bd) => { if (p) { c.strokeStyle = this.casings[cls]; c.lineWidth = wid(cls, bd) + 2.6; c.stroke(p); } });
      for (const cls of order) paths[cls].forEach((p, bd) => { if (p) { c.strokeStyle = this.colors[cls]; c.lineWidth = wid(cls, bd); c.stroke(p); } });
    };
    drawRoadSets(this.bufs, 0);

    // route
    const rp0 = this.planMode ? this.planPts : this.routePts, re0 = this.planMode ? this.planEdgePt : this.routeEdgePt;
    let rl = this.planMode ? this.planLift : this.routeLift;
    if (!rl || rl.length !== rp0.length / 2) rl = null;
    let rFirst = 0, rLast = 0, routeLifted = false;
    const rw = Math.max(10 * s, 6);
    if (rp0.length && re0.length) {
      const total = rp0.length / 2;
      rFirst = this.planMode ? 0 : re0[Math.min(Math.max(0, this.routeIdx - 1), re0.length - 1)];
      rLast = this.planMode ? total : Math.min(total, rFirst + 6000);
      if (rl) for (let p = rFirst; p < rLast && !routeLifted; p++) routeLifted = rl[p] > 0;
    }
    // deck false: the route on the ground; deck true: the parts on flyovers / bridges, raised; ghost: the ground route again, faint
    const drawRoute = (deck, ghost) => {
      if (rLast <= rFirst) return;
      const rp = [];
      let cur = -1, lq = null;
      for (let p = rFirst; p < rLast; p++) {
        const q = proj(rp0[p * 2], rp0[p * 2 + 1]);
        if (deck && rl) q[1] -= 0.5 * rl[p] * liftK * q[2];
        if (lq) {
          const segUp = !!rl && (rl[p] > 0 || rl[p - 1] > 0);
          const sc = (lq[2] + q[2]) / 2;
          if (segUp !== deck || sc < 0.28) cur = -1;
          else {
            const bd = tilted ? band(sc) : 0, path = rp[bd] || (rp[bd] = new Path2D());
            if (bd !== cur) { path.moveTo(lq[0], lq[1]); cur = bd; }
            path.lineTo(q[0], q[1]);
          }
        }
        lq = q;
      }
      if (!ghost) rp.forEach((p, bd) => { if (p) { c.strokeStyle = 'rgba(255,255,255,0.92)'; c.lineWidth = (rw + 5) * BF[bd]; c.stroke(p); } });
      rp.forEach((p, bd) => { if (p) { c.strokeStyle = ghost ? 'rgba(36,132,255,0.47)' : 'rgb(36,132,255)'; c.lineWidth = rw * BF[bd]; c.stroke(p); } });
    };
    drawRoute(false, false);
    if (hasLift) {
      drawRoadSets(elev, 2);
      if (routeLifted) { drawRoute(true, false); drawRoute(false, true); }   // the route below shows faintly through the deck above it
    }

    // scaled drawing around a point (labels keep facing the viewer, smaller in the distance)
    const at = (x, y, f, fn) => { if (Math.abs(f - 1) < 0.02) { fn(); return; } c.save(); c.translate(x, y); c.scale(f, f); c.translate(-x, -y); fn(); c.restore(); };
    const pill = (txt, sx, sy, fill, border, fg, size, pad) => {
      c.font = 'bold ' + size + 'px sans-serif'; c.textBaseline = 'middle'; c.textAlign = 'center';
      const w = c.measureText(txt).width + pad * 2, h = size + 6;
      c.beginPath(); if (c.roundRect) c.roundRect(sx - w / 2, sy - h / 2, w, h, 4); else c.rect(sx - w / 2, sy - h / 2, w, h);
      c.fillStyle = fill; c.fill();
      if (border) { c.strokeStyle = border; c.lineWidth = 1.6; c.stroke(); }
      c.fillStyle = fg; c.fillText(txt, sx, sy + 1); c.textBaseline = 'alphabetic';
    };
    const visible = (q, mx, my) => !((tilted && q[2] < 0.3) || q[0] < -mx || q[0] > W + mx || q[1] < -my || q[1] > H + my);

    // finishing flag at the destination (the end of the mapped road can be well short of it)
    if (this.flagOn && !this.planMode) {
      const q = proj(this.flagX, this.flagZ);
      if (visible(q, 80, 80)) at(q[0], q[1], fs(q[2]), () => {
        c.fillStyle = 'rgba(0,0,0,0.35)'; c.beginPath(); c.ellipse(q[0], q[1] + 1, 7, 3, 0, 0, Math.PI * 2); c.fill();
        c.strokeStyle = '#fff'; c.lineWidth = 3.4; c.lineCap = 'round'; c.beginPath(); c.moveTo(q[0], q[1]); c.lineTo(q[0], q[1] - 50); c.stroke();
        const x0 = q[0] + 1.5, y0 = q[1] - 50, cell = 7;
        c.fillStyle = '#fff'; c.fillRect(x0, y0, cell * 5, cell * 4);
        c.fillStyle = '#000';
        for (let ix = 0; ix < 5; ix++) for (let iy = 0; iy < 4; iy++) if ((ix + iy) % 2 === 0) c.fillRect(x0 + ix * cell, y0 + iy * cell, cell, cell);
        c.strokeStyle = '#000'; c.lineWidth = 1.4; c.strokeRect(x0, y0, cell * 5, cell * 4);
      });
    }

    // big area (city) names
    if (true) { // area (city) names stay on at every zoom level
      const size = Math.min(34, Math.max(this.planMode ? 11 : 20, 26 * Math.pow(zoomFactor, 0.35)));
      c.textAlign = 'center'; c.textBaseline = 'middle';
      for (const ci of m.cities) {
        const dx = ci.x - cx, dz = ci.z - cz;
        if (dx * dx + dz * dz > radius * radius) continue;
        const q = proj(ci.x, ci.z);
        if (!visible(q, 120, 30)) continue;
        const name = ci.name.toUpperCase();
        at(q[0], q[1], fs(q[2]), () => {
          c.font = 'bold ' + size + 'px sans-serif';
          c.fillStyle = 'rgba(10,12,20,0.6)'; c.fillText(name, q[0] + 1.5, q[1] + 1.5);
          c.fillStyle = 'rgba(236,224,228,0.9)'; c.fillText(name, q[0], q[1]);
        });
      }
      c.textBaseline = 'alphabetic';
    }
    // border crossings: checkpoint symbol, or the flag of the country being entered
    if (zoomFactor > 0.35) {
      for (const bd of m.borders) {
        const dx = bd.x - cx, dz = bd.z - cz;
        if (dx > radius || dx < -radius || dz > radius || dz < -radius) continue;
        const q = proj(bd.x, bd.z);
        if (!visible(q, 140, 90)) continue;
        if (bd.kind === 0) {
          if (zoomFactor < 0.6) continue;
          at(q[0], q[1], fs(q[2]), () => pill('BORDER', q[0], q[1], '#fff', 'rgb(200,40,40)', 'rgb(200,40,40)', 12, 8));
        } else {
          const img = this.assets && this.assets.flag(bd.cc);
          if (!img) continue;
          at(q[0], q[1], fs(q[2]), () => {
            // the flag (2x) with the name of the country being entered right under it
            c.fillStyle = '#fff'; c.beginPath(); c.arc(q[0], q[1], 42, 0, Math.PI * 2); c.fill();
            c.drawImage(img, q[0] - 38, q[1] - 38, 76, 76);
            pill(countryName(bd.cc), q[0], q[1] + 50, 'rgba(250,250,252,0.95)', '#fff', 'rgb(30,36,48)', 22, 8);
          });
        }
      }
    }
    // road-number shields
    if (zoomFactor > 0.5) {
      for (const rl of m.roadLabels) {
        const dx = rl.x - cx, dz = rl.z - cz;
        if (dx > radius || dx < -radius || dz > radius || dz < -radius) continue;
        const q = proj(rl.x, rl.z);
        if (!visible(q, 40, 20)) continue;
        at(q[0], q[1], fs(q[2]), () => pill(rl.text, q[0], q[1], rl.text[0] === 'E' ? 'rgb(0,128,72)' : 'rgb(0,64,176)', '#fff', '#fff', 17, 6));
      }
    }
    // companies: the logo when the game has one, otherwise the name
    if (zoomFactor > 0.75) {
      for (const co of m.companies) {
        const dx = co.x - cx, dz = co.z - cz;
        if (dx > radius || dx < -radius || dz > radius || dz < -radius) continue;
        const q = proj(co.x, co.z);
        if (!visible(q, 90, 30) || (tilted && q[2] < 0.35)) continue;
        const logo = this.assets && this.assets.logo(co.id);
        if (logo) {
          at(q[0], q[1], fs(q[2]), () => {
            c.fillStyle = 'rgba(250,250,252,0.94)'; c.beginPath();
            if (c.roundRect) c.roundRect(q[0] - 56, q[1] - 16, 112, 32, 5); else c.rect(q[0] - 56, q[1] - 16, 112, 32);
            c.fill(); c.drawImage(logo, q[0] - 52, q[1] - 13, 104, 26);
          });
        } else {
          at(q[0], q[1], fs(q[2]), () => pill(m.companyNames.get(co.id.toLowerCase()) || co.id, q[0], q[1], 'rgba(250,250,252,0.92)', null, 'rgb(30,36,48)', 15, 5));
        }
      }
    }
    // points of interest
    if (zoomFactor > 0.3) {
      const r = zoomFactor > 0.8 ? 24 : 18;
      for (const p of m.pois) {
        if (!this.showPoiTypes.has(p.type)) continue;
        const dx = p.x - cx, dz = p.z - cz;
        if (dx > radius || dx < -radius || dz > radius || dz < -radius) continue;
        const q = proj(p.x, p.z);
        if (!visible(q, r, r)) continue;
        at(q[0], q[1], fs(q[2]), () => {
          drawPoiIcon(c, p.type, q[0], q[1], r);
        });
      }
    }
    // planned trip: start, stops and destination pins
    if (this.planMode) {
      for (const pin of this.pins) {
        const q = proj(pin.x, pin.z);
        if (q[0] < -60 || q[0] > W + 60 || q[1] < -60 || q[1] > H + 60) continue;
        c.fillStyle = pin.color; c.beginPath(); c.arc(q[0], q[1], 20, 0, Math.PI * 2); c.fill();
        c.strokeStyle = '#fff'; c.lineWidth = 3; c.stroke();
        c.fillStyle = '#fff'; c.font = 'bold 22px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(pin.label, q[0], q[1] + 1); c.textBaseline = 'alphabetic';
      }
    }
    // waypoints
    (this.planMode ? [] : this.stops).forEach((sp, i) => {
      const q = proj(sp.x, sp.z);
      if (q[0] < -60 || q[0] > W + 60 || q[1] < -60 || q[1] > H + 60) return;
      at(q[0], q[1], fs(q[2]), () => {
        c.fillStyle = 'rgb(25,118,210)'; c.beginPath(); c.arc(q[0], q[1], 17, 0, Math.PI * 2); c.fill();
        c.strokeStyle = 'rgb(18,92,58)'; c.lineWidth = 3; c.stroke();
        c.fillStyle = '#fff'; c.font = 'bold 20px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(String(i + 1), q[0], q[1] + 1); c.textBaseline = 'alphabetic';
      });
    });
    // next maneuver marker
    const nm = this.planMode ? null : this.nextManeuver;
    if (nm) {
      const q = proj(nm.x, nm.z);
      if (q[0] > -80 && q[0] < W + 80 && q[1] > -80 && q[1] < H + 80) {
        at(q[0], q[1], fs(q[2]), () => {
          const r = 26;
          c.fillStyle = '#fff'; c.beginPath(); c.arc(q[0], q[1], r, 0, Math.PI * 2); c.fill();
          c.strokeStyle = 'rgb(18,92,58)'; c.lineWidth = 3; c.stroke();
          if (!isFree) Glyphs.draw(c, nm.turn, nm.exitNo, nm.angleDeg, q[0], q[1], r * 1.55, 'rgb(18,92,58)');
          else { c.fillStyle = 'rgb(18,92,58)'; c.beginPath(); c.arc(q[0], q[1], r * 0.35, 0, Math.PI * 2); c.fill(); }
        });
      }
    }
    // haze over the far distance
    if (tilted) {
      const sT = Math.sin(this.tilt), cT = Math.cos(this.tilt);
      const yFar = py - yMax * cT * (E / (E + yMax * sT)), fadeTo = Math.max(yFar + 60, H * 0.2);
      const g = c.createLinearGradient(0, 0, 0, fadeTo), a = Math.min(1, this.tilt / this.tiltMax);
      g.addColorStop(0, st.bg); g.addColorStop(0.35, 'rgba(34,40,54,' + a + ')'); g.addColorStop(1, 'rgba(34,40,54,0)');
      c.fillStyle = g; c.fillRect(0, 0, W, fadeTo);
    }
    // truck (flat at the pivot)
    if (this.planMode) return;
    const t = 15;
    c.save(); c.translate(px, py); c.rotate(this.bearing - vb);
    c.beginPath(); c.moveTo(0, -t * 1.2); c.lineTo(t * 0.85, t * 0.85); c.lineTo(0, t * 0.4); c.lineTo(-t * 0.85, t * 0.85); c.closePath();
    c.fillStyle = 'rgb(0,214,120)'; c.fill(); c.strokeStyle = '#fff'; c.lineWidth = 2.5; c.stroke();
    c.restore();
  }
}
