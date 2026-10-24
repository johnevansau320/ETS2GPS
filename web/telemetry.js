'use strict';
/* Telemetry (via the companion's /api/telemetry proxy of the TruckSim GPS server), motion filter, text repair. */

const CP1252 = {
  0x20AC: 0x80, 0x201A: 0x82, 0x0192: 0x83, 0x201E: 0x84, 0x2026: 0x85, 0x2020: 0x86, 0x2021: 0x87, 0x02C6: 0x88,
  0x2030: 0x89, 0x0160: 0x8A, 0x2039: 0x8B, 0x0152: 0x8C, 0x017D: 0x8E, 0x2018: 0x91, 0x2019: 0x92, 0x201C: 0x93,
  0x201D: 0x94, 0x2022: 0x95, 0x2013: 0x96, 0x2014: 0x97, 0x02DC: 0x98, 0x2122: 0x99, 0x0161: 0x9A, 0x203A: 0x9B,
  0x0153: 0x9C, 0x017E: 0x9E, 0x0178: 0x9F,
};

/** The server sometimes sends UTF-8 text that was decoded as Windows-1252 ("Ð¦Ðµ..."); undo that when it yields clean UTF-8. */
function fixText(s) {
  if (!/[Â-ô\uDC80-\uDCFF]/.test(s)) return s;
  const bytes = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 0xDC80 && c <= 0xDCFF) bytes.push(c - 0xDC00);
    else if (c < 0x100) bytes.push(c);
    else if (CP1252[c] !== undefined) bytes.push(CP1252[c]);
    else return s;
  }
  try {
    const dec = new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(bytes));
    return dec.length < s.length ? dec : s;
  } catch (e) { return s; }
}

/** "0001-01-01T10:11:00" (a duration encoded as a date) -> minutes, or -1. */
function restMinutes(t) {
  const m = /^\d{4}-\d{2}-(\d{2})T(\d{2}):(\d{2})/.exec(t || '');
  return m ? (parseInt(m[1], 10) - 1) * 1440 + parseInt(m[2], 10) * 60 + parseInt(m[3], 10) : -1;
}

/** "0001-05-05T23:49:00Z" -> minutes since 0001-01-01 00:00 (a Monday), or -1. */
function clockMinutes(t) {
  const m = /^\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(t || '');
  if (!m) return -1;
  const cum = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
  const mo = Math.min(11, Math.max(0, parseInt(m[1], 10) - 1));
  return (cum[mo] + parseInt(m[2], 10) - 1) * 1440 + parseInt(m[3], 10) * 60 + parseInt(m[4], 10);
}

function parseTruckState(j) {
  const str = (o, k) => (o && o[k] !== null && o[k] !== undefined ? fixText(String(o[k]).trim()) : '');
  const num = (o, k) => (o && typeof o[k] === 'number' ? o[k] : 0);
  const game = j.game || {}, truck = j.truck || {}, pl = truck.placement || {}, nav = j.navigation || {}, job = j.job || {};
  const trailers = j.trailers || [];
  const gp = j.gameplay || {};
  const tr = trailers[0] || {};
  const attached = trailers.length > 0 && tr.attached === true;
  const cap = num(truck, 'fuelCapacity');
  const destId = str(job, 'destinationCityId');
  return {
    connected: game.connected === true, paused: game.paused === true,
    x: num(pl, 'x'), y: num(pl, 'y'), z: num(pl, 'z'), reportedHeadingDeg: num(pl, 'heading'),
    speedKmh: num(truck, 'speed'), speedLimitKmh: Math.round(num(nav, 'speedLimit')),
    destCityId: destId, destCity: str(job, 'destinationCity'),
    destCompanyId: str(job, 'destinationCompanyId'), destCompany: str(job, 'destinationCompany'),
    hasTrailer: attached,
    fuelFraction: cap > 0 ? num(truck, 'fuel') / cap : -1,
    fuelWarning: truck.fuelWarningOn === true,
    nextRestMin: restMinutes(str(game, 'nextRestStopTime')),
    job: destId === '' ? null : {
      srcCity: str(job, 'sourceCity'), srcCompany: str(job, 'sourceCompany'),
      destCity: str(job, 'destinationCity'), destCompany: str(job, 'destinationCompany'),
      cargo: str(job, 'cargo'), massKg: num(job, 'cargoMass'), income: num(job, 'income'),
      dueMin: restMinutes(str(job, 'remainingTime')), plannedKm: Math.round(num(job, 'plannedDistanceKm')),
      units: Math.round(num(job, 'unitCount')) || 1,
    },
    navEtaMin: restMinutes(typeof nav.estimatedTime === 'string' ? nav.estimatedTime : ''),
    gameTimeMin: clockMinutes(str(game, 'time')),
    onJob: gp.onJob === true, jobDelivered: gp.jobDelivered === true, jobCancelled: gp.jobCancelled === true, jobFinished: gp.jobFinished === true,
    detail: {
      truckName: (str(truck, 'make') + ' ' + str(truck, 'model')).trim(),
      odometerKm: num(truck, 'odometer'), fuelL: num(truck, 'fuel'), fuelCapL: cap,
      consumptionLPerKm: num(truck, 'fuelAverageConsumption'), rangeKm: num(truck, 'fuelRange'),
      trailerAttached: attached,
      trailerName: attached ? (str(tr, 'brand') + ' ' + str(tr, 'name')).trim() : '',
      trailerPlate: attached ? str(tr, 'licensePlate') : '',
      trailerChassis: attached ? num(tr, 'wearChassis') * 100 : 0, trailerWheels: attached ? num(tr, 'wearWheels') * 100 : 0,
      trailerBody: attached ? num(tr, 'wearBody') * 100 : 0, cargoDamage: attached ? num(tr, 'cargoDamage') * 100 : 0,
      navDistanceKm: num(nav, 'estimatedDistance') / 1000, jobMarket: str(job, 'jobMarket'),
    },
  };
}

