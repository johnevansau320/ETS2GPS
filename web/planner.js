'use strict';
/* Trip planner: choose a start, stops and a destination, preview the route on the map and save it as a trip, all without the
 * game running (the route does not start from the truck). A saved trip can be driven later from wherever the truck is.
 * Uses the globals of app.js (map, router, motion, truck, modal, toast, ls, ...). */

const newPlan = () => ({ start: null, stops: [], dest: null, editName: null, len: -1, time: 0 });
let plan = newPlan();
const copyPt = (p) => ({ x: p.x, z: p.z, name: p.name });
const haveTruckFix = () => !!(truck && truck.connected && (motion.x !== 0 || motion.z !== 0));
const myPosition = () => ({ x: motion.x, z: motion.z, name: (place || '').replace(/^Near /, '') || 'My position' });

// ------------------------------------------------------------------------------ planner dialog --
function showPlanner() {
  if (!map || !router) { toast('Map not loaded yet'); return; }
  const rows = [], acts = [];
  rows.push('Start:  ' + (plan.start ? plan.start.name : '(tap to choose, or leave empty to start from the truck)')); acts.push(startOptions);
  plan.stops.forEach((s, i) => { rows.push('Stop ' + (i + 1) + ':  ' + s.name); acts.push(() => planStopOptions(i)); });
  rows.push('Destination:  ' + (plan.dest ? plan.dest.name : '(tap to choose)')); acts.push(destOptions);
  const summary = plan.len >= 0
    ? gameDist(plan.len) + '  ·  Real ' + formatDuration(plan.time / 60) + '  ·  Sim ' + formatDuration(plan.time / 60 * TIME_SCALE)
    : 'Plan a trip without the game running: choose a start, add stops, choose the destination, then preview it on the map.';
  modal({
    title: plan.editName ? 'Plan trip: ' + plan.editName : 'Plan a trip', message: summary, items: rows, onItem: (i) => acts[i](),
    buttons: [
      { label: 'Close' },
      { label: 'Clear', fn: () => { plan = newPlan(); setTimeout(showPlanner, 0); } },
      { label: 'Add stop', fn: () => setTimeout(() => pickPlace('Add a stop', (c) => { plan.stops.push(c); plan.len = -1; }), 0) },
      { label: 'Save trip', fn: () => setTimeout(savePlan, 0) },
      { label: 'Preview on map', primary: true, fn: () => setTimeout(previewPlan, 0) },
    ],
  });
}

// City search; picking calls onPick(point) and returns to the planner.
function pickPlace(title, onPick) {
  if (!map) return;
  let shown = map.cities, dlg;
  const refresh = (q) => {
    const key = normalizeName((q || '').trim());
    shown = key ? map.cities.filter((c) => c.key.includes(key)).sort((a, b) => (a.key.startsWith(key) ? 0 : 1) - (b.key.startsWith(key) ? 0 : 1)) : map.cities;
    dlg.renderItems(shown.slice(0, 200).map((c) => c.name));
  };
  dlg = modal({
    title, input: { placeholder: 'City name' }, items: [],
    onItem: (i) => { const c = shown[i]; if (c) onPick({ x: c.x, z: c.z, name: c.name }); setTimeout(showPlanner, 0); },
    buttons: [{ label: 'Cancel', fn: () => { setTimeout(showPlanner, 0); } }],
  });
  dlg.input.addEventListener('input', () => refresh(dlg.input.value));
  refresh('');
}

function startOptions() {
  const items = ['Search for a city…'];
  if (haveTruckFix()) items.push('Use my current position');
  if (plan.start) items.push('Clear the start');
  modal({
    title: 'Start of the trip', items, buttons: [{ label: 'Cancel', fn: () => { setTimeout(showPlanner, 0); } }],
    onItem: (i) => {
      const it = items[i];
      if (it.startsWith('Search')) { setTimeout(() => pickPlace('Start of the trip', (c) => { plan.start = c; plan.len = -1; }), 0); return; }
      plan.start = it.startsWith('Use') ? myPosition() : null; plan.len = -1;
      setTimeout(showPlanner, 0);
    },
  });
}

function destOptions() {
  const items = ['Search for a city…'];
  if (plan.dest) items.push('Clear the destination');
  modal({
    title: 'Destination', items, buttons: [{ label: 'Cancel', fn: () => { setTimeout(showPlanner, 0); } }],
    onItem: (i) => {
      if (i === 0) { setTimeout(() => pickPlace('Destination', (c) => { plan.dest = c; plan.len = -1; }), 0); return; }
      plan.dest = null; plan.len = -1; setTimeout(showPlanner, 0);
    },
  });
}

