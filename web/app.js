'use strict';
/* ETS2 GPS web app: UI and glue (port of MainActivity.kt). Served by the companion at http://<pc>:31378/ */

const TIME_SCALE = 19;
const LANE_SHOW_M = 50000; // the lane panel appears this far (in game metres) before the junction
const $ = (id) => document.getElementById(id);
const ls = {
  get(k, d) { try { const v = localStorage.getItem('ets2gps.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('ets2gps.' + k, JSON.stringify(v)); } catch (e) { /* private mode */ } },
};
const fmt = (v, d = 0) => Number(v).toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });

// ---------------------------------------------------------------------------- state --
let map = null, router = null, nav = null, truck = null;
const motion = new MotionFilter(), elev = new ElevationLog();
let routeMode = ROUTE_MODES.find((m) => m.key === ls.get('mode', 'HEAVY')) || ROUTE_MODES[0];
let simScale = ls.get('scale', 19), realDist = ls.get('real', false);
let distScale = realDist ? 1 : simScale;   // what map metres are multiplied by for display: 1 = real, simScale = game (Sim)
let avoidFerries = ls.get('ferries', false);
let hasDest = false, destX = 0, destZ = 0, destName = '', destIsPoi = false;
let offAnnounced = false, destIsJob = false, endHinted = false, stillSince = 0;
let resume = null; // {x,z,name,stops} while a POI detour is active
let waypoints = [];
let afterM = 0, afterS = 0, startLabel = 'Start';
let avoidPoints = ls.get('avoid', []);
let routeRemainingM = -1, routeEtaGameMin = -1, paceRatio = 1, lastPaceMs = 0, lastNextText = '';
let routing = false, lastReroute = 0, place = '', lastPlaceCheck = 0;
let tripBase = 0, tripDriveMs = 0, tripMaxKmh = 0, tripFuelUsed = 0, tripLastFuel = -1, lastTripMs = 0;
let descStart = -1, climbStart = -1, lastDescWarn = 0, lastClimbWarn = 0;
let fuelAlerted = false, rest30 = false, rest10 = false, lastJobKey = '', tripOpen = false, statusMsg = '';

const mapView = new MapView($('map'));
const telemetry = new TelemetryClient(onTruck, (t) => { statusMsg = t; $('status').textContent = t; });

// ------------------------------------------------------------------------------ voice --
const voice = {
  enabled: ls.get('voice', true), output: ls.get('voice_out', 0), unlocked: false, pcWarned: false,
  unlock() {
    if (this.unlocked || !('speechSynthesis' in window)) return;
    this.unlocked = true;
    try { const u = new SpeechSynthesisUtterance(' '); u.volume = 0; speechSynthesis.speak(u); } catch (e) { /* ignore */ }
  },
  say(text) {
    if (!this.enabled || !text) return;
    if (this.output !== 0) {
      fetch('/say', { method: 'POST', body: text }).then((r) => { if (!r.ok) throw new Error(); }).catch(() => {
        if (!this.pcWarned) { this.pcWarned = true; toast('PC speaker unreachable - using this device'); }
        if (this.output === 1) this.speakLocal(text);
      });
    }
    if (this.output !== 1) this.speakLocal(text);
  },
  speakLocal(text) {
    if (!('speechSynthesis' in window)) return;
    try {
      speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      u.lang = navigator.language || 'en-US'; u.rate = 1.0;
      speechSynthesis.speak(u);
    } catch (e) { /* ignore */ }
  },
};
document.addEventListener('pointerdown', () => voice.unlock(), { once: true });

// ------------------------------------------------------------------------------- UI helpers --
let toastTimer = 0;
function toast(t) {
  const el = $('toast'); el.textContent = t; el.style.display = 'block';
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { el.style.display = 'none'; }, 3500);
}

/** Modal dialog: opts {title, message, html, items:[text], onItem(i), input:{value,placeholder}, buttons:[{label,fn,primary}], build(bodyEl)} */
function modal(opts) {
  const bg = $('modalBg'), box = $('modal');
  box.innerHTML = '';
  const close = () => { bg.style.display = 'none'; box.innerHTML = ''; };
  const h = document.createElement('h2'); h.textContent = opts.title || ''; box.appendChild(h);
  const body = document.createElement('div'); body.className = 'body'; box.appendChild(body);
  if (opts.message) { const p = document.createElement('div'); p.textContent = opts.message; body.appendChild(p); }
  let input = null;
  if (opts.input) {
    input = document.createElement('input'); input.type = opts.input.type || 'text';
    input.value = opts.input.value || ''; input.placeholder = opts.input.placeholder || '';
    input.autocomplete = 'off'; input.autocapitalize = 'off'; input.spellcheck = false;
    body.appendChild(input);
  }
  let list = null;
  const renderItems = (items) => {
    if (!list) { list = document.createElement('div'); list.className = 'list'; body.appendChild(list); }
    list.innerHTML = '';
    items.forEach((t, i) => {
      const d = document.createElement('div'); d.textContent = t;
      d.addEventListener('click', () => { close(); opts.onItem(i); });
      list.appendChild(d);
    });
  };
  if (opts.items) renderItems(opts.items);
  if (opts.build) opts.build(body, close);
  const acts = document.createElement('div'); acts.className = 'acts'; box.appendChild(acts);
  const buttons = opts.buttons || [{ label: 'Close' }];
  for (const b of buttons) {
    const bt = document.createElement('button'); bt.textContent = b.label; if (b.primary) bt.className = 'primary';
    bt.addEventListener('click', () => { const keep = b.fn ? b.fn(input ? input.value : null) : null; if (keep !== 'keep') close(); });
    acts.appendChild(bt);
  }
  bg.style.display = 'flex';
  bg.onclick = (e) => { if (e.target === bg) close(); };
  if (input) setTimeout(() => { input.focus(); if (input.type === 'text') input.select(); }, 50);
  return { close, input, renderItems };
}

function formatDuration(min) {
  const m = Math.round(min);
  if (m >= 1440) return Math.floor(m / 1440) + 'd ' + Math.floor((m % 1440) / 60) + 'h ' + String(m % 60).padStart(2, '0') + 'm';
  if (m >= 60) return Math.floor(m / 60) + 'h ' + String(m % 60).padStart(2, '0') + 'm';
  return m + 'm';
}
function clockText(absMin) {
  if (absMin < 0) return '-';
  const day = Math.floor(absMin / 1440), inDay = absMin - day * 1440;
  const names = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  return names[day % 7] + ' ' + String(Math.floor(inDay / 60)).padStart(2, '0') + ':' + String(Math.floor(inDay % 60)).padStart(2, '0');
}
function etaClock(gameMin) {
  if (!truck || truck.gameTimeMin < 0) return '';
  const abs = truck.gameTimeMin + gameMin, day = Math.floor(abs / 1440), today = Math.floor(truck.gameTimeMin / 1440);
  return clockText(abs) + (day > today + 6 ? ' (+' + (day - today) + 'd)' : '');
}
const placeName = (company, city) => (company ? company + ', ' + city : city);

// ------------------------------------------------------------------------------ layout --
function layout() {
  mapView.resize();
  const h = window.innerHeight, barBottom = 6 + 96 + 6;
  const zoomTop = h / 2 - 56;
  const top = Math.max(barBottom, (barBottom + zoomTop) / 2 - 50);
  $('compass').style.top = top + 'px';
  $('alt').style.top = top + 'px'; $('alt').style.right = '108px';
}
window.addEventListener('resize', layout);
window.addEventListener('orientationchange', () => setTimeout(layout, 200));

