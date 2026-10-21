// Auto-hide for the left menu: it fades away after a few seconds without being touched; a thin grip on the left edge brings it back.
(function () {
  const menu = document.getElementById('menu');
  if (!menu) return;
  const grip = document.createElement('div');
  grip.id = 'menuGrip';
  grip.innerHTML = '<i></i>';
  menu.parentNode.insertBefore(grip, menu.nextSibling);
  let shown = true, idle = performance.now();
  const touch = () => { idle = performance.now(); };
  const set = (show) => {
    if (shown === show) return;
    shown = show;
    menu.classList.toggle('away', !show);
    grip.style.display = show ? 'none' : 'block';
  };
  ['pointerdown', 'pointermove', 'pointerenter', 'wheel', 'scroll', 'focusin'].forEach((ev) => menu.addEventListener(ev, touch, { passive: true }));
  const bring = () => { touch(); set(true); };
  grip.addEventListener('pointerenter', bring);
  grip.addEventListener('pointerdown', bring);
  setInterval(() => { if (shown && performance.now() - idle > 4500 && !menu.matches(':hover')) set(false); }, 400);
})();
