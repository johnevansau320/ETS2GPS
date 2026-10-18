'use strict';
/* Road network from companion/build_map.py (etsmap.bin): loader, spatial grid and lookups.
 * World units are metres in the game's coordinate system (x east, z south). */

const POI = { FUEL: 1, SERVICE: 2, PARKING: 3, WEIGH: 4, DEALER: 5, GARAGE: 6, RECRUIT: 7, HOTEL: 8, FERRY: 9, TRAIN: 10 };
const POI_LABEL = {
  1: 'Fuel station', 2: 'Service centre', 3: 'Rest area / parking', 4: 'Weigh station',
  5: 'Truck dealer', 6: 'Garage', 7: 'Recruitment agency', 8: 'Hotel', 9: 'Ferry terminal', 10: 'Car train terminal',
};

function normalizeName(s) {
  return s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();
}

class MapData {
  static parse(raw) {
    const dv = new DataView(raw);
    const u8 = new Uint8Array(raw);
    let o = 0;
    const magic = String.fromCharCode(u8[0], u8[1], u8[2], u8[3]);
    if (magic !== 'ETSM') throw new Error('not an ETS2 GPS map file');
    const ver = dv.getUint16(4, true);
    if (ver < 2 || ver > 9) throw new Error('unsupported map version ' + ver + ' (rebuild the map or update the app)');
    const m = new MapData();
    m.ver = ver;
    m.minX = dv.getFloat32(8, true); m.minZ = dv.getFloat32(12, true);
    m.maxX = dv.getFloat32(16, true); m.maxZ = dv.getFloat32(20, true);
    o = 24;
    const nn = dv.getUint32(o, true); o += 4;
    m.nodeX = new Float32Array(nn); m.nodeZ = new Float32Array(nn);
    for (let i = 0; i < nn; i++) { m.nodeX[i] = dv.getFloat32(o, true); m.nodeZ[i] = dv.getFloat32(o + 4, true); o += 8; }
    const jn = dv.getUint32(o, true); o += 4;
    m.roundabouts = new Set();
    for (let i = 0; i < jn; i++) {
      const jid = dv.getUint32(o, true);
      if (u8[o + 5] !== 0) m.roundabouts.add(jid);
      o += 6;
    }
    const en = dv.getUint32(o, true); o += 4;
    const edgesPos = o;
    let total = 0;
    for (let i = 0, p = edgesPos; i < en; i++) { const np = u8[p + 16]; total += np; p += 17 + 4 * np; }
    m.edgeCount = en; m.nodeCount = nn;
    m.eFrom = new Int32Array(en); m.eTo = new Int32Array(en);
    m.eFlags = new Uint8Array(en); m.eCls = new Uint8Array(en); m.eGrade = new Uint8Array(en);
    m.eSpeed = new Uint16Array(en); m.eWidth = new Float32Array(en);
    m.eJid = new Uint32Array(en); m.ePtStart = new Int32Array(en + 1);
    m.ptX = new Float32Array(total); m.ptZ = new Float32Array(total); m.eLen = new Float32Array(en);
    let pp = 0;
    for (let i = 0; i < en; i++) {
      m.eFrom[i] = dv.getUint32(o, true); m.eTo[i] = dv.getUint32(o + 4, true);
      const fl = u8[o + 8]; m.eFlags[i] = fl; m.eCls[i] = u8[o + 9];
      m.eGrade[i] = ver >= 6 ? (fl >> 1) & 3 : (m.eCls[i] === 2 ? 3 : m.eCls[i] === 1 ? 2 : 0);
      m.eSpeed[i] = u8[o + 10] * 5; m.eWidth[i] = u8[o + 11] / 4;
      m.eJid[i] = dv.getUint32(o + 12, true);
      const npts = u8[o + 16];
      o += 17;
      m.ePtStart[i] = pp;
      let qx = Math.floor(m.nodeX[m.eFrom[i]] * 4 + 0.5), qz = Math.floor(m.nodeZ[m.eFrom[i]] * 4 + 0.5);
      let len = 0;
      for (let k = 0; k < npts; k++) {
        qx += dv.getInt16(o, true); qz += dv.getInt16(o + 2, true); o += 4;
        m.ptX[pp] = qx / 4; m.ptZ[pp] = qz / 4;
        if (k > 0) { const dx = m.ptX[pp] - m.ptX[pp - 1], dz = m.ptZ[pp] - m.ptZ[pp - 1]; len += Math.sqrt(dx * dx + dz * dz); }
        pp++;
      }
      m.eLen[i] = Math.max(len, 0.5);
    }
    m.ePtStart[en] = pp;
    const dec = new TextDecoder('utf-8');
    const str = () => { const n = u8[o]; const s = dec.decode(u8.subarray(o + 1, o + 1 + n)); o += 1 + n; return s; };
    const cn = dv.getUint32(o, true); o += 4;
    m.cities = [];
    for (let i = 0; i < cn; i++) {
      const id = str(), name = str();
      const x = dv.getFloat32(o, true), z = dv.getFloat32(o + 4, true); o += 8;
      m.cities.push({ id, name, x, z, key: normalizeName(name) });
    }
    m.cities.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
    m.companies = [];
    if (ver >= 3 && o + 4 <= raw.byteLength) {
      const kn = dv.getUint32(o, true); o += 4;
      for (let i = 0; i < kn; i++) {
        const id = str(), cityId = str();
        m.companies.push({ id, cityId, x: dv.getFloat32(o, true), z: dv.getFloat32(o + 4, true) }); o += 8;
      }
    }
    m.pois = [];
    if (ver >= 5 && o + 4 <= raw.byteLength) {
      const pn = dv.getUint32(o, true); o += 4;
      for (let i = 0; i < pn; i++) {
        m.pois.push({ type: u8[o], x: dv.getFloat32(o + 1, true), z: dv.getFloat32(o + 5, true) }); o += 9;
      }
    }
    m.laneN = new Uint8Array(0); m.laneMask = new Uint16Array(0);
    if (ver >= 8 && o + 4 <= raw.byteLength) {
      m.laneN = new Uint8Array(en); m.laneMask = new Uint16Array(en);
      const ln = dv.getUint32(o, true); o += 4;
      for (let i = 0; i < ln; i++) {
        const e = dv.getUint32(o, true);
        if (e < en) { m.laneN[e] = u8[o + 4]; m.laneMask[e] = dv.getUint16(o + 5, true); }
        o += 7;
      }
    }
    m.companyNames = new Map(); m.roadLabels = []; m.areas = []; m.borders = []; m.cityCountry = new Map();
    if (ver >= 9 && o + 4 <= raw.byteLength) {
      const nn2 = dv.getUint32(o, true); o += 4;
      for (let i = 0; i < nn2; i++) { const id = str(); m.companyNames.set(id.toLowerCase(), str()); }
      const ln2 = dv.getUint32(o, true); o += 4;
      for (let i = 0; i < ln2; i++) { const text = str(); m.roadLabels.push({ text, x: dv.getFloat32(o, true), z: dv.getFloat32(o + 4, true) }); o += 8; }
      const an = dv.getUint32(o, true); o += 4;
      for (let i = 0; i < an; i++) {
        const color = u8[o], np = dv.getUint16(o + 1, true); o += 3;
        const xs = new Float32Array(np), zs = new Float32Array(np);
        let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity;
        for (let k = 0; k < np; k++) {
          const x = dv.getFloat32(o, true), z = dv.getFloat32(o + 4, true); o += 8;
          xs[k] = x; zs[k] = z;
          if (x < x0) x0 = x; if (x > x1) x1 = x; if (z < z0) z0 = z; if (z > z1) z1 = z;
        }
        m.areas.push({ color, xs, zs, x0, x1, z0, z1 });
      }
      if (o + 4 <= raw.byteLength) {
        const bn = dv.getUint32(o, true); o += 4;
        for (let i = 0; i < bn; i++) { const kind = u8[o]; o++; const cc = str(); m.borders.push({ kind, cc, x: dv.getFloat32(o, true), z: dv.getFloat32(o + 4, true) }); o += 8; }
        if (o + 4 <= raw.byteLength) {
          const cn2 = dv.getUint32(o, true); o += 4;
          for (let i = 0; i < cn2; i++) { const id = str(); m.cityCountry.set(id.toLowerCase(), str()); }
        }
      }
    }
    m.buildGrid();
    return m;
  }