// ---------------------------------------------------------------------------------- map --
async function loadMap() {
  setBannerMessage('Downloading the map from your PC…');
  try {
    const r = await fetch('/map.bin', { cache: 'no-cache' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const total = parseInt(r.headers.get('Content-Length') || '0', 10);
    const reader = r.body.getReader(), chunks = [];
    let got = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value); got += value.length;
      if (total) setBannerMessage('Downloading the map… ' + Math.round(got * 100 / total) + '%');
    }
    setBannerMessage('Unpacking the map…');
    await new Promise((res) => setTimeout(res, 30));
    const blob = new Blob(chunks);
    let raw;
    if (typeof DecompressionStream !== 'undefined') {
      raw = await new Response(blob.stream().pipeThrough(new DecompressionStream('deflate'))).arrayBuffer();
    } else throw new Error('This browser cannot unpack the map (needs iPadOS / Safari 16.4 or newer)');
    setBannerMessage('Building the road network…');
    await new Promise((res) => setTimeout(res, 30));
    const m = MapData.parse(raw);
    const rt = new Router(m);
    rt.mode = routeMode;
    rt.setAvoid(avoidPoints);
    rt.avoidFerries = avoidFerries;
    map = m; router = rt; nav = new Navigator(m, rt); nav.distScale = distScale;
    mapView.map = m;
    loadAssets();
    setBannerMessage(null);
    toast('Map loaded: ' + m.cities.length + ' cities, ' + fmt(m.edgeCount) + ' road segments' + (m.laneN.length ? ', with lane data' : ' - no lane data: rebuild the map with the updated companion'));
  } catch (e) {
    setBannerMessage('Could not load the map: ' + (e.message || e));
  }
}

// Logos and flags are optional: older PCs / companions do not have them.
async function loadAssets() {
  try { mapView.assets = await MapAssets.load('/assets.bin'); } catch (e) { /* names only */ }
}

// ------------------------------------------------------------------------------ banner --
let bannerMsg = null, lastManeuverKey = '';
function setBannerMessage(t) {
  bannerMsg = t;
  $('bMsg').style.display = t ? 'block' : 'none'; $('bMsg').textContent = t || '';
  $('bMain').style.display = t ? 'none' : 'block';
  if (t) { clearGlyph($('bGlyph')); $('bThen').style.display = 'none'; }
}
function clearGlyph(cv) { const c = cv.getContext('2d'); c.clearRect(0, 0, cv.width, cv.height); }
function drawGlyphOn(cv, turn, exitNo, angle, size) {
  const dpr = window.devicePixelRatio || 1;
  if (cv.width !== size * dpr) { cv.width = size * dpr; cv.height = size * dpr; cv.style.width = size + 'px'; cv.style.height = size + 'px'; }
  const c = cv.getContext('2d'); c.setTransform(dpr, 0, 0, dpr, 0, 0); c.clearRect(0, 0, size, size);
  Glyphs.draw(c, turn, exitNo, angle, size / 2, size / 2, size, '#fff');
}
function setManeuver(next, dist, what, then) {
  if (bannerMsg) setBannerMessage(null);
  $('bDist').textContent = dist; $('bWhat').textContent = what;
  const key = next.turn + next.exitNo + Math.round(next.angleDeg);
  if (key !== lastManeuverKey) { lastManeuverKey = key; drawGlyphOn($('bGlyph'), next.turn, next.exitNo, next.angleDeg, 76); }
  const t = then && then.turn !== Turn.ARRIVE ? then : null;
  $('bThen').style.display = t ? 'block' : 'none';
  if (t) { const c = $('bThenGlyph').getContext('2d'); c.clearRect(0, 0, 60, 60); Glyphs.draw(c, t.turn, t.exitNo, t.angleDeg, 30, 30, 52, '#fff'); }
}
function clearManeuver() { lastManeuverKey = ''; $('bDist').textContent = ''; $('bWhat').textContent = 'No route - tap Search, Job or long-press the map'; clearGlyph($('bGlyph')); $('bThen').style.display = 'none'; }
function updateBannerJob() {
  const j = truck && truck.job;
  if (!j) { $('bJob1').textContent = ''; $('bJob2').textContent = ''; return; }
  $('bJob1').textContent = placeName(j.srcCompany, j.srcCity) + '  →  ' + placeName(j.destCompany, j.destCity);
  const parts = [];
  if (j.cargo) parts.push(j.cargo + (j.massKg > 0 ? ' ' + (j.massKg / 1000).toFixed(1) + ' t' : ''));
  if (j.income > 0) parts.push('€' + fmt(j.income));
  const etaMin = nav && nav.active && routeEtaGameMin >= 0 ? routeEtaGameMin : truck.navEtaMin;
  let late = false;
  if (j.dueMin >= 0) {
    let due = 'Due ' + formatDuration(j.dueMin);
    if (etaMin >= 0) { due += ' (ETA ' + formatDuration(etaMin) + ')'; late = etaMin > j.dueMin; }
    parts.push(due);
  }
  $('bJob2').textContent = parts.join('  ·  '); $('bJob2').classList.toggle('late', late);
}

let lastLaneJson = '', lastLanePush = 0;
/** Tells the companion what the lane panel shows, so the PC overlay can display it too. */
function pushLanes(lanes, caption) {
  const body = JSON.stringify({ lanes: lanes ? lanes.map((l) => ({ a: Number.isNaN(l.angleDeg) ? null : Math.round(l.angleDeg * 10) / 10, p: !!l.preferred, alt: l.altDeg === null || l.altDeg === undefined ? null : Math.round(l.altDeg * 10) / 10 })) : null, caption: caption || '' });
  const now = performance.now();
  if (body === lastLaneJson && (!lanes || now - lastLanePush < 2000)) return; // "no lanes" is sent once, not repeated (a repeat would blank the PC's lanes)
  if (lanes && body !== lastLaneJson && now - lastLanePush < 400) return;
  lastLaneJson = body; lastLanePush = now;
  fetch('/lanes', { method: 'POST', body }).catch(() => {});
}
function setLanes(lanes, caption) {
  const cv = $('lanes');
  pushLanes(tripOpen ? null : lanes, caption);
  if (!lanes || tripOpen) { cv.style.display = 'none'; return; }
  cv.style.display = 'block';
  LanePanel.draw(cv, lanes, caption || '');
}

// --------------------------------------------------------------------------- telemetry --
function onTruck(s) {
  truck = s;
  const now = performance.now();
  motion.update(s, now);
  mapView.hasFix = s.connected && (s.x !== 0 || s.z !== 0);
  mapView.speedKmh = s.speedKmh;
  statusMsg = !s.connected ? 'Telemetry connected - waiting for the game' : s.paused ? 'Game paused' : place;
  $('status').textContent = statusMsg;
  $('speed').textContent = Math.round(Math.max(0, s.speedKmh));
  const lim = $('limit');
  if (s.speedLimitKmh > 0) { lim.textContent = s.speedLimitKmh; lim.style.visibility = 'visible'; } else lim.style.visibility = 'hidden';
  trackTrip(s, now);
  updateBannerJob();
  if (tripOpen) updateTrip();
  if (s.connected) { updateAltitude(s); checkHill(s, now); checkNewJob(s); checkAlerts(s); updatePlace(now); navTick(); }
}

