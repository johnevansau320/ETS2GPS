'use strict';
/* Directed-edge A* over the road network, snapping and the main-network filter (port of Router.kt). */

const ROUTE_MODES = [
  { key: 'HEAVY', label: 'Heavy transport', short: 'Heavy', factors: [2.0, 1.3, 1.0, 0.8], junctionExtra: 4,
    help: 'Heavy transport (default): motorways and major roads, avoids small roads and awkward junctions' },
  { key: 'FASTEST', label: 'Fastest', short: 'Fast', factors: [1, 1, 1, 1], junctionExtra: 0,
    help: 'Fastest: shortest travel time on any road' },
  { key: 'TOURIST', label: 'Tourist', short: 'Tour', factors: [0.8, 0.8, 1.6, 2.8], junctionExtra: 0,
    help: 'Tourist: prefers smaller, scenic roads and avoids motorways' },
];
const JUNCTION_PENALTY_S = 3;
const HIDDEN_FACTOR = 14;

function angleDiff(a, b) { // smallest signed difference a - b in (-pi, pi]
  let d = (a - b) % (2 * Math.PI);
  if (d > Math.PI) d -= 2 * Math.PI;
  if (d <= -Math.PI) d += 2 * Math.PI;
  return d;
}
function bearingOf(dx, dz) { return Math.atan2(dx, -dz); }

class Heap {
  constructor() { this.pri = new Float64Array(1024); this.vals = new Int32Array(1024); this.size = 0; }
  push(p, v) {
    if (this.size === this.pri.length) {
      const np = new Float64Array(this.size * 2); np.set(this.pri); this.pri = np;
      const nv = new Int32Array(this.size * 2); nv.set(this.vals); this.vals = nv;
    }
    const pri = this.pri, vals = this.vals;
    let i = this.size++;
    while (i > 0) {
      const parent = (i - 1) >> 1;
      if (pri[parent] <= p) break;
      pri[i] = pri[parent]; vals[i] = vals[parent]; i = parent;
    }
    pri[i] = p; vals[i] = v;
  }
  pop() {
    const pri = this.pri, vals = this.vals;
    const top = vals[0];
    this.size--;
    if (this.size > 0) {
      const p = pri[this.size], v = vals[this.size];
      let i = 0;
      for (;;) {
        let c = i * 2 + 1;
        if (c >= this.size) break;
        if (c + 1 < this.size && pri[c + 1] < pri[c]) c++;
        if (pri[c] >= p) break;
        pri[i] = pri[c]; vals[i] = vals[c]; i = c;
      }
      pri[i] = p; vals[i] = v;
    }
    return top;
  }
}

class Router {
  constructor(map) {
    this.map = map;
    const n = map.nodeCount, ec = map.edgeCount;
    this.outStart = new Int32Array(n + 1);
    const cnt = new Int32Array(n + 1);
    for (let e = 0; e < ec; e++) { cnt[map.eFrom[e] + 1]++; if (map.isTwoWay(e)) cnt[map.eTo[e] + 1]++; }
    for (let i = 1; i <= n; i++) cnt[i] += cnt[i - 1];
    this.outStart.set(cnt);
    this.outList = new Int32Array(cnt[n]);
    const fill = new Int32Array(n);
    for (let e = 0; e < ec; e++) {
      const a = map.eFrom[e];
      this.outList[this.outStart[a] + fill[a]++] = e * 2;
      if (map.isTwoWay(e)) { const b = map.eTo[e]; this.outList[this.outStart[b] + fill[b]++] = e * 2 + 1; }
    }
    this.inMain = new Uint8Array(n);
    this.edgeW = new Float32Array(ec);
    this.blocked = new Uint8Array(ec);
    this.minFactor = 1; this.junctionExtra = 0;
    this.gStamp = new Int32Array(ec * 2); this.gVal = new Float32Array(ec * 2);
    this.prev = new Int32Array(ec * 2); this.closed = new Int32Array(ec * 2);
    this.gen = 0;
    this.avoidFerries = false; // never use ferries or car trains
    this._mode = ROUTE_MODES[0];
    this.computeMainComponent();
    this.rebuildCosts();
  }

  get mode() { return this._mode; }
  set mode(v) { this._mode = v; this.rebuildCosts(); }

  rebuildCosts() {
    const f = this._mode.factors, m = this.map;
    let mn = 1;
    for (let e = 0; e < m.edgeCount; e++) {
      let w = (m.eJid[e] !== 0 || m.isFerry(e)) ? 1 : f[Math.min(3, m.eGrade[e])];
      if (m.isHidden(e)) w *= HIDDEN_FACTOR; // invisible roads: only when there is no sensible alternative
      this.edgeW[e] = w;
      if (w < mn) mn = w;
    }
    this.minFactor = mn;
    this.junctionExtra = this._mode.junctionExtra;
  }