function planStopOptions(i) {
  const w = plan.stops[i]; if (!w) { showPlanner(); return; }
  modal({
    title: w.name, items: ['Replace it (search)…', 'Move earlier', 'Move later', 'Remove'], buttons: [{ label: 'Cancel', fn: () => { setTimeout(showPlanner, 0); } }],
    onItem: (k) => {
      if (k === 0) { setTimeout(() => pickPlace('Replace stop ' + (i + 1), (c) => { plan.stops[i] = c; plan.len = -1; }), 0); return; }
      if (k === 1 && i > 0) { plan.stops.splice(i, 1); plan.stops.splice(i - 1, 0, w); }
      else if (k === 2 && i < plan.stops.length - 1) { plan.stops.splice(i, 1); plan.stops.splice(i + 1, 0, w); }
      else if (k === 3) plan.stops.splice(i, 1);
      plan.len = -1; setTimeout(showPlanner, 0);
    },
  });
}

// ---------------------------------------------------------------------------------- routing --
// Routes start -> stops -> destination (the start is not the truck) and fills the preview line. Returns an error text or null.
function computePlan() {
  const r = router, m = map;
  if (!r || !m) return 'Map not loaded yet';
  const start = plan.start || (haveTruckFix() ? myPosition() : null);
  if (!start) return 'Choose a start first (or start the game)';
  if (!plan.dest) return 'Choose a destination first';
  const pts = [start, ...plan.stops, plan.dest];
  const edges = []; let len = 0, time = 0, lastDe = -1;
  for (let i = 0; i + 1 < pts.length; i++) {
    const b = r.nearestMainNode(pts[i + 1].x, pts[i + 1].z);
    if (b < 0) return pts[i + 1].name + ' is not reachable by road';
    const a = lastDe < 0 ? r.nearestMainNode(pts[i].x, pts[i].z) : m.deTo(lastDe);
    if (a < 0) return pts[i].name + ' is not reachable by road';
    if (a === b) continue;
    let best = null;
    const n = r.outCount(a);
    for (let k = 0; k < n && k < 4; k++) {
      const de = r.outAt(a, k);
      if (lastDe >= 0 && n > 1 && (de >> 1) === (lastDe >> 1)) continue;   // no U-turn at a stop
      if (r.avoidFerries && m.isFerry(de >> 1)) continue;
      const res = r.route(de, m.eLen[de >> 1], b);
      if (res && (!best || res.timeS < best.timeS)) best = res;
    }
    if (!best) return r.avoidFerries ? 'No route without ferries - allow ferries in Settings' : 'No route to ' + pts[i + 1].name;
    for (const de of best.edges) edges.push(de);
    len += best.lengthM; time += best.timeS; lastDe = best.edges[best.edges.length - 1];
  }
  if (!edges.length) return 'The start and the destination are the same place';
  let total = 0;
  for (const de of edges) total += m.deCount(de);
  const line = new Float32Array(total * 2), starts = new Int32Array(edges.length);
  let p = 0;
  edges.forEach((de, i) => {
    starts[i] = p;
    for (let k = 0; k < m.deCount(de); k++) { line[p * 2] = m.deX(de, k); line[p * 2 + 1] = m.deZ(de, k); p++; }
  });
  mapView.planPts = line; mapView.planEdgePt = starts;
  plan.len = len; plan.time = time;
  mapView.pins = [{ x: start.x, z: start.z, label: 'S', color: 'rgb(30,150,80)' }]
    .concat(plan.stops.map((s, i) => ({ x: s.x, z: s.z, label: String(i + 1), color: 'rgb(25,118,210)' })))
    .concat([{ x: plan.dest.x, z: plan.dest.z, label: 'D', color: 'rgb(200,60,60)' }]);
  return null;
}

function previewPlan() {
  toast('Calculating the route…');
  setTimeout(() => {
    let err;
    try { err = computePlan(); } catch (e) { err = 'Routing failed: ' + (e.message || e); }
    if (err) { toast(err); showPlanner(); return; }
    enterPlanView();
  }, 30);
}