// ------------------------------------------------------------------------- navigation --
function navTick() {
  const n = nav;
  if (!n || !n.active) return;
  const st = n.update(motion.x, motion.z, motion.speedMps);
  mapView.routeIdx = n.currentIndex; mapView.nextManeuver = st.next;
  if (st.speech) voice.say(st.speech);
  if (st.arrived && waypoints.length) {
    const wp = waypoints.shift();
    n.clear(); syncStops();
    voice.say('Waypoint reached. Continuing to ' + (waypoints.length ? waypoints[0].name : destName));
    toast('Waypoint reached: ' + wp.name);
    computeRoute(destX, destZ, destName, { reroute: true });
    return;
  }
  if (st.arrived && resume) {
    const r = resume; resume = null; n.clear();
    voice.say('Back on route to ' + r.name);
    waypoints = r.stops.slice(); syncStops();
    computeRoute(r.x, r.z, r.name, { keepStops: true });
    return;
  }
  if (st.arrived && destIsJob && holdAtRouteEnd(n)) return;
  if (st.arrived) {
    if (destIsJob) voice.say('You have arrived at ' + destName);
    mapView.flagOn = false;
    $('info').textContent = 'Arrived at ' + destName;
    setBannerMessage('You have arrived'); clearManeuver();
    n.clear(); mapView.routePts = new Float32Array(0); mapView.routeEdgePt = new Int32Array(0); mapView.nextManeuver = null;
    hasDest = false; setLanes(null);
    return;
  }
  const next = st.next;
  lastNextText = next ? n.instruction(next) + ' in ' + n.shortDistance(st.distToNextM) + (next.towards ? ' towards ' + next.towards : '') : '';
  const totM = st.remainingM + afterM, totS = st.remainingS + afterS;
  routeRemainingM = totM;
  if (next) {
    const what = n.instruction(next); setManeuver(next, n.shortDistance(st.distToNextM), what.charAt(0).toUpperCase() + what.slice(1) + (next.towards ? ' towards ' + next.towards : ''), st.following);
  }
  if (!laneHidden() && st.lanes && st.lanesDistM * simScale < LANE_SHOW_M) setLanes(st.lanes, n.shortDistance(st.lanesDistM)); else setLanes(null);
  updatePace(n);
  const realMin = totS / paceRatio / 60, gameMin = realMin * TIME_SCALE;
  routeEtaGameMin = gameMin;
  const clock = etaClock(gameMin);
  const label = waypoints.length ? destName + ' (via ' + waypoints.length + ' stop' + (waypoints.length > 1 ? 's' : '') + ', next: ' + waypoints[0].name + ')' : destName;
  $('info').textContent = label + '\n' + n.shortDistance(totM) + ' · Real ' + formatDuration(realMin) + ' · Sim ' + formatDuration(gameMin) + (clock ? ' · Arrive ' + clock : '');
  if (!st.offRoute) offAnnounced = false;
  if (st.offRoute) rerouteIfNeeded();
}
const laneHidden = () => tripOpen;

/**
 * The mapped road network often ends well short of a company's gate (the game's own route goes on to the gate). While the game still reports a long way
 * to go for the job, keep the route and the flag on the map and tell the driver to carry on, instead of declaring the arrival at the end of the mapped route.
 */
function holdAtRouteEnd(n) {
  const t = truck;
  if (!t || !t.destCityId) return false; // job delivered or cancelled: arrived
  const navKm = t.detail ? t.detail.navDistanceKm : 0;
  const gameM = navKm > 0.001 ? navKm * 1000 : Math.hypot(motion.x - destX, motion.z - destZ) * simScale;
  const now = performance.now();
  if (motion.speedMps < 0.5) { if (!stillSince) stillSince = now; } else stillSince = 0;
  if (gameM < 150 || (stillSince && now - stillSince > 25000)) return false;
  if (!endHinted) { endHinted = true; voice.say('The mapped route ends here. Follow the flag to the drop off point.'); }
  setBannerMessage('Mapped route ends here - follow the flag');
  clearManeuver(); setLanes(null);
  $('info').textContent = destName + '\nTo the destination: about ' + n.shortDistance(gameM / Math.max(1, simScale)) + " (the game's route)";
  offAnnounced = false;
  return true;
}

/** Learns how fast the truck really travels compared with the router's speeds, so the ETA follows reality. */
function updatePace(n) {
  const now = performance.now(), dt = (now - lastPaceMs) / 1000;
  lastPaceMs = now;
  const model = n.modelSpeedMps, actual = motion.speedMps;
  if (dt <= 0 || dt > 5 || model < 5 || actual < 3) return;
  const ratio = Math.min(1.3, Math.max(0.35, actual / model));
  const a = 1 - Math.exp(-dt / 90);
  paceRatio = Math.min(1.2, Math.max(0.4, paceRatio + (ratio - paceRatio) * a));
}

function rerouteIfNeeded() {
  const now = performance.now();
  if (!hasDest || routing || now - lastReroute < 4000) return;
  lastReroute = now;
  if (!offAnnounced) { offAnnounced = true; voice.say('Recalculating'); } // once per time off the route, not every few seconds
  computeRoute(destX, destZ, destName, { reroute: true });
}
function rerouteForModeChange() { if (hasDest && nav && nav.active && !routing) computeRoute(destX, destZ, destName, { reroute: true }); }

function cancelRoute() {
  resume = null; destIsPoi = false; destIsJob = false; mapView.flagOn = false; waypoints = []; afterM = 0; afterS = 0; syncStops();
  if (nav) nav.clear();
  routeEtaGameMin = -1; routeRemainingM = -1; lastNextText = ''; paceRatio = 1; hasDest = false;
  mapView.routePts = new Float32Array(0); mapView.routeEdgePt = new Int32Array(0); mapView.nextManeuver = null;
  setBannerMessage(null); clearManeuver(); setLanes(null);
  $('info').textContent = 'No route';
}

