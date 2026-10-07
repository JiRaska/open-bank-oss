/* Public status UI. Never infer healthy from a missing or stale API response. */
(() => {
  'use strict';
  const refreshMs = 60_000;
  const staleMs = 10 * 60_000;
  const labels = {
    operational: ['All monitored checks pass', 'Latest external checks passed.', '✓'],
    degraded: ['Some checks are degraded', 'Confirmed impact under investigation.', '!'],
    partial_outage: ['Partial service outage', 'Confirmed impact under investigation.', '!'],
    major_outage: ['Major service outage', 'Confirmed impact under investigation.', '!'],
    unknown: ['Unable to verify service status', 'Fresh monitoring data is unavailable.', '?']
  };
  const icons = { website: '◎', customer_login: '◈', api_edge: '↗' };
  const $ = id => document.getElementById(id);
  let currentWindow = '24h';
  let latest = null;

  function el(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content !== undefined) node.textContent = String(content);
    return node;
  }
  function timeText(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? 'Unknown time' : new Intl.DateTimeFormat('en-GB', {timeZone:'UTC', day:'numeric', month:'short', year:'numeric', hour:'2-digit', minute:'2-digit'}).format(date) + ' UTC';
  }
  function fresh(data) {
    const age = Date.now() - new Date(data.checkedAt).getTime();
    return Number.isFinite(age) && age >= -60_000 && age <= staleMs;
  }
  function statusOf(data) {
    return data && fresh(data) && labels[data.status] ? data.status : 'unknown';
  }
  function renderOverall(data) {
    const status = statusOf(data);
    const banner = $('overall-banner');
    const [title, detail, icon] = labels[status];
    banner.className = 'overall-banner is-' + (status === 'major_outage' || status === 'partial_outage' ? 'outage' : status);
    $('overall-label').textContent = title;
    $('overall-detail').textContent = data?.message || detail;
    banner.querySelector('.overall-icon').textContent = icon;
    $('last-check').textContent = 'Last checked: ' + (data?.checkedAt ? timeText(data.checkedAt) : '—');
    document.body.classList.toggle('is-incident', status === 'degraded' || status.includes('outage'));
    document.querySelector('.mascot-repair').setAttribute('aria-hidden', status === 'operational' || status === 'unknown' ? 'true' : 'false');
    document.querySelector('.mascot-operational').setAttribute('aria-hidden', status === 'degraded' || status.includes('outage') ? 'true' : 'false');
  }
  function renderComponents(data) {
    const root = $('components');
    root.replaceChildren();
    const components = Array.isArray(data?.components) ? data.components : [];
    if (!components.length) {
      const card = el('article', 'service-card');
      card.append(el('div', 'service-icon', '?'), el('h3', '', 'Checks unavailable'), el('p', '', 'Recent results are unavailable.'));
      root.append(card);
      return;
    }
    for (const item of components) {
      const status = fresh(data) && (labels[item.status] || item.status === 'outage' || item.status === 'pending') ? item.status : 'unknown';
      const card = el('article', 'service-card');
      const head = el('div', 'service-head');
      const tag = el('span', 'service-status ' + (status.includes('outage') ? 'outage' : status), status.replaceAll('_', ' '));
      head.append(el('span', 'service-icon', icons[item.id] || '◉'), tag);
      const rule = el('div', 'service-rule');
      const detail = el('div', 'service-detail');
      detail.append(el('span', '', item.coverage || 'External check'), el('strong', '', status === 'unknown' ? 'Unverified' : item.lastResult || 'Latest check'));
      card.append(head, el('h3', '', item.name || 'Service'), el('p', '', item.description || ''), rule, detail);
      root.append(card);
    }
  }
  function renderHistory(data) {
    const history = data?.history?.[currentWindow];
    const value = $('availability-value');
    value.replaceChildren();
    if (!history || !Number.isFinite(history.availabilityPercent) || history.totalSamples < 1) {
      value.append(document.createTextNode('— '), el('span', '', 'insufficient data'));
    } else {
      value.append(document.createTextNode(history.availabilityPercent.toFixed(2) + '% '), el('span', '', 'observed'));
    }
    $('samples-value').textContent = history?.totalSamples ?? '—';
    $('history-start').textContent = currentWindow === '24h' ? '24 hours ago' : '30 days ago';
    $('history-end').textContent = 'Now · UTC';
    const bars = $('history-bars');
    bars.replaceChildren();
    const points = Array.isArray(history?.buckets) ? history.buckets : [];
    for (const point of points) {
      const type = point.total === 0 ? 'unknown' : point.success === point.total ? 'good' : point.success === 0 ? 'bad' : 'mixed';
      const tip = timeText(point.start) + ': ' + point.success + '/' + point.total + ' successful samples';
      const bar = el('span', 'history-bar ' + type);
      bar.dataset.tip = tip;
      bars.append(bar);
    }
    if (!points.length) for (let i = 0; i < (currentWindow === '24h' ? 48 : 30); i++) bars.append(el('span', 'history-bar unknown'));
    const observed = history?.totalSamples ?? 0;
    bars.setAttribute('aria-label', `${currentWindow === '24h' ? '24-hour' : '30-day'} website probe history: ${observed} observed samples, ${history?.availabilityPercent ?? 'unknown'} percent availability. Striped bars indicate missing data.`);
    $('history-note').textContent = observed ? `${observed} valid checks. Missing intervals excluded.` : 'No valid checks. Missing intervals are unverified.';
  }
  function renderIncidents(data) {
    const root = $('incident-list');
    root.replaceChildren();
    if (!data || !fresh(data)) {
      const row = el('div', 'empty-state');
      row.append(el('span', 'empty-mark', '?'));
      const copy = el('div'); copy.append(el('strong', '', 'Incident history unavailable'));
      row.append(copy); root.append(row); return;
    }
    const incidents = Array.isArray(data.incidents) ? data.incidents : [];
    if (!incidents.length) {
      const row = el('div', 'empty-state');
      row.append(el('span', 'empty-mark', '✓'));
      const copy = el('div'); copy.append(el('strong', '', 'No confirmed incidents in the current record'));
      row.append(copy); root.append(row); return;
    }
    for (const item of incidents.slice(0, 15)) {
      const row = el('article', 'incident');
      const title = el('div', 'incident-title-row');
      title.append(el('h3', '', item.title || 'Service incident'), el('time', '', timeText(item.startedAt)));
      row.append(title, el('p', '', item.summary || 'Our team is investigating.'), el('span', 'incident-state', item.resolvedAt ? 'Resolved · ' + timeText(item.resolvedAt) : 'Investigating'));
      root.append(row);
    }
  }
  function renderBudget() {
    const target = Number($('slo-select').value);
    const seconds = Math.round((1 - target / 100) * 30 * 24 * 60 * 60);
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const remainingSeconds = seconds % 60;
    $('budget-value').textContent = (hours ? hours + 'h ' : '') + minutes + 'm' + (remainingSeconds ? ' ' + remainingSeconds + 's' : '');
    const fraction = (target / 100).toFixed(5).replace(/0+$/, '').replace(/\.$/, '');
    $('budget-formula').textContent = `(1 − ${fraction}) × 30 × 24 × 60 minutes`;
    $('budget-ring').style.background = `conic-gradient(#65d4ff 0 ${target}%, #2a578a ${target}%)`;
  }
  async function refresh() {
    try {
      const response = await fetch('/api/v1/status', {cache:'no-store', headers:{Accept:'application/json'}});
      const data = await response.json();
      if (!response.ok || !data || data.schemaVersion !== 1) throw new Error('Invalid status response');
      latest = data;
    } catch (_) {
      latest = null;
    }
    renderOverall(latest); renderComponents(latest); renderHistory(latest); renderIncidents(latest);
  }
  document.querySelectorAll('[data-window]').forEach(button => button.addEventListener('click', () => {
    currentWindow = button.dataset.window;
    document.querySelectorAll('[data-window]').forEach(b => { const active = b === button; b.classList.toggle('active', active); b.setAttribute('aria-pressed', String(active)); });
    renderHistory(latest);
  }));
  $('slo-select').addEventListener('change', renderBudget);
  $('year').textContent = String(new Date().getUTCFullYear());
  renderBudget(); refresh(); setInterval(refresh, refreshMs);
})();
