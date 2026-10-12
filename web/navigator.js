'use strict';
/* Follows a route: maneuvers, lane guidance, progress and spoken prompts (port of Navigator.kt). */

const Turn = {
  KEEP_LEFT: 'KEEP_LEFT', KEEP_RIGHT: 'KEEP_RIGHT', BEAR_LEFT: 'BEAR_LEFT', BEAR_RIGHT: 'BEAR_RIGHT',
  TURN_LEFT: 'TURN_LEFT', TURN_RIGHT: 'TURN_RIGHT', UTURN: 'UTURN', ROUNDABOUT: 'ROUNDABOUT', ARRIVE: 'ARRIVE',
};
const OFF_ROUTE_M = 45;

class Navigator {
  constructor(map, router) {
    this.map = map; this.router = router;
    this.distScale = 19;
    this.arriveWord = 'your destination';
    this.clear();
  }

  get active() { return this.route.length > 0; }
  get currentIndex() { return this.idx; }
  get routeLengthM() { return this.cum.length ? this.cum[this.cum.length - 1] : 0; }

  /** Speed the router assumes for the road the truck is on now (m/s), 0 for junction paths. */
  get modelSpeedMps() {
    if (!this.route.length) return 0;
    const e = this.route[Math.min(Math.max(this.idx, 0), this.route.length - 1)] >> 1;
    return this.map.eJid[e] !== 0 ? 0 : this.map.eSpeed[e] / 3.6;
  }

  clear() {
    this.route = new Int32Array(0); this.cum = new Float32Array(0); this.secs = new Float32Array(0);
    this.maneuvers = []; this.laneHints = []; this.stage = [];
    this.idx = 0; this.progress = 0; this.offCount = 0;
  }

  /** remainingM = how much of the first edge was still ahead when the route was computed. */
  setRoute(res, remainingM) {
    const m = this.map;
    this.route = res.edges;
    const n = this.route.length;
    this.cum = new Float32Array(n + 1); this.secs = new Float32Array(n + 1);
    for (let i = 0; i < n; i++) {
      this.cum[i + 1] = this.cum[i] + m.eLen[this.route[i] >> 1];
      this.secs[i + 1] = this.secs[i] + this.router.edgeSeconds(this.route[i]);
    }
    this.idx = 0;
    this.progress = m.eLen[this.route[0] >> 1] - remainingM;
    this.offCount = 0;
    this.maneuvers = this.buildManeuvers();
    this.assignTowards();
    this.stage = this.maneuvers.map((mv) => (mv.atM < this.progress ? 3 : 0)); // nothing behind the start gets announced
  }

  tangent(de, atEnd, win = 6) {
    const m = this.map, n = m.deCount(de);
    if (n < 2) return 0;
    if (atEnd) {
      const px = m.deX(de, n - 1), pz = m.deZ(de, n - 1);
      for (let i = n - 2; i >= 0; i--) {
        const qx = m.deX(de, i), qz = m.deZ(de, i);
        if (Math.hypot(px - qx, pz - qz) >= win || i === 0) return bearingOf(px - qx, pz - qz);
      }
    } else {
      const px = m.deX(de, 0), pz = m.deZ(de, 0);
      for (let i = 1; i < n; i++) {
        const qx = m.deX(de, i), qz = m.deZ(de, i);
        if (Math.hypot(qx - px, qz - pz) >= win || i === n - 1) return bearingOf(qx - px, qz - pz);
      }
    }
    return 0;
  }

  degDiff(a, b) { return angleDiff(a, b) * 180 / Math.PI; }

  buildManeuvers() {
    const m = this.map, r = this.router, route = this.route, cum = this.cum;
    const out = [], hints = [];
    const n = route.length;
    for (let k = 1; k < n - 1; k++) {
      const de = route[k], e = de >> 1, jid = m.eJid[e];
      if (jid === 0) continue;
      const entry = m.deFrom(de), cameFrom = m.deFrom(route[k - 1]);
      const alts = []; // [directed edge, signed turn angle]
      for (let i = 0; i < r.outCount(entry); i++) {
        const j = r.outAt(entry, i);
        if (m.eJid[j >> 1] !== jid || m.deTo(j) === cameFrom) continue;
        alts.push([j, this.degDiff(this.tangent(j, true), this.tangent(j, false))]);
      }
      if (alts.length < 2) continue;
      const chosen = alts.find((a) => a[0] === de);
      if (!chosen) continue;
      const angle = chosen[1];
      let straightest = alts[0];
      for (const a of alts) if (Math.abs(a[1]) < Math.abs(straightest[1])) straightest = a;
      const x = m.deX(de, 0), z = m.deZ(de, 0);
      const mk = (turn, exitNo) => ({ turn, atM: cum[k], exitNo, angleDeg: angle, x, z, towards: null, lanes: null });
      if (m.roundabouts.has(jid)) {
        const sorted = alts.slice().sort((a, b) => b[1] - a[1]);
        const no = sorted.findIndex((a) => a[0] === de) + 1;
        out.push(mk(Turn.ROUNDABOUT, no));
        continue;
      }
      const a = Math.abs(angle), right = angle > 0;
      const before = out.length;
      const laneList = this.lanesFor(alts, de);
      if (a < 22) {
        if (straightest[0] !== de) out.push(mk(right ? Turn.KEEP_RIGHT : Turn.KEEP_LEFT, 0));
      } else if (a < 55) out.push(mk(right ? Turn.BEAR_RIGHT : Turn.BEAR_LEFT, 0));
      else if (a < 135) out.push(mk(right ? Turn.TURN_RIGHT : Turn.TURN_LEFT, 0));
      else out.push(mk(Turn.UTURN, 0));
      if (out.length > before) out[out.length - 1].lanes = laneList;
      // also hint junctions where the route just carries straight on but some lanes would leave it
      if (laneList && (out.length > before || laneList.some((l) => !l.preferred))) hints.push({ atM: cum[k], lanes: laneList });
    }
    const last = route[n - 1], cnt = m.deCount(last);
    out.push({ turn: Turn.ARRIVE, atM: cum[n], exitNo: 0, angleDeg: 0, x: m.deX(last, cnt - 1), z: m.deZ(last, cnt - 1), towards: null, lanes: null });
    this.laneHints = hints;
    return out;
  }