/** opts: announce, isPoi, reroute, keepStops */
function computeRoute(x, z, name, opts = {}) {
  const r = router, n = nav, m = map;
  if (!r || !n || !m) { toast('Map not loaded yet'); return; }
  if (!truck || !truck.connected) { toast('Start the game first - no truck position yet'); return; }
  if (routing) return;
  routing = true;
  if (!opts.reroute) {
    if (opts.isPoi) {
      if (hasDest && !destIsPoi) resume = { x: destX, z: destZ, name: destName, stops: waypoints.slice() };
      waypoints = [];
    } else { resume = null; if (!opts.keepStops) waypoints = []; }
    destIsPoi = !!opts.isPoi; destIsJob = !!opts.isJob; endHinted = false; stillSince = 0;
  }
  destX = x; destZ = z; destName = name; hasDest = true;
  if (!opts.reroute && !opts.isPoi && !opts.keepStops) startLabel = place.replace(/^Near /, '') || 'Start';
  syncStops();
  const leg = waypoints[0] || null;
  const tx = leg ? leg.x : x, tz = leg ? leg.z : z, tname = leg ? leg.name : name;
  const chain = waypoints.slice(1).map((w) => ({ x: w.x, z: w.z })).concat([{ x, z }]);
  const legIsStop = !!leg;
  const sx = motion.x, sz = motion.z, brg = motion.bearing;
  if (opts.announce) setBannerMessage('Calculating route to ' + tname + '…');
  setTimeout(() => { // let the "calculating" message paint first
    try {
      const snap = r.snap(sx, sz, brg, 60) || r.snap(sx, sz, null, 200);
      if (!snap) { setBannerMessage('You are not on a known road'); return; }
      const node = r.nearestMainNode(tx, tz);
      if (node < 0) { setBannerMessage('Destination is not reachable by road'); return; }
      const remaining = Math.max(1, m.eLen[snap.de >> 1] - snap.along);
      const res = r.route(snap.de, remaining, node);
      if (!res) { setBannerMessage(r.avoidFerries ? 'No route without ferries - allow ferries in Settings' : 'No route found'); return; }
      let am = 0, asec = 0, prev = node;
      if (legIsStop) for (const c of chain) {
        const nn = r.nearestMainNode(c.x, c.z);
        if (nn < 0 || nn === prev || r.outCount(prev) === 0) continue;
        const ide = r.outAt(prev, 0), rr = r.route(ide, m.eLen[ide >> 1], nn);
        if (rr) { am += rr.lengthM; asec += rr.timeS; }
        prev = nn;
      }
      let total = 0;
      for (const de of res.edges) total += m.deCount(de);
      const pts = new Float32Array(total * 2), lift = new Uint8Array(total), starts = new Int32Array(res.edges.length);
      let p = 0;
      res.edges.forEach((de, i) => { starts[i] = p; for (let k = 0; k < m.deCount(de); k++) { pts[p * 2] = m.deX(de, k); pts[p * 2 + 1] = m.deZ(de, k); lift[p] = Math.round(m.deLift(de, k) * 2); p++; } });
      n.distScale = distScale;
      n.arriveWord = legIsStop ? 'your waypoint' : destIsJob ? 'the destination area' : 'your destination';
      mapView.flagOn = true; mapView.flagX = x; mapView.flagZ = z;
      afterM = am; afterS = asec;
      n.setRoute(res, remaining);
      mapView.routePts = pts; mapView.routeLift = lift; mapView.routeEdgePt = starts; mapView.routeIdx = 0;
      setBannerMessage(null);
      if (opts.announce) voice.say(legIsStop
        ? 'Route to ' + tname + ', the first of ' + (waypoints.length + 1) + ' stops. ' + n.spokenDistance(res.lengthM) + '. ' + routeMode.label + ' routing.'
        : 'Route to ' + name + '. ' + n.spokenDistance(res.lengthM) + '. ' + routeMode.label + ' routing.');
      navTick();
    } catch (e) {
      setBannerMessage('Routing failed: ' + (e.message || e));
    } finally { routing = false; }
  }, 30);
}

function navigateToJob(t) {
  const co = map.findCompany(t.destCityId, t.destCompanyId);
  if (co) { computeRoute(co.x, co.z, t.destCompany ? t.destCompany + ', ' + t.destCity : t.destCity, { announce: true, isJob: true }); return; }
  const c = map.findCity(t.destCityId) || map.cities.find((c) => c.key === normalizeName(t.destCity));
  if (!c) { toast("Job destination '" + t.destCity + "' is not on the loaded map"); return; }
  computeRoute(c.x, c.z, c.name, { announce: true, isJob: true });
}
function routeToJob() {
  if (!map) { toast('Map not loaded yet'); return; }
  const t = truck;
  if (!t || !t.connected) { toast('Start the game first - no telemetry yet'); return; }
  if (!t.destCityId && !t.destCity) { toast('The game reports no active job'); return; }
  navigateToJob(t);
}
function checkNewJob(t) {
  if (!map) return;
  const key = t.destCityId ? t.destCityId + '/' + t.destCompanyId : '';
  if (key === lastJobKey) return;
  lastJobKey = key;
  if (key) resetTrip();
  if (!key) return;
  const name = placeName(t.destCompany, t.destCity);
  modal({ title: 'New job', message: 'Navigate to ' + name + '?', buttons: [{ label: 'Not now' }, { label: 'Navigate', primary: true, fn: () => navigateToJob(t) }] });
}

// ------------------------------------------------------------------------------ waypoints --
function syncStops() { mapView.stops = waypoints.map((w) => ({ x: w.x, z: w.z })); }
function addWaypoint(x, z, name) {
  if (!hasDest) { toast('Create a route first, then add waypoints'); return; }
  if (routing) { toast('Still planning - try again in a moment'); return; }
  waypoints.push({ x, z, name }); syncStops();
  toast('Waypoint ' + waypoints.length + ' added: ' + name);
  voice.say('Waypoint added: ' + name);
  rerouteForModeChange();
}
function showStops() {
  if (!hasDest) { toast('Create a route with Search first, then add waypoints here'); return; }
  const names = waypoints.map((w, i) => (i + 1) + '. ' + w.name).concat('Destination: ' + destName);
  modal({
    title: 'Route stops', items: names, onItem: (i) => { if (i < waypoints.length) stopOptions(i); },
    buttons: [{ label: 'Close' }, { label: 'Clear waypoints', fn: () => { waypoints = []; syncStops(); rerouteForModeChange(); } },
      { label: 'Add waypoint', primary: true, fn: () => { setTimeout(() => showSearch(true), 0); } }],
  });
}
function stopOptions(i) {
  const w = waypoints[i]; if (!w) return;
  modal({
    title: w.name, items: ['Remove', 'Move earlier', 'Move later'],
    onItem: (k) => {
      if (k === 0) waypoints.splice(i, 1);
      else if (k === 1 && i > 0) { waypoints.splice(i, 1); waypoints.splice(i - 1, 0, w); }
      else if (k === 2 && i < waypoints.length - 1) { waypoints.splice(i, 1); waypoints.splice(i + 1, 0, w); }
      syncStops(); rerouteForModeChange();
    },
    buttons: [{ label: 'Cancel' }],
  });
}

// --------------------------------------------------------------------------- saved trips --
const loadTrips = () => ls.get('trips', []);
function saveCurrentTrip() {
  if (!hasDest || !nav || !nav.active) { toast('Plan a route first, then save it'); return; }
  if (destIsPoi) { toast('Finish the POI detour first, then save the trip'); return; }
  const def = startLabel + ' → ' + destName;
  modal({
    title: 'Save this trip as', input: { value: def },
    buttons: [{ label: 'Cancel' }, { label: 'Save', primary: true, fn: (v) => {
      const name = (v || '').trim() || def;
      const list = loadTrips().filter((t) => t.name !== name);
      list.unshift({ name, mode: routeMode.key, dest: { x: destX, z: destZ, name: destName }, stops: waypoints.map((w) => ({ ...w })), len: Math.max(0, routeRemainingM), ferries: avoidFerries });
      ls.set('trips', list); toast('Trip saved: ' + name);
    } }],
  });
}
function showTrips() {
  const list = loadTrips();
  const rows = list.map((t) => {
    const km = t.len * distScale / 1000, md = (ROUTE_MODES.find((m) => m.key === t.mode) || {}).label || '';
    return t.name + '\n' + (km >= 1 ? Math.round(km) + ' km · ' : '') + t.stops.length + ' waypoint' + (t.stops.length === 1 ? '' : 's') + ' · ' + md;
  });
  modal({
    title: 'Saved trips', message: list.length ? '' : 'No saved trips yet. Use Plan a trip (no game needed), or plan a route while driving and tap Save current trip.',
    items: rows, onItem: (i) => tripOptions(i),
    buttons: [{ label: 'Close' }, { label: 'Plan a trip…', fn: () => { plan = newPlan(); setTimeout(showPlanner, 0); } }, { label: 'Import / export…', fn: () => { setTimeout(tripFileMenu, 0); } }, { label: 'Save current trip', primary: true, fn: () => { setTimeout(saveCurrentTrip, 0); } }],
  });
}
function tripOptions(i) {
  const list = loadTrips(), t = list[i]; if (!t) return;
  const items = [];
  if (t.start) items.push('Drive this trip (to its start first)');
  items.push(t.start ? 'Drive from here (skip its start)' : 'Start this trip from here');
  items.push('View / edit the plan', 'Export this trip…', 'Rename', 'Delete');
  modal({
    title: t.name, items,
    onItem: (k) => {
      const what = items[k];
      if (what.startsWith('Drive this trip')) startSavedTrip(t, true);
      else if (what.startsWith('Drive from here') || what.startsWith('Start this trip')) startSavedTrip(t);
      else if (what.startsWith('View')) editSavedTrip(t);
      else if (what.startsWith('Export')) exportTrips([t], t.name);
      else if (what === 'Rename') modal({ title: 'Rename trip', input: { value: t.name }, buttons: [{ label: 'Cancel' }, { label: 'Rename', primary: true, fn: (v) => {
        const name = (v || '').trim(); if (!name || name === t.name) return;
        const l2 = loadTrips().filter((x, idx) => idx === i || x.name !== name);
        const at = l2.findIndex((x) => x.name === t.name); if (at >= 0) l2[at] = { ...t, name };
        ls.set('trips', l2); toast('Renamed to: ' + name);
      } }] });
      else { list.splice(i, 1); ls.set('trips', list); toast('Deleted: ' + t.name); }
    },
    buttons: [{ label: 'Cancel' }],
  });
}
function startSavedTrip(t, viaStart = false) {
  if (!map) { toast('Map not loaded yet'); return; }
  if (!truck || !truck.connected) { toast('Start the game first - no truck position yet'); return; }
  if (routing) { toast('Still planning - try again in a moment'); return; }
  const md = ROUTE_MODES.find((m) => m.key === t.mode); if (md) setRouteMode(md, false);
  setAvoidFerries(!!t.ferries, false);
  waypoints = t.stops.map((w) => ({ ...w })); resume = null;
  // a planned trip begins at its own start: drive there first unless the truck is already close to it
  if (viaStart && t.start && Math.hypot(t.start.x - motion.x, t.start.z - motion.z) > 1500) waypoints.unshift({ x: t.start.x, z: t.start.z, name: t.start.name });
  computeRoute(t.dest.x, t.dest.z, t.dest.name, { announce: true, keepStops: true });
}