  /** Marks every road within ~25 m of each {x,z} point as forbidden for routing. */
  setAvoid(points) {
    const m = this.map;
    this.blocked.fill(0);
    for (const { x, z } of points) {
      m.queryEdges(x, z, 30, (e) => {
        if (m.eJid[e] !== 0 || m.isFerry(e)) return;
        for (let p = m.ePtStart[e]; p < m.ePtStart[e + 1] - 1; p++) {
          const x0 = m.ptX[p], z0 = m.ptZ[p], dx = m.ptX[p + 1] - x0, dz = m.ptZ[p + 1] - z0;
          const l2 = dx * dx + dz * dz;
          const t = l2 > 1e-6 ? Math.min(1, Math.max(0, ((x - x0) * dx + (z - z0) * dz) / l2)) : 0;
          if (Math.hypot(x - (x0 + dx * t), z - (z0 + dz * t)) < 25) { this.blocked[e] = 1; break; }
        }
      });
    }
  }

  edgeSeconds(de) {
    const e = de >> 1, m = this.map;
    const v = Math.max(m.eSpeed[e], 10) / 3.6;
    return m.eLen[e] / v + (m.eJid[e] !== 0 ? JUNCTION_PENALTY_S : 0);
  }
  edgeCost(de) {
    const e = de >> 1;
    return this.edgeSeconds(de) * this.edgeW[e] + (this.map.eJid[e] !== 0 ? this.junctionExtra : 0);
  }
  outCount(node) { return this.outStart[node + 1] - this.outStart[node]; }
  outAt(node, i) { return this.outList[this.outStart[node] + i]; }

  /** Largest strongly connected component (iterative Kosaraju). */
  computeMainComponent() {
    const m = this.map, n = m.nodeCount, ec = m.edgeCount;
    const rStart = new Int32Array(n + 1);
    for (let e = 0; e < ec; e++) { rStart[m.eTo[e] + 1]++; if (m.isTwoWay(e)) rStart[m.eFrom[e] + 1]++; }
    for (let i = 1; i <= n; i++) rStart[i] += rStart[i - 1];
    const rList = new Int32Array(rStart[n]);
    const rFill = new Int32Array(n);
    for (let e = 0; e < ec; e++) {
      const a = m.eFrom[e], b = m.eTo[e];
      rList[rStart[b] + rFill[b]++] = a;
      if (m.isTwoWay(e)) rList[rStart[a] + rFill[a]++] = b;
    }
    const seen = new Uint8Array(n), order = new Int32Array(n);
    let oc = 0;
    const stackNode = new Int32Array(n), stackIdx = new Int32Array(n);
    for (let s = 0; s < n; s++) {
      if (seen[s] || this.outCount(s) === 0) continue;
      let sp = 1; stackNode[0] = s; stackIdx[0] = 0; seen[s] = 1;
      while (sp > 0) {
        const u = stackNode[sp - 1], i = stackIdx[sp - 1];
        if (i < this.outCount(u)) {
          stackIdx[sp - 1] = i + 1;
          const v = m.deTo(this.outAt(u, i));
          if (!seen[v]) { seen[v] = 1; stackNode[sp] = v; stackIdx[sp] = 0; sp++; }
        } else { order[oc++] = u; sp--; }
      }
    }
    const comp = new Int32Array(n).fill(-1);
    let best = 0, bestId = -1, cid = 0;
    const stack = new Int32Array(n);
    for (let k = oc - 1; k >= 0; k--) {
      const s = order[k];
      if (comp[s] !== -1) continue;
      comp[s] = cid;
      let sp = 0; stack[sp++] = s;
      let cntc = 0;
      while (sp > 0) {
        const u = stack[--sp]; cntc++;
        for (let j = rStart[u]; j < rStart[u + 1]; j++) {
          const v = rList[j];
          if (comp[v] === -1) { comp[v] = cid; stack[sp++] = v; }
        }
      }
      if (cntc > best) { best = cntc; bestId = cid; }
      cid++;
    }
    for (let i = 0; i < n; i++) this.inMain[i] = comp[i] === bestId ? 1 : 0;
  }

