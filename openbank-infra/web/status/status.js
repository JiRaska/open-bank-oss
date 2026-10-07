const labels = {
  dns: 'Status domain DNS', tls: 'Status domain TLS', website: 'Public website',
  customer_sign_in: 'Customer sign-in discovery', api_edge: 'API edge',
}

function verifiedDocument(value, now = Date.now()) {
  if (!value || value.schema_version !== 1 || !value.checks || !value.observed_at || !value.expires_at ||
      !Array.isArray(value.history) || !Array.isArray(value.incidents) ||
      !['OPERATIONAL', 'DEGRADED'].includes(value.state)) return false
  const observed = Date.parse(value.observed_at)
  const expiry = Date.parse(value.expires_at)
  if (!Number.isFinite(observed) || !Number.isFinite(expiry) || observed > now || expiry <= now || expiry - observed > 600_000) return false
  return Object.keys(labels).every(name => value.checks[name] && typeof value.checks[name].ok === 'boolean')
}

function historySummary(history, now, hours) {
  const expected = Math.ceil(hours * 60 / 5)
  const samples = Array.isArray(history) ? history.filter(sample => {
    const at = Date.parse(sample?.at)
    return Number.isFinite(at) && at <= now && at >= now - hours * 3_600_000 &&
      sample.checks && Object.keys(labels).every(name => typeof sample.checks[name] === 'boolean')
  }) : []
  // Manual reruns can produce several records in one five-minute slot. Count each
  // slot once, and keep a failure if any check in that slot failed.
  const slots = new Map()
  for (const sample of samples) {
    const slot = Math.floor(Date.parse(sample.at) / 300_000)
    const healthy = Object.values(sample.checks).every(Boolean)
    slots.set(slot, (slots.get(slot) ?? true) && healthy)
  }
  const healthy = [...slots.values()].filter(Boolean).length
  const coverage = Math.min(slots.size / expected, 1)
  return { observed: slots.size, expected, coverage, availability: slots.size ? healthy / slots.size : null }
}

function renderHistory(id, hours, history, now) {
  const result = historySummary(history, now, hours)
  const period = hours === 24 ? '24 hours' : '30 days'
  const coverage = `${result.observed}/${result.expected} expected observations`
  document.getElementById(id).textContent = result.coverage < .95
    ? `${period}: insufficient coverage (${coverage}). Availability is not verified.`
    : `${period}: ${(result.availability * 100).toFixed(2)}% observed availability (${coverage}).`
}

function render(value, now = Date.now()) {
  const valid = verifiedDocument(value, now)
  const activeIncident = valid && Array.isArray(value.incidents) && value.incidents.some(incident => !incident.resolved_at)
  const state = document.getElementById('state')
  state.className = 'state ' + (valid ? (value.state === 'OPERATIONAL' && !activeIncident && Object.values(value.checks).every(check => check.ok) ? 'state-operational' : 'state-degraded') : 'state-unknown')
  state.textContent = !valid ? 'Current status unverified' : activeIncident ? 'Confirmed incident in progress' : state.classList.contains('state-operational') ? 'All public checks responding' : 'One or more public checks failing'
  document.body.classList.toggle('degraded', valid && !state.classList.contains('state-operational'))
  document.body.classList.toggle('incident-active', activeIncident)
  document.getElementById('observed').textContent = valid ? `Observed ${new Date(value.observed_at).toLocaleString()}` : 'Evidence is missing, malformed, or stale. Please do not treat an earlier green state as current.'
  const checks = document.getElementById('checks')
  checks.replaceChildren()
  for (const [name, label] of Object.entries(labels)) {
    const item = document.createElement('li')
    const ok = valid && value.checks[name].ok
    item.className = ok ? 'check-ok' : 'check-down'
    item.textContent = label
    const detail = document.createElement('span')
    detail.textContent = !valid ? 'Unverified' : ok ? 'Responding' : 'Not responding'
    item.append(detail)
    checks.append(item)
  }
  renderHistory('history-24h', 24, valid ? value.history : [], now)
  renderHistory('history-30d', 720, valid ? value.history : [], now)
  const incidents = document.getElementById('incidents')
  incidents.replaceChildren()
  if (!valid || !Array.isArray(value.incidents) || value.incidents.length === 0) {
    const item = document.createElement('li')
    item.textContent = valid ? 'No confirmed incidents published.' : 'Confirmed incident timeline unavailable.'
    incidents.append(item)
  } else {
    for (const incident of value.incidents) {
      const item = document.createElement('li')
      item.textContent = `${incident.summary} — started ${incident.started_at}${incident.resolved_at ? `; recovered ${incident.resolved_at}` : '; recovery not confirmed'}`
      incidents.append(item)
    }
  }
}

async function refresh() {
  try {
    const response = await fetch('/api/status.json', { cache: 'no-store', signal: AbortSignal.timeout(8_000) })
    if (!response.ok) throw new Error('status API unavailable')
    render(await response.json())
  } catch { render(null) }
}

if (typeof document !== 'undefined') {
  void refresh()
  setInterval(refresh, 60_000)
}

if (typeof module !== 'undefined') module.exports = { verifiedDocument, historySummary, render }