// ----------------------------------------------------------------------- avoid / long-press --
function avoidRoadAt(x, z) {
  avoidPoints.push({ x, z }); ls.set('avoid', avoidPoints);
  if (router) router.setAvoid(avoidPoints);
  toast('Avoiding the road here (' + avoidPoints.length + ' avoided in total)');
  voice.say('Avoiding that road'); rerouteForModeChange();
}
function clearAvoid() { avoidPoints = []; ls.set('avoid', []); if (router) router.setAvoid([]); toast('Avoided roads cleared'); rerouteForModeChange(); }
mapView.onLongPress = (x, z) => {
  if (mapView.planMode) { planPointMenu(x, z); return; }
  const canStop = hasDest && nav && nav.active;
  const items = canStop ? ['Navigate here', 'Add as waypoint', 'Avoid road here'] : ['Navigate here', 'Avoid road here'];
  modal({
    title: 'This point on the map', items, buttons: [{ label: 'Cancel' }],
    onItem: (i) => {
      const it = items[i];
      if (it === 'Navigate here') computeRoute(x, z, 'Selected point', { announce: true });
      else if (it === 'Add as waypoint') addWaypoint(x, z, 'Selected point');
      else avoidRoadAt(x, z);
    },
  });
};

// ------------------------------------------------------------------------------- search --
function showSearch(asWaypoint = false) {
  if (!map) { toast('Map not loaded yet'); return; }
  let shown = map.cities;
  let dlg;
  const refresh = (q) => {
    const key = normalizeName((q || '').trim());
    shown = key ? map.cities.filter((c) => c.key.includes(key)).sort((a, b) => (a.key.startsWith(key) ? 0 : 1) - (b.key.startsWith(key) ? 0 : 1)) : map.cities;
    dlg.renderItems(shown.slice(0, 200).map((c) => c.name));
  };
  dlg = modal({
    title: asWaypoint ? 'Add waypoint' : 'Navigate to', input: { placeholder: 'City name' }, items: [],
    onItem: (i) => {
      const c = shown[i]; if (!c) return;
      if (asWaypoint) addWaypoint(c.x, c.z, c.name);
      else if (hasDest && nav && nav.active) {
        modal({ title: c.name, items: ['Add as waypoint', 'New destination (replace route)'], buttons: [{ label: 'Cancel' }],
          onItem: (k) => { if (k === 0) addWaypoint(c.x, c.z, c.name); else computeRoute(c.x, c.z, c.name, { announce: true }); } });
      } else computeRoute(c.x, c.z, c.name, { announce: true });
    },
    buttons: [{ label: 'Cancel' }],
  });
  dlg.input.addEventListener('input', () => refresh(dlg.input.value));
  refresh('');
}

// ----------------------------------------------------------------------------------- POI --
function nearestPois(type, limit) {
  if (!map) return [];
  const x = motion.x, z = motion.z, b = motion.bearing;
  const fx = Math.sin(b), fz = -Math.cos(b), rx = Math.cos(b), rz = Math.sin(b);
  const hits = [];
  for (const p of map.pois) {
    if (p.type !== type) continue;
    const dx = p.x - x, dz = p.z - z;
    if (dx > 15000 || dx < -15000 || dz > 15000 || dz < -15000) continue;
    const d = Math.hypot(dx, dz);
    hits.push({ poi: p, dist: d, ahead: (dx * fx + dz * fz) / Math.max(d, 1), right: (dx * rx + dz * rz) / Math.max(d, 1) });
  }
  hits.sort((a, c) => (a.ahead < -0.3 ? a.dist * 2 : a.dist) - (c.ahead < -0.3 ? c.dist * 2 : c.dist)); // things behind count double
  return hits.slice(0, limit);
}
function gameDist(worldM) { const g = worldM * distScale; return g < 1000 ? Math.max(10, Math.round(g / 10) * 10) + ' m' : (g / 1000).toFixed(1) + ' km'; }
const direction = (h) => (h.ahead > 0.5 ? 'ahead' : h.ahead < -0.5 ? 'behind' : h.right > 0 ? 'to the right' : 'to the left');
function showPoiPicker() {
  if (!map) { toast('Map not loaded yet'); return; }
  const types = [POI.FUEL, POI.PARKING, POI.SERVICE, POI.WEIGH, POI.GARAGE, POI.DEALER];
  modal({ title: 'Find nearby', items: types.map((t) => POI_LABEL[t]), onItem: (i) => showPoiList(types[i]), buttons: [{ label: 'Cancel' }] });
}
function showPoiList(type) {
  if (!truck || !truck.connected) { toast('Start the game first - no truck position yet'); return; }
  const hits = nearestPois(type, 15);
  if (!hits.length) { toast('No ' + POI_LABEL[type].toLowerCase() + ' within range'); return; }
  modal({
    title: POI_LABEL[type] + 's nearby', items: hits.map((h) => POI_LABEL[type] + '  –  ' + gameDist(h.dist) + ' ' + direction(h)),
    onItem: (i) => computeRoute(hits[i].poi.x, hits[i].poi.z, POI_LABEL[type], { announce: true, isPoi: true }), buttons: [{ label: 'Cancel' }],
  });
}