  /** ISO alpha-2 of the country around a point: that of the nearest city. */
  countryAt(x, z) {
    let best = null, bd = Infinity;
    for (const c of this.cities) {
      const dx = c.x - x, dz = c.z - z, dd = dx * dx + dz * dz;
      if (dd < bd && this.cityCountry.has(c.id.toLowerCase())) { bd = dd; best = c; }
    }
    return best ? this.cityCountry.get(best.id.toLowerCase()) : null;
  }

  buildGrid() {
    this.cell = 400;
    this.cols = Math.floor((this.maxX - this.minX) / this.cell) + 2;
    this.rows = Math.floor((this.maxZ - this.minZ) / this.cell) + 2;
    const counts = new Int32Array(this.cols * this.rows + 1);
    const each = (f) => {
      for (let e = 0; e < this.edgeCount; e++) {
        let x0 = 1e30, x1 = -1e30, z0 = 1e30, z1 = -1e30;
        for (let p = this.ePtStart[e]; p < this.ePtStart[e + 1]; p++) {
          const x = this.ptX[p], z = this.ptZ[p];
          if (x < x0) x0 = x; if (x > x1) x1 = x; if (z < z0) z0 = z; if (z > z1) z1 = z;
        }
        if (x0 > x1) continue;
        const cx0 = this.cellX(x0), cx1 = this.cellX(x1), cz0 = this.cellZ(z0), cz1 = this.cellZ(z1);
        for (let cz = cz0; cz <= cz1; cz++) for (let cx = cx0; cx <= cx1; cx++) f(e, cz * this.cols + cx);
      }
    };
    each((e, c) => { counts[c + 1]++; });
    for (let i = 1; i < counts.length; i++) counts[i] += counts[i - 1];
    this.cellStart = counts;
    this.cellEdges = new Int32Array(counts[counts.length - 1]);
    const fill = new Int32Array(this.cols * this.rows);
    each((e, c) => { this.cellEdges[this.cellStart[c] + fill[c]] = e; fill[c]++; });
    this.stamp = new Int32Array(this.edgeCount);
    this.stampGen = 0;
  }

