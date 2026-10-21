// Pictograms for the map's points of interest, drawn white on a coloured badge.
// Each icon is an SVG path on a 24 x 24 grid, filled with the even-odd rule. The same paths are used by the Windows and Android apps.
const POI_ICONS = {
  // fuel pump
  1: 'M5 3h9a1 1 0 0 1 1 1v17h1v1H4v-1h1z M7 5v5h6V5z M15 9h1.5a2 2 0 0 1 2 2v5a.5 .5 0 0 0 1 0v-6l-2-2 1-1 3 3v6a2.5 2.5 0 0 1-5 0v-5H15z',
  // service centre: spanner
  2: 'M20.5 6.8a5 5 0 0 1-6.6 5.9L7 19.6a2 2 0 0 1-2.8-2.8l6.9-6.9A5 5 0 0 1 17.2 3.5l-3 3 .8 2.8 2.8 .8z',
  // rest area / parking: the parking sign
  3: 'M7 3h6.5a5 5 0 0 1 0 10H11v8H7z M11 7v2.5h2.5a1.25 1.25 0 0 0 0-2.5z',
  // weigh station: scales
  4: 'M3 6h18v2H3z M11 3h2v16h-2z M7 19h10v2H7z M5 8l-3.2 7a3.2 3.2 0 0 0 6.4 0z M19 8l-3.2 7a3.2 3.2 0 0 0 6.4 0z',
  // truck dealer: truck
  5: 'M1.5 5h12v11h-12z M14.5 8h3.8l3.2 3.7V16h-7z M5 16.5a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 1 0-4.4 0z M15.6 16.5a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 1 0-4.4 0z',
  // garage: building with a roller door
  6: 'M2 10l10-7 10 7v11H2z M6 21h12V12H6z M8 14h8v1.5H8z M8 17h8v1.5H8z',
  // recruitment agency: person
  7: 'M12 3.5a3.7 3.7 0 1 0 0 7.4 3.7 3.7 0 1 0 0-7.4z M4.5 21c0-4.4 3.2-7.4 7.5-7.4s7.5 3 7.5 7.4z',
  // hotel: bed
  8: 'M3 5h2v9h16v6h-2v-3H5v3H3z M7 8h4a2 2 0 0 1 2 2v2H7z M14 9h4a3 3 0 0 1 3 3v2h-7z',
  // ferry terminal: ship
  9: 'M2 14.5l2.6 5.5h14.8l2.6-5.5z M5.5 12V8h13v4z M9.5 8V4.5h5V8z',
  // car train terminal: train
  10: 'M7 3h10a2 2 0 0 1 2 2v10a3 3 0 0 1-3 3l2 3h-2l-1.5-2h-5L8 21H6l2-3a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2z M7 5.5v5h10v-5z M8 13.4a1.3 1.3 0 1 0 2.6 0 1.3 1.3 0 1 0-2.6 0z M13.4 13.4a1.3 1.3 0 1 0 2.6 0 1.3 1.3 0 1 0-2.6 0z',
};
const POI_COLORS = { 1: '#32cd32', 2: '#78828f', 3: '#286edc', 4: '#965abe', 5: '#be3c3c', 6: '#3c8c78', 7: '#a08c28', 8: '#5a5aaa', 9: '#0078aa', 10: '#6e6e7c' };
const POI_PATHS = {};
for (const k in POI_ICONS) { try { POI_PATHS[k] = new Path2D(POI_ICONS[k]); } catch (e) { /* no Path2D: letters fall back */ } }

// Draws the badge for a point of interest centred on (x, y); r is half the badge size in pixels.
function drawPoiIcon(g, type, x, y, r) {
  const p = POI_PATHS[type];
  g.save();
  g.translate(x, y);
  g.shadowColor = 'rgba(0,0,0,0.45)'; g.shadowBlur = r * 0.35; g.shadowOffsetY = r * 0.12;
  g.fillStyle = POI_COLORS[type] || '#808080';
  g.beginPath();
  if (g.roundRect) g.roundRect(-r, -r, 2 * r, 2 * r, r * 0.42); else g.rect(-r, -r, 2 * r, 2 * r);
  g.fill();
  g.shadowColor = 'transparent';
  g.lineWidth = Math.max(1.5, r * 0.1); g.strokeStyle = 'rgba(255,255,255,0.92)';
  g.stroke();
  if (p) {
    const s = (2 * r * 0.66) / 24;
    g.scale(s, s); g.translate(-12, -12);
    g.fillStyle = '#fff';
    g.fill(p, 'evenodd');
  }
  g.restore();
}
