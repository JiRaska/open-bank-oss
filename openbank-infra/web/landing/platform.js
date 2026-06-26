// ===== Platform page animations =====
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

// easeOutCubic count-up
function countUp(el, target, decimals, prefix) {
  if (reduce) { el.textContent = prefix + target.toFixed(decimals); return; }
  const dur = 1100, t0 = performance.now();
  function tick(now) {
    const p = Math.min(1, (now - t0) / dur);
    const e = 1 - Math.pow(1 - p, 3);
    el.textContent = prefix + (target * e).toFixed(decimals);
    if (p < 1) requestAnimationFrame(tick);
    else el.textContent = prefix + target.toFixed(decimals);
  }
  requestAnimationFrame(tick);
}

function runCounts(scope) {
  scope.querySelectorAll('[data-count]').forEach(el => {
    if (el.dataset.done) return;
    el.dataset.done = '1';
    const raw = el.dataset.count;
    const decimals = raw.includes('.') ? (raw.split('.')[1].length) : 0;
    countUp(el, parseFloat(raw), decimals, el.dataset.prefix || '');
  });
}

// coverage rings: animate the conic gradient + label
function runRings(scope) {
  scope.querySelectorAll('.reg-card').forEach((card, i) => {
    if (card.dataset.done) return;
    card.dataset.done = '1';
    const cov = parseInt(card.dataset.cov, 10);
    const ring = card.querySelector('.reg-ring');
    const label = ring.querySelector('span');
    const delay = i * 90;
    setTimeout(() => {
      if (reduce) { ring.style.setProperty('--ring', cov); label.innerHTML = cov + '<i>%</i>'; return; }
      const dur = 1000, t0 = performance.now();
      (function tick(now) {
        const p = Math.min(1, (now - t0) / dur);
        const e = 1 - Math.pow(1 - p, 3);
        const v = cov * e;
        ring.style.setProperty('--ring', v.toFixed(1));
        label.innerHTML = Math.round(v) + '<i>%</i>';
        if (p < 1) requestAnimationFrame(tick);
      })(performance.now());
    }, delay);
  });
}

// finops bars
function runBars(scope) {
  const rows = scope.querySelectorAll('.bar-row');
  rows.forEach((row, i) => {
    if (row.dataset.done) return;
    row.dataset.done = '1';
    setTimeout(() => { row.querySelector('.bar-fill').style.width = row.dataset.w + '%'; }, i * 70);
  });
  runCounts(scope);
}

// single observer dispatching by section
const obs = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    const el = e.target;
    if (el.classList.contains('reg-grid')) runRings(el);
    else if (el.classList.contains('finops-wrap')) runBars(el);
    else runCounts(el);
    obs.unobserve(el);
  }
}, { threshold: 0.2, rootMargin: '0px 0px -30px 0px' });

['.reg-grid', '.finops-wrap', '.bcp-status', '.ai-posture'].forEach(sel => {
  document.querySelectorAll(sel).forEach(el => obs.observe(el));
});