  cellX(x) { const v = Math.floor((x - this.minX) / this.cell); return v < 0 ? 0 : v > this.cols - 1 ? this.cols - 1 : v; }
  cellZ(z) { const v = Math.floor((z - this.minZ) / this.cell); return v < 0 ? 0 : v > this.rows - 1 ? this.rows - 1 : v; }

  /** Calls f(edge) once for every edge whose grid cells intersect the square [x±r, z±r]. */
  queryEdges(x, z, r, f) {
    const gen = ++this.stampGen;
    const cx0 = this.cellX(x - r), cx1 = this.cellX(x + r), cz0 = this.cellZ(z - r), cz1 = this.cellZ(z + r);
    for (let cz = cz0; cz <= cz1; cz++) for (let cx = cx0; cx <= cx1; cx++) {
      const c = cz * this.cols + cx;
      for (let k = this.cellStart[c]; k < this.cellStart[c + 1]; k++) {
        const e = this.cellEdges[k];
        if (this.stamp[e] !== gen) { this.stamp[e] = gen; f(e); }
      }
    }
  }

  isTwoWay(e) { return (this.eFlags[e] & 1) !== 0; }
  isFerry(e) { return (this.eFlags[e] & 8) !== 0; }
  isHidden(e) { return (this.eFlags[e] & 16) !== 0; }
  lanesAtEntry(e) { return e < this.laneN.length ? this.laneN[e] : 0; }
  laneMaskOf(e) { return e < this.laneMask.length ? this.laneMask[e] : 0; }

  deFrom(de) { return (de & 1) === 0 ? this.eFrom[de >> 1] : this.eTo[de >> 1]; }
  deTo(de) { return (de & 1) === 0 ? this.eTo[de >> 1] : this.eFrom[de >> 1]; }
  deCount(de) { const e = de >> 1; return this.ePtStart[e + 1] - this.ePtStart[e]; }
  deX(de, i) { const e = de >> 1; return (de & 1) === 0 ? this.ptX[this.ePtStart[e] + i] : this.ptX[this.ePtStart[e + 1] - 1 - i]; }
  deZ(de, i) { const e = de >> 1; return (de & 1) === 0 ? this.ptZ[this.ePtStart[e] + i] : this.ptZ[this.ePtStart[e + 1] - 1 - i]; }

  findCity(id) { id = id.toLowerCase(); return this.cities.find((c) => c.id.toLowerCase() === id) || null; }
  findCompany(cityId, companyId) {
    const a = cityId.toLowerCase(), b = companyId.toLowerCase();
    return this.companies.find((c) => c.id.toLowerCase() === b && c.cityId.toLowerCase() === a) || null;
  }
}