// ------------------------------------------------------------------- altitude / alerts --
function updateAltitude(s) {
  elev.add(motion.travelled, s.y);
  const g = motion.gradientPct;
  const arrow = !motion.gradientValid || Math.abs(g) < 0.5 ? '▬' : g > 0 ? '▲' : '▼';
  const gs = motion.gradientValid ? arrow + ' ' + Math.abs(g).toFixed(1) + '%' : arrow;
  const el = $('alt'); el.textContent = gs + '\n' + Math.round(s.y) + ' m';
  el.style.color = motion.gradientValid && g <= -5 ? 'rgb(255,170,90)' : motion.gradientValid && g >= 6 ? 'rgb(255,120,120)' : '#fff';
}
function checkHill(s, now) {
  if (!motion.gradientValid || s.speedKmh < 25) { descStart = -1; climbStart = -1; return; }
  const g = motion.gradientPct, t = motion.travelled;
  if (g <= -5) {
    if (descStart < 0) descStart = t;
    if (t - descStart >= 120 && now - lastDescWarn > 90000) { lastDescWarn = now; voice.say('Steep descent. Use the engine brake.'); toast('Steep descent - engine brake'); }
  } else if (g > -3) descStart = -1;
  if (g >= 6) {
    if (climbStart < 0) climbStart = t;
    if (t - climbStart >= 150 && now - lastClimbWarn > 120000) { lastClimbWarn = now; voice.say('Steep climb. Select a lower gear.'); }
  } else if (g < 4) climbStart = -1;
}
function showElevation() {
  const n = elev.alt.length;
  let lo = 1e30, hi = -1e30; for (const a of elev.alt) { lo = Math.min(lo, a); hi = Math.max(hi, a); }
  const summary = n < 2 ? 'No data yet' : 'Now ' + Math.round(truck ? truck.y : 0) + ' m  ·  climbed ' + Math.round(elev.ascent()) + ' m  ·  descended ' + Math.round(elev.descent()) + ' m  ·  range ' + Math.round(lo) + ' to ' + Math.round(hi) + ' m';
  modal({
    title: 'Trip elevation', message: summary,
    build: (body) => {
      const cv = document.createElement('canvas'); cv.style.cssText = 'width:100%;height:220px;background:#10151c;margin-top:8px;border-radius:6px';
      body.appendChild(cv);
      requestAnimationFrame(() => drawElevation(cv));
    },
    buttons: [{ label: 'Reset trip', fn: () => elev.clear() }, { label: 'Close', primary: true }],
  });
}
function drawElevation(cv) {
  const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
  cv.width = W * dpr; cv.height = H * dpr;
  const c = cv.getContext('2d'); c.setTransform(dpr, 0, 0, dpr, 0, 0);
  const n = elev.alt.length, l = 46, r = W - 12, t = 12, b = H - 24;
  c.font = '12px sans-serif'; c.fillStyle = 'rgba(255,255,255,.85)';
  if (n < 2) { c.textAlign = 'center'; c.fillText('Drive a little to build the profile', W / 2, H / 2); return; }
  let lo = Math.min(...elev.alt), hi = Math.max(...elev.alt);
  if (hi - lo < 10) { const m = (hi + lo) / 2; lo = m - 5; hi = m + 5; }
  const d0 = elev.dist[0], d1 = Math.max(elev.dist[n - 1], d0 + 1);
  const px = (i) => l + (elev.dist[i] - d0) / (d1 - d0) * (r - l), py = (i) => b - (elev.alt[i] - lo) / (hi - lo) * (b - t);
  c.textAlign = 'right'; c.strokeStyle = 'rgba(255,255,255,.2)';
  for (let k = 0; k <= 3; k++) { const y = t + (b - t) * k / 3; c.beginPath(); c.moveTo(l, y); c.lineTo(r, y); c.stroke(); c.fillText(Math.round(hi - (hi - lo) * k / 3) + ' m', l - 4, y + 4); }
  c.beginPath(); c.moveTo(px(0), b); for (let i = 0; i < n; i++) c.lineTo(px(i), py(i)); c.lineTo(px(n - 1), b); c.closePath(); c.fillStyle = 'rgba(90,190,255,.27)'; c.fill();
  c.beginPath(); for (let i = 0; i < n; i++) { if (i) c.lineTo(px(i), py(i)); else c.moveTo(px(i), py(i)); } c.strokeStyle = 'rgb(90,190,255)'; c.lineWidth = 2.2; c.stroke();
  c.textAlign = 'center'; c.fillStyle = 'rgba(255,255,255,.85)';
  c.fillText('0', l, H - 6); c.fillText(Math.round((d1 - d0) * distScale / 1000) + ' km', r - 14, H - 6);
}
function checkAlerts(s) {
  if (!map) return;
  const low = s.fuelWarning || (s.fuelFraction >= 0 && s.fuelFraction <= 0.12);
  if (low && !fuelAlerted) {
    fuelAlerted = true;
    const h = nearestPois(POI.FUEL, 1)[0];
    voice.say('Low fuel.' + (h && nav ? ' Nearest fuel station is ' + nav.spokenDistance(h.dist) + ' ' + direction(h) + '.' : ''));
    toast('Low fuel - tap POI, then Fuel station');
  }
  if (!low && s.fuelFraction > 0.25) fuelAlerted = false;
  const r = s.nextRestMin;
  if (r >= 0) {
    if (r > 60) { rest30 = false; rest10 = false; }
    const mins = r <= 10 ? 10 : 30;
    if ((r <= 10 && !rest10) || (r <= 30 && !rest30)) {
      if (r <= 10) rest10 = true;
      rest30 = true;
      const h = nearestPois(POI.PARKING, 1)[0];
      voice.say('You will need a rest in about ' + mins + ' minutes.' + (h && nav ? ' Nearest rest area is ' + nav.spokenDistance(h.dist) + ' ' + direction(h) + '.' : ''));
    }
  }
}
function updatePlace(now) {
  if (now - lastPlaceCheck < 2000 || !map) return;
  lastPlaceCheck = now;
  let best = null, bd = 4000;
  for (const c of map.cities) {
    const dx = c.x - motion.x, dz = c.z - motion.z;
    if (dx > bd || dx < -bd || dz > bd || dz < -bd) continue;
    const d = Math.hypot(dx, dz); if (d < bd) { bd = d; best = c; }
  }
  place = best ? 'Near ' + best.name : '';
}