function enterPlanView() {
  const pts = mapView.planPts;
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (let i = 0; i + 1 < pts.length; i += 2) {
    minX = Math.min(minX, pts[i]); maxX = Math.max(maxX, pts[i]); minZ = Math.min(minZ, pts[i + 1]); maxZ = Math.max(maxZ, pts[i + 1]);
  }
  const w = Math.max(300, mapView.W), h = Math.max(200, mapView.H);
  const spanX = Math.max(200, maxX - minX), spanZ = Math.max(200, maxZ - minZ);
  const pxPerM = Math.min((w - 150) * 0.85 / spanX, h * 0.5 / spanZ);
  const zoom = Math.min(4, Math.max(0.004, pxPerM / 0.55));
  mapView.showPlan((minX + maxX) / 2 - 60 / pxPerM, (minZ + maxZ) / 2 + h * 0.08 / pxPerM, zoom);   // clear of the left menu
  $('recentre').style.display = 'none';
  updatePlanBar();
  $('planbar').style.display = 'block';
}

function updatePlanBar() {
  const from = plan.start ? plan.start.name : (haveTruckFix() ? myPosition().name : '?');
  const stops = plan.stops.length ? '  ·  ' + plan.stops.length + ' stop' + (plan.stops.length > 1 ? 's' : '') : '';
  $('planText').textContent = from + ' → ' + (plan.dest ? plan.dest.name : '') + stops + '\n' +
    gameDist(plan.len) + '  ·  Real ' + formatDuration(plan.time / 60) + '  ·  Sim ' + formatDuration(plan.time / 60 * TIME_SCALE);
}

function exitPlanView() {
  if (!mapView.planMode) return;
  $('planbar').style.display = 'none';
  mapView.hidePlan();
  $('recentre').style.display = 'none';
}

// Long-press on the map while planning: use that spot as the start, a stop or the destination.
function planPointMenu(x, z) {
  const items = ['Set as the start', 'Add as a stop', 'Set as the destination'];
  modal({
    title: 'This point on the map', items, buttons: [{ label: 'Cancel' }],
    onItem: (i) => {
      const pt = { x, z, name: 'Selected point' };
      if (i === 0) plan.start = pt; else if (i === 1) plan.stops.push(pt); else plan.dest = pt;
      plan.len = -1;
      previewPlan();
    },
  });
}

// ------------------------------------------------------------------------------ save / drive --
function savePlan() {
  if (!plan.dest) { toast('Choose a destination first'); showPlanner(); return; }
  if (plan.len < 0) {
    let err;
    try { err = computePlan(); } catch (e) { err = 'Routing failed: ' + (e.message || e); }
    if (err) { toast(err); showPlanner(); return; }
  }
  const def = plan.editName || ((plan.start ? plan.start.name : 'Trip') + ' → ' + plan.dest.name);
  modal({
    title: 'Save this trip as', input: { value: def },
    buttons: [{ label: 'Cancel' }, { label: 'Save', primary: true, fn: (v) => { saveTripNow((v || '').trim() || def); } }],
  });
}

function saveTripNow(name) {
  if (!plan.dest) return;
  const list = loadTrips().filter((t) => t.name !== name && !(plan.editName && t.name === plan.editName));
  list.unshift({
    name, mode: routeMode.key, ferries: avoidFerries, start: plan.start ? copyPt(plan.start) : null, dest: copyPt(plan.dest),
    stops: plan.stops.map(copyPt), len: plan.len,
  });
  ls.set('trips', list); plan.editName = name; toast('Trip saved: ' + name);
  if (mapView.planMode) updatePlanBar();
}

function drivePlan() {
  if (!plan.dest) return;
  const trip = { mode: routeMode.key, ferries: avoidFerries, start: plan.start, dest: plan.dest, stops: plan.stops };
  exitPlanView();
  startSavedTrip(trip, true);
}

// Opens a saved trip in the planner (to look at it on the map, change it and save it again).
function editSavedTrip(t) {
  const md = ROUTE_MODES.find((m) => m.key === t.mode); if (md) setRouteMode(md, false);
  setAvoidFerries(!!t.ferries, false);
  plan = { start: t.start ? copyPt(t.start) : null, stops: t.stops.map(copyPt), dest: copyPt(t.dest), editName: t.name, len: -1, time: 0 };
  showPlanner();
}

$('planEdit').onclick = () => { exitPlanView(); showPlanner(); };
$('planSave').onclick = () => savePlan();
$('planDrive').onclick = drivePlan;
$('planDone').onclick = exitPlanView;