class TelemetryClient {
  constructor(onState, onStatus) {
    this.onState = onState; this.onStatus = onStatus;
    this.running = false; this.failures = 0;
  }
  start() { if (this.running) return; this.running = true; this.poll(); }
  stop() { this.running = false; }
  async poll() {
    while (this.running) {
      let delay = 150;
      try {
        const ctl = new AbortController();
        const to = setTimeout(() => ctl.abort(), 2500);
        const r = await fetch('/api/telemetry', { cache: 'no-store', signal: ctl.signal });
        clearTimeout(to);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        this.onState(parseTruckState(await r.json()));
        this.failures = 0;
      } catch (e) {
        this.failures++;
        this.onStatus('No telemetry (' + (e.message || e.name) + ') - is the TruckSim GPS server running? Retrying');
        delay = this.failures < 5 ? 700 : 2000;
      }
      await new Promise((res) => setTimeout(res, delay));
    }
  }
}

/** Steady position, heading and speed from the noisy stream; altitude and gradient from height change over distance. */
class MotionFilter {
  constructor() {
    this.x = 0; this.z = 0; this.bearing = 0; this.speedMps = 0;
    this.lastT = 0; this.vx = 0; this.vz = 0; this.haveFix = false; this.maxReported = 0;
    this.altitude = 0; this.travelled = 0; this.gradientPct = 0; this.gradientValid = false;
    this.gd = new Float32Array(96); this.gy = new Float32Array(96); this.gCount = 0; this.gHead = 0; this.lastPush = -1e9;
  }
  pushAlt(y) {
    this.altitude = y;
    if (this.travelled - this.lastPush >= 5) {
      this.lastPush = this.travelled;
      this.gd[this.gHead] = this.travelled; this.gy[this.gHead] = y;
      this.gHead = (this.gHead + 1) % this.gd.length;
      if (this.gCount < this.gd.length) this.gCount++;
    }
    for (let i = 0; i < this.gCount; i++) {
      const idx = (this.gHead - 1 - i + this.gd.length * 2) % this.gd.length;
      const dd = this.travelled - this.gd[idx];
      if (dd >= 60) {
        const g = (y - this.gy[idx]) / dd * 100;
        this.gradientPct += (g - this.gradientPct) * (this.gradientValid ? 0.3 : 1);
        this.gradientValid = true;
        return;
      }
    }
  }
  reportedBearing(h) {
    if (h > this.maxReported) this.maxReported = h;
    const deg = this.maxReported <= 1.0001 ? h * 360 : h;
    return ((360 - deg) % 360) * Math.PI / 180; // SCS heading: 0 = north, counter-clockwise
  }
  blend(a, b, k) { return a + angleDiff(b, a) * k; }
  update(s, nowMs) {
    if (!s.connected) return;
    if (!this.haveFix || Math.hypot(s.x - this.x, s.z - this.z) > 400) { // first fix or teleport
      this.x = s.x; this.z = s.z; this.haveFix = true; this.lastT = nowMs; this.vx = 0; this.vz = 0;
      this.gCount = 0; this.gHead = 0; this.gradientValid = false; this.lastPush = -1e9; this.altitude = s.y;
      this.bearing = this.reportedBearing(s.reportedHeadingDeg);
      return;
    }
    const dt = (nowMs - this.lastT) / 1000;
    const dx = s.x - this.x, dz = s.z - this.z, moved = Math.hypot(dx, dz);
    if (moved < 0.01) {
      if (dt > 0.8) {
        this.vx = 0; this.vz = 0; this.speedMps = 0;
        this.bearing = this.blend(this.bearing, this.reportedBearing(s.reportedHeadingDeg), 0.1);
        this.lastT = nowMs;
      }
      return;
    }
    if (dt < 0.02) return;
    this.vx += (dx / dt - this.vx) * 0.5; this.vz += (dz / dt - this.vz) * 0.5;
    this.speedMps = Math.hypot(this.vx, this.vz);
    if (moved > 0.4 && moved / dt > 1.5) this.bearing = this.blend(this.bearing, Math.atan2(dx, -dz), 0.35);
    this.x = s.x; this.z = s.z; this.lastT = nowMs;
    this.travelled += moved;
    this.pushAlt(s.y);
  }
  predicted(nowMs) {
    const dt = Math.min(0.4, Math.max(0, (nowMs - this.lastT) / 1000));
    return [this.x + this.vx * dt, this.z + this.vz * dt];
  }
}

/** Altitude samples along the trip (one every ~25 map metres), decimated when it grows large. */
class ElevationLog {
  constructor() { this.dist = []; this.alt = []; this.step = 25; this.lastD = -1e9; }
  add(distance, y) {
    if (distance - this.lastD < this.step) return;
    this.lastD = distance;
    if (this.dist.length >= 8192) { this.dist = this.dist.filter((_, i) => i % 2 === 0); this.alt = this.alt.filter((_, i) => i % 2 === 0); this.step *= 2; }
    this.dist.push(distance); this.alt.push(y);
  }
  clear() { this.dist = []; this.alt = []; this.lastD = -1e9; this.step = 25; }
  ascent() { let a = 0; for (let i = 1; i < this.alt.length; i++) { const d = this.alt[i] - this.alt[i - 1]; if (d > 0.3) a += d; } return a; }
  descent() { let a = 0; for (let i = 1; i < this.alt.length; i++) { const d = this.alt[i] - this.alt[i - 1]; if (d < -0.3) a -= d; } return a; }
}