  /** Closest drivable position to (x, z); with a heading (radians) the direction of travel is matched. */
  snap(x, z, headingRad, radius = 60) {
    const m = this.map;
    let best = null, bestScore = Infinity;
    m.queryEdges(x, z, radius, (e) => {
      const a = m.ePtStart[e], b = m.ePtStart[e + 1];
      let cum = 0;
      for (let p = a; p < b - 1; p++) {
        const x0 = m.ptX[p], z0 = m.ptZ[p], x1 = m.ptX[p + 1], z1 = m.ptZ[p + 1];
        const dx = x1 - x0, dz = z1 - z0, l2 = dx * dx + dz * dz, segLen = Math.sqrt(l2);
        let t = l2 > 1e-6 ? ((x - x0) * dx + (z - z0) * dz) / l2 : 0;
        t = Math.min(1, Math.max(0, t));
        const d = Math.hypot(x - (x0 + dx * t), z - (z0 + dz * t));
        if (d <= radius) {
          const bearing = Math.atan2(dx, -dz);
          for (let dir = 0; dir < 2; dir++) {
            if (dir === 1 && !m.isTwoWay(e)) continue;
            let penalty = 0;
            if (headingRad !== null && headingRad !== undefined) {
              const br = dir === 0 ? bearing : bearing + Math.PI;
              const diff = Math.abs(angleDiff(br, headingRad));
              penalty = diff > 1.9 ? 1000 : diff * 12;
            }
            const score = d + penalty;
            if (score < bestScore) {
              bestScore = score;
              const alongFwd = cum + segLen * t, total = m.eLen[e];
              best = dir === 0 ? { de: e * 2, dist: d, along: alongFwd, seg: p - a }
                : { de: e * 2 + 1, dist: d, along: Math.max(0, total - alongFwd), seg: Math.max(0, b - p - 2) };
            }
          }
        }
        cum += segLen;
      }
    });
    return best;
  }

  /** Nearest node in the main network, searching outwards (prefers visible, non-avoided roads). */
  nearestMainNode(x, z) {
    const m = this.map;
    let r = 300;
    while (r <= 20000) {
      let best = -1, bestD = Infinity;
      m.queryEdges(x, z, r, (e) => {
        for (const n of [m.eFrom[e], m.eTo[e]]) {
          if (!this.inMain[n]) continue;
          const pen = this.blocked[e] ? 600 : m.isHidden(e) ? 150 : 0;
          const d = Math.hypot(m.nodeX[n] - x, m.nodeZ[n] - z) + pen;
          if (d < bestD) { bestD = d; best = n; }
        }
      });
      if (best >= 0 && bestD <= r) return best;
      r *= 3;
    }
    return -1;
  }

  /** Cheapest route that finishes directed edge startDe (remainingM left of it) and ends at destNode. */
  route(startDe, remainingM, destNode) {
    const m = this.map;
    const gen = ++this.gen;
    const dx = m.nodeX[destNode], dz = m.nodeZ[destNode];
    const vmax = 110 / 3.6 / this.minFactor;
    const heap = new Heap();
    const startE = startDe >> 1;
    const startCost = this.edgeCost(startDe) * Math.min(1, Math.max(0, remainingM / m.eLen[startE]));
    const h = (node) => { const ax = m.nodeX[node] - dx, az = m.nodeZ[node] - dz; return Math.sqrt(ax * ax + az * az) / vmax; };
    this.gStamp[startDe] = gen; this.gVal[startDe] = startCost; this.prev[startDe] = -1;
    heap.push(startCost + h(m.deTo(startDe)), startDe);
    let goal = -1, pops = 0;
    while (heap.size > 0) {
      const cur = heap.pop();
      if (this.closed[cur] === gen) continue;
      this.closed[cur] = gen;
      if (++pops > 6000000) break;
      const node = m.deTo(cur);
      if (node === destNode) { goal = cur; break; }
      const g = this.gVal[cur], curE = cur >> 1;
      for (let i = this.outStart[node]; i < this.outStart[node + 1]; i++) {
        const nx = this.outList[i];
        if ((nx >> 1) === curE) continue; // no U-turn on the same road
        if (this.blocked[nx >> 1]) continue;
        if (this.avoidFerries && m.isFerry(nx >> 1)) continue;
        if (this.closed[nx] === gen) continue;
        const ng = g + this.edgeCost(nx);
        if (this.gStamp[nx] !== gen || ng < this.gVal[nx]) {
          this.gStamp[nx] = gen; this.gVal[nx] = ng; this.prev[nx] = cur;
          heap.push(ng + h(m.deTo(nx)), nx);
        }
      }
    }
    if (goal < 0) return null;
    const list = [];
    for (let c = goal; c !== -1; c = this.prev[c]) list.push(c);
    list.reverse();
    let len = remainingM;
    for (let k = 1; k < list.length; k++) len += m.eLen[list[k] >> 1];
    return { edges: Int32Array.from(list), lengthM: len, timeS: this.gVal[goal] };
  }
}