// ------------------------------------------------------------------------- trip screen --
function resetTrip() { tripBase = motion.travelled; tripDriveMs = 0; tripMaxKmh = 0; tripFuelUsed = 0; tripLastFuel = -1; elev.clear(); }
function trackTrip(s, now) {
  const dt = Math.min(2000, Math.max(0, now - lastTripMs)); lastTripMs = now;
  if (!s.connected) return;
  if (s.speedKmh > 3) tripDriveMs += dt;
  tripMaxKmh = Math.max(tripMaxKmh, s.speedKmh);
  const f = s.detail ? s.detail.fuelL : -1;
  if (f > 0) { if (tripLastFuel > 0 && f < tripLastFuel) tripFuelUsed += tripLastFuel - f; tripLastFuel = f; }
}
function toggleTrip() {
  tripOpen = !tripOpen;
  const show = tripOpen;
  $('trip').style.display = show ? 'block' : 'none';
  for (const id of ['zoom', 'compass', 'alt', 'banner', 'bottom', 'status']) $(id).style.display = show ? 'none' : (id === 'zoom' ? 'flex' : id === 'alt' ? 'flex' : id === 'banner' ? 'flex' : id === 'bottom' ? 'flex' : 'block');
  $('recentre').style.display = !show && mapView.free ? 'block' : 'none';
  $('btnTrip').textContent = show ? 'Map' : 'Job Info';
  if (show) { setLanes(null); updateTrip(); }
}
const row = (k, v, color) => '<div class="row"><span>' + k + '</span><b' + (color ? ' style="color:' + color + '"' : '') + '>' + v + '</b></div>';
const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const dmgColor = (v) => (v < 5 ? '#fff' : v < 25 ? 'rgb(255,190,90)' : 'rgb(255,120,110)');
function updateTrip() {
  const s = truck, el = $('trip');
  if (!s || !s.connected) { el.innerHTML = '<h2>TRIP DETAILS</h2><div class="card"><h3>WAITING</h3>' + row('Game', 'not connected') + '</div>'; return; }
  const j = s.job, dt = s.detail, n = nav;
  const amber = 'rgb(255,190,90)', red = 'rgb(255,120,110)', green = 'rgb(60,220,130)';
  const routingNow = n && n.active && routeRemainingM >= 0;
  // the Job Info screen always counts in game kilometres (planned distance, range and consumption come from the game), whatever the Real / Sim setting
  const remGameKm = routingNow ? routeRemainingM * simScale / 1000 : (dt ? dt.navDistanceKm : 0);
  const realMin = routingNow && routeEtaGameMin >= 0 ? routeEtaGameMin / TIME_SCALE : -1;
  const simMin = routingNow ? routeEtaGameMin : (s.navEtaMin >= 0 ? s.navEtaMin : -1);
  const planned = j ? j.plannedKm : 0;
  const progress = planned > 0 && remGameKm > 0 ? Math.min(1, Math.max(0, 1 - remGameKm / planned)) : 0;
  const cal = new Date();
  let html = '<h2>TRIP DETAILS ' + (j ? '<small style="color:' + green + '">● ON JOB</small>' : '') + '</h2>';
  html += '<div class="card" style="margin-bottom:10px"><div class="row"><span>FROM <b>' + esc(j ? placeName(j.srcCompany, j.srcCity) : '-') + '</b></span><span>TO <b>' + esc(j ? placeName(j.destCompany, j.destCity) : (hasDest ? destName : '-')) + '</b></span></div>' +
    '<div class="tbar"><div style="width:' + Math.round(progress * 100) + '%"></div></div>' +
    '<div class="row"><span>' + fmt(remGameKm) + ' km left' + (planned > 0 ? ' of ' + fmt(planned) + ' km · ' + Math.round(progress * 100) + '%' : '') + '</span><span>Local ' + cal.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + ' · ETS2 ' + clockText(s.gameTimeMin) + '</span></div></div>';
  html += '<div class="cards">';
  // job
  if (j) {
    const late = j.dueMin >= 0 && simMin >= 0 && simMin > j.dueMin;
    let c = '<h3>JOB</h3>' + row('Cargo', esc(j.cargo) + (j.massKg > 0 ? '  ' + (j.massKg / 1000).toFixed(1) + ' t' : '')) + row('Units', j.units);
    if (j.income > 0) c += row('Income', '€' + fmt(j.income));
    if (j.dueMin >= 0) {
      c += row('Deadline in', formatDuration(j.dueMin) + ' (sim)', late ? red : null);
      if (simMin >= 0) { const sp = j.dueMin - simMin; c += row(late ? 'Running late by' : 'Time to spare', formatDuration(Math.abs(sp)) + ' (sim)', late ? red : green); }
    }
    if (planned > 0) c += row('Planned distance', planned + ' km');
    html += '<div class="card">' + c + '</div>';
  } else html += '<div class="card"><h3>JOB</h3>' + row('Status', 'no active job') + '</div>';
  // route
  let rc = '<h3>ROUTE</h3>' + row('Destination', esc(hasDest ? destName : 'none'));
  if (routingNow) {
    rc += row('Remaining', Math.round(remGameKm).toLocaleString('en') + ' km') + row('Real time left', formatDuration(realMin)) + row('Sim time left', formatDuration(simMin)) + row('Arrive (sim clock)', clockText(s.gameTimeMin + simMin));
    if (lastNextText) rc += row('Next', esc(lastNextText.charAt(0).toUpperCase() + lastNextText.slice(1)));
  } else { rc += row('Game route left', Math.round(remGameKm) + ' km'); if (simMin >= 0) rc += row('Game ETA (sim)', formatDuration(simMin)); }
  rc += row('Route type', routeMode.label);
  html += '<div class="card">' + rc + '</div>';
  // truck
  if (dt) {
    const fr = dt.fuelCapL > 0 ? dt.fuelL / dt.fuelCapL : 0, fc = fr < 0.12 ? red : fr < 0.25 ? amber : null;
    html += '<div class="card"><h3>TRUCK</h3>' + row('Truck', esc(dt.truckName || '-')) + row('Fuel', Math.round(dt.fuelL) + ' / ' + Math.round(dt.fuelCapL) + ' L  (' + Math.round(fr * 100) + '%)', fc) +
      row('Range', fmt(dt.rangeKm) + ' km') + row('Consumption', (dt.consumptionLPerKm * 100).toFixed(1) + ' L/100 km') + row('Odometer', fmt(dt.odometerKm) + ' km') +
      row('Speed', Math.round(Math.max(0, s.speedKmh)) + ' km/h' + (s.speedLimitKmh > 0 ? '  (limit ' + s.speedLimitKmh + ')' : '')) + '</div>';
    if (dt.trailerAttached) {
      html += '<div class="card"><h3>TRAILER &amp; CARGO</h3>' + row('Trailer', esc(dt.trailerName || '-')) + row('Plate', esc(dt.trailerPlate || '-')) +
        row('Chassis damage', Math.round(dt.trailerChassis) + '%', dmgColor(dt.trailerChassis)) + row('Wheels damage', Math.round(dt.trailerWheels) + '%', dmgColor(dt.trailerWheels)) +
        row('Body damage', Math.round(dt.trailerBody) + '%', dmgColor(dt.trailerBody)) + row('Cargo damage', Math.round(dt.cargoDamage) + '%', dmgColor(dt.cargoDamage)) + '</div>';
    } else html += '<div class="card"><h3>TRAILER &amp; CARGO</h3>' + row('Trailer', 'none attached') + '</div>';
  }
  // this trip
  const driven = Math.max(0, motion.travelled - tripBase) * simScale / 1000, drivenM = Math.max(0, motion.travelled - tripBase);
  const avg = tripDriveMs > 1000 ? drivenM / (tripDriveMs / 1000) * 3.6 : 0;
  let tc = '<h3>THIS TRIP</h3>' + row('Distance driven', Math.round(driven) + ' km') + row('Driving time (real)', formatDuration(tripDriveMs / 60000)) + row('Driving time (sim)', formatDuration(tripDriveMs / 60000 * TIME_SCALE)) +
    row('Average speed', Math.round(avg) + ' km/h') + row('Top speed', Math.round(tripMaxKmh) + ' km/h') + row('Climbed / descended', Math.round(elev.ascent()) + ' / ' + Math.round(elev.descent()) + ' m') + row('Fuel used', Math.round(tripFuelUsed) + ' L');
  if (driven > 5 && tripFuelUsed > 0) tc += row('Trip consumption', (tripFuelUsed / driven * 100).toFixed(1) + ' L/100 km');
  html += '<div class="card">' + tc + '</div>';
  // time & rest
  let rt = '<h3>TIME &amp; REST</h3>' + row('Sim clock', clockText(s.gameTimeMin)) + row('Real time', cal.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
  if (s.nextRestMin >= 0) rt += row('Rest needed in', formatDuration(s.nextRestMin) + ' (sim)', s.nextRestMin <= 30 ? red : s.nextRestMin <= 90 ? amber : null);
  if (place) rt += row('Location', esc(place.replace(/^Near /, '')));
  html += '<div class="card">' + rt + '</div></div>';
  el.innerHTML = html;
}

// ------------------------------------------------------------------------------ settings --
function setRouteMode(md, reroute = true) {
  routeMode = md; ls.set('mode', md.key); $('btnMode').textContent = md.short;
  if (router) router.mode = md;
  if (reroute) rerouteForModeChange();
}
function showSettings() {
  modal({
    title: 'Settings',
    build: (body) => {
      const h = (t) => { const d = document.createElement('div'); d.innerHTML = '<b>' + t + '</b>'; d.style.margin = '10px 0 2px'; body.appendChild(d); };
      h('Route type');
      ROUTE_MODES.forEach((md) => {
        const l = document.createElement('label');
        l.innerHTML = '<input type="radio" name="rm" value="' + md.key + '"' + (md.key === routeMode.key ? ' checked' : '') + '> ' + md.help;
        body.appendChild(l);
      });
      const fl = document.createElement('label'); fl.style.marginTop = '8px';
      fl.innerHTML = '<input type="checkbox" id="setFerries"' + (avoidFerries ? ' checked' : '') + '> Avoid ferries and car trains (some places may become unreachable)'; body.appendChild(fl);
      const clr = document.createElement('button'); clr.className = 'inline'; clr.textContent = 'Clear avoided roads (' + avoidPoints.length + ')';
      clr.onclick = () => { clearAvoid(); clr.textContent = 'Clear avoided roads (0)'; }; body.appendChild(clr);
      h('Voice');
      const vl = document.createElement('label'); vl.innerHTML = '<input type="checkbox" id="setVoice"' + (voice.enabled ? ' checked' : '') + '> Voice guidance'; body.appendChild(vl);
      ['Speak on this device', 'Speak on the PC (headset, via companion)', 'Speak on both'].forEach((t, i) => {
        const l = document.createElement('label'); l.innerHTML = '<input type="radio" name="vo" value="' + i + '"' + (voice.output === i ? ' checked' : '') + '> ' + t; body.appendChild(l);
      });
      const tv = document.createElement('button'); tv.className = 'inline'; tv.textContent = 'Test voice';
      tv.onclick = () => { const sel = body.querySelector('input[name=vo]:checked'); voice.output = sel ? parseInt(sel.value, 10) : 0; voice.unlock(); voice.say('Voice guidance is working.'); }; body.appendChild(tv);
      h('Distances');
      const rl = document.createElement('label'); rl.innerHTML = '<input type="checkbox" id="setReal"' + (realDist ? ' checked' : '') + '> Real distances (turns, lane guidance, route length). Off = Sim distances like the game shows'; body.appendChild(rl);
      const sl = document.createElement('div'); sl.style.cssText = 'font-size:13px;color:#aab3c0;margin-top:8px'; sl.textContent = 'Sim scale (game shows map metres x19)'; body.appendChild(sl);
      const sc = document.createElement('input'); sc.type = 'number'; sc.id = 'setScale'; sc.value = simScale; sc.step = 'any'; body.appendChild(sc);
      const help = document.createElement('div'); help.style.cssText = 'font-size:12px;color:#aab3c0;margin-top:10px';
      help.textContent = 'Telemetry and the map come from the companion on your PC. On iPad: Settings > Display > Auto-Lock > Never, and use Share > Add to Home Screen for a full-screen app.';
      body.appendChild(help);
    },
    buttons: [{ label: 'Cancel' }, { label: 'Save + reload map', fn: () => { saveSettings(); loadMap(); } }, { label: 'Save', primary: true, fn: saveSettings }],
  });
}
function setAvoidFerries(on, reroute = true) {
  avoidFerries = on; ls.set('ferries', on);
  if (router) router.avoidFerries = on;
  if (reroute) rerouteForModeChange();
}
function saveSettings() {
  const body = $('modal');
  const rm = body.querySelector('input[name=rm]:checked');
  const md = rm && ROUTE_MODES.find((m) => m.key === rm.value);
  const sc = parseFloat(($('setScale') || {}).value);
  if (sc > 0) { simScale = sc; ls.set('scale', sc); }
  realDist = !!($('setReal') || {}).checked; ls.set('real', realDist);
  distScale = realDist ? 1 : simScale; if (nav) nav.distScale = distScale;
  const vo = body.querySelector('input[name=vo]:checked');
  if (vo) { voice.output = parseInt(vo.value, 10); ls.set('voice_out', voice.output); }
  voice.enabled = !!($('setVoice') || {}).checked; ls.set('voice', voice.enabled); updateVoiceButton();
  const ferryOn = !!($('setFerries') || {}).checked;
  const ferryChanged = ferryOn !== avoidFerries;
  if (ferryChanged) setAvoidFerries(ferryOn, !(md && md.key !== routeMode.key));
  if (md && md.key !== routeMode.key) setRouteMode(md);
}
// cruise control follows the speed limit: the Windows app presses the game's cruise keys; this just switches it on and off
let cruiseOn = false;
function updateCruiseButton() { $('btnCruise').textContent = cruiseOn ? 'Cruise auto' : 'Cruise off'; }
function cruiseCall(on) {
  fetch('/cruise' + (on === undefined ? '' : '?on=' + (on ? 1 : 0)))
    .then((r) => r.json())
    .then((j) => { cruiseOn = !!j.on; updateCruiseButton(); if (on !== undefined) toast(cruiseOn ? 'Cruise control will follow the speed limit while the game is in front' : 'Cruise control stays where you set it'); })
    .catch(() => { if (on !== undefined) toast('Needs the ETS2 GPS Windows app on the PC (it presses the cruise keys)'); });
}
$('btnCruise').onclick = () => cruiseCall(!cruiseOn);
cruiseCall();
function updateVoiceButton() { $('btnVoice').textContent = voice.enabled ? 'Voice on' : 'Voice off'; }

// ---------------------------------------------------------------------------------- wiring --
$('btnTrip').onclick = toggleTrip;
$('btnSearch').onclick = () => showSearch(false);
$('btnStops').onclick = showStops;
$('btnTrips').onclick = showTrips;
$('btnPlan').onclick = () => showPlanner();
$('btnMode').onclick = () => { const next = ROUTE_MODES[(ROUTE_MODES.indexOf(routeMode) + 1) % ROUTE_MODES.length]; setRouteMode(next); toast('Route mode: ' + next.label); voice.say(next.label + ' routing'); };
$('btnVoice').onclick = () => { voice.enabled = !voice.enabled; ls.set('voice', voice.enabled); updateVoiceButton(); voice.unlock(); if (voice.enabled) voice.say('Voice on'); };
$('btnPoi').onclick = showPoiPicker;
$('btnJob').onclick = routeToJob;
$('btnStop').onclick = cancelRoute;
$('btnSettings').onclick = showSettings;
$('zIn').onclick = () => mapView.zoomBy(1.3);
$('zOut').onclick = () => mapView.zoomBy(1 / 1.3);
$('recentre').onclick = () => mapView.recentre();
$('alt').onclick = showElevation;
mapView.onFreeChanged = (f) => { $('recentre').style.display = f && !tripOpen && !mapView.planMode ? 'block' : 'none'; };
$('btnMode').textContent = routeMode.short; updateVoiceButton();

function frame() {
  if (motion.haveFix) {
    const [x, z] = motion.predicted(performance.now());
    mapView.truckX = x; mapView.truckZ = z; mapView.bearing = motion.bearing;
  }
  mapView.draw();
  Compass.draw($('compass'), mapView.viewBearing, mapView.hasFix);
  requestAnimationFrame(frame);
}
layout();
setBannerMessage(null); clearManeuver();
requestAnimationFrame(frame);
telemetry.start();
loadMap();