  /** Lane arrows for the approach to junction path de, from the map's lane table (null when unknown). */
  lanesFor(alts, de) {
    const m = this.map;
    const n = m.lanesAtEntry(de >> 1), chosen = m.laneMaskOf(de >> 1);
    if (n < 2 || chosen === 0) return null;
    const mine = alts.find((a) => a[0] === de)[1];
    const out = [];
    for (let i = 0; i < n; i++) {
      const dirs = alts.filter((a) => ((m.laneMaskOf(a[0] >> 1) >> i) & 1) !== 0);
      if (!dirs.length) { out.push({ angleDeg: NaN, preferred: false, altDeg: null }); continue; }
      const pref = ((chosen >> i) & 1) !== 0;
      let main = mine;
      if (!pref) { let b = dirs[0]; for (const d of dirs) if (Math.abs(d[1]) < Math.abs(b[1])) b = d; main = b[1]; }
      let other = null;
      for (const d of dirs) if (Math.abs(d[1] - main) > 20 && (other === null || Math.abs(d[1]) < Math.abs(other))) other = d[1];
      out.push({ angleDeg: main, preferred: pref, altDeg: other });
    }
    return out;
  }

  /** For each maneuver, the first town the route passes after it (within 2 km of the road). */
  assignTowards() {
    const m = this.map, route = this.route, cum = this.cum;
    const nc = m.cities.length;
    const passAt = new Float32Array(nc).fill(3e38), bestD = new Float32Array(nc).fill(3e38);
    let minX = 1e30, maxX = -1e30, minZ = 1e30, maxZ = -1e30;
    for (const de of route) for (let i = 0; i < m.deCount(de); i++) {
      const x = m.deX(de, i), z = m.deZ(de, i);
      if (x < minX) minX = x; if (x > maxX) maxX = x; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
    }
    const cand = [];
    for (let ci = 0; ci < nc; ci++) {
      const c = m.cities[ci];
      if (c.x > minX - 2000 && c.x < maxX + 2000 && c.z > minZ - 2000 && c.z < maxZ + 2000) cand.push(ci);
    }
    for (let k = 0; k < route.length; k++) {
      const de = route[k], n = m.deCount(de), len = m.eLen[de >> 1];
      for (let i = 0; i < n; i += 2) {
        const x = m.deX(de, i), z = m.deZ(de, i);
        for (const ci of cand) {
          const c = m.cities[ci], dx = c.x - x, dz = c.z - z;
          if (dx > 2000 || dx < -2000 || dz > 2000 || dz < -2000) continue;
          const d = Math.hypot(dx, dz);
          if (d < 2000 && d < bestD[ci]) { bestD[ci] = d; passAt[ci] = cum[k] + len * i / Math.max(1, n - 1); }
        }
      }
    }
    for (const mv of this.maneuvers) {
      if (mv.turn === Turn.ARRIVE) continue;
      let best = -1;
      for (const ci of cand) if (passAt[ci] > mv.atM + 250 && (best < 0 || passAt[ci] < passAt[best])) best = ci;
      if (best >= 0) mv.towards = m.cities[best].name;
    }
  }

  project(de, x, z) {
    const m = this.map;
    let best = 1e30, bestAlong = 0, cumLen = 0;
    const n = m.deCount(de);
    for (let i = 0; i < n - 1; i++) {
      const x0 = m.deX(de, i), z0 = m.deZ(de, i), x1 = m.deX(de, i + 1), z1 = m.deZ(de, i + 1);
      const dx = x1 - x0, dz = z1 - z0, l2 = dx * dx + dz * dz, seg = Math.sqrt(l2);
      let t = l2 > 1e-6 ? ((x - x0) * dx + (z - z0) * dz) / l2 : 0;
      t = Math.min(1, Math.max(0, t));
      const d = Math.hypot(x - (x0 + dx * t), z - (z0 + dz * t));
      if (d < best) { best = d; bestAlong = cumLen + seg * t; }
      cumLen += seg;
    }
    return { dist: best, along: bestAlong };
  }

  /** Feed the latest truck position; returns guidance for this instant. */
  update(x, z, speedMps) {
    const empty = { distToNextM: 0, next: null, following: null, remainingM: 0, remainingS: 0, offRoute: false, arrived: false, speech: null, lanes: null, lanesDistM: 0 };
    if (!this.route.length) return empty;
    const route = this.route, cum = this.cum, secs = this.secs, m = this.map;
    let bestK = this.idx, bestD = 1e30, bestAlong = 0;
    for (let k = Math.max(0, this.idx - 1); k <= Math.min(route.length - 1, this.idx + 10); k++) {
      const p = this.project(route[k], x, z);
      const score = p.dist + Math.abs(k - this.idx) * 0.5;
      if (score < bestD) { bestD = score; bestK = k; bestAlong = p.along; }
    }
    const rawDist = this.project(route[bestK], x, z).dist;
    if (rawDist > OFF_ROUTE_M) this.offCount++; else this.offCount = 0;
    const offRoute = this.offCount >= 8;
    if (rawDist <= OFF_ROUTE_M) {
      this.idx = bestK;
      const p = cum[this.idx] + bestAlong;
      this.progress = p >= this.progress - 5 ? Math.max(this.progress, p) : p; // ignore tiny backward jitter
    }
    const progress = this.progress;
    const remaining = Math.max(0, cum[route.length] - progress);
    const arrived = remaining < 25;
    const remS = Math.max(0, secs[route.length] - (secs[this.idx] + (progress - cum[this.idx]) / m.eLen[route[this.idx] >> 1] * (secs[this.idx + 1] - secs[this.idx])));
    let ni = -1;
    for (let i = 0; i < this.maneuvers.length; i++) if (this.maneuvers[i].atM > progress - 12) { ni = i; break; }
    const next = ni >= 0 ? this.maneuvers[ni] : null;
    const following = ni >= 0 && ni + 1 < this.maneuvers.length ? this.maneuvers[ni + 1] : null;
    const toNext = next ? Math.max(0, next.atM - progress) : 0;
    let speech = null;
    if (next && !offRoute) {
      const tau = Math.max(speedMps, 4);
      const d1 = Math.max(tau * 26, 300), d2 = Math.max(tau * 10, 110), d3 = Math.max(tau * 3.5, 35);
      const want = toNext <= d3 ? 3 : toNext <= d2 ? 2 : toNext <= d1 ? 1 : 0;
      if (want > this.stage[ni]) { this.stage[ni] = want; speech = this.phrase(next, following, toNext, want); }
    }
    const hint = this.laneHints.find((h) => h.atM > progress - 12);
    return {
      distToNextM: toNext, next, following, remainingM: remaining, remainingS: remS, offRoute, arrived, speech,
      lanes: hint ? hint.lanes : null, lanesDistM: hint ? Math.max(0, hint.atM - progress) : 0,
    };
  }

  phrase(mv, then, dist, stage) {
    const base = this.instruction(mv);
    const tail = (then && then.turn !== Turn.ARRIVE && then.atM - mv.atM < 320 && mv.turn !== Turn.ARRIVE)
      ? ', then ' + this.instruction(then) : '';
    if (mv.turn === Turn.ARRIVE) {
      return stage >= 3 ? 'You have arrived at ' + this.arriveWord : 'In ' + this.spokenDistance(dist) + ', you will arrive at ' + this.arriveWord;
    }
    if (stage >= 3) return base.charAt(0).toUpperCase() + base.slice(1) + tail;
    return 'In ' + this.spokenDistance(dist) + ', ' + base + (mv.towards ? ' towards ' + mv.towards : '') + tail;
  }

  instruction(mv) {
    switch (mv.turn) {
      case Turn.KEEP_LEFT: return 'keep left';
      case Turn.KEEP_RIGHT: return 'keep right';
      case Turn.BEAR_LEFT: return 'bear left';
      case Turn.BEAR_RIGHT: return 'bear right';
      case Turn.TURN_LEFT: return 'turn left';
      case Turn.TURN_RIGHT: return 'turn right';
      case Turn.UTURN: return 'make a U-turn';
      case Turn.ROUNDABOUT: return 'at the roundabout, take the ' + this.ordinal(mv.exitNo) + ' exit';
      default: return 'arrive at ' + this.arriveWord;
    }
  }

  ordinal(n) { return ['', 'first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth'][n] || n + 'th'; }

  spokenDistance(worldM) {
    const m = worldM * this.distScale;
    if (m < 950) return Math.max(50, Math.round(m / 50) * 50) + ' meters';
    if (m < 10000) { const half = Math.round(m / 500) / 2; return half + ' kilometers'; }
    return Math.round(m / 1000) + ' kilometers';
  }

  shortDistance(worldM) {
    const m = worldM * this.distScale;
    if (m < 1000) return Math.max(10, Math.round(m / 10) * 10) + ' m';
    if (m < 10000) return (m / 1000).toFixed(1) + ' km';
    return Math.round(m / 1000) + ' km';
  }
}
