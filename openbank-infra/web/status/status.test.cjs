const assert = require('node:assert/strict')
const test = require('node:test')
const { verifiedDocument, historySummary } = require('./status.js')

const names = ['dns', 'tls', 'website', 'customer_sign_in', 'api_edge']
const now = Date.parse('2026-10-07T12:00:00Z')
const fresh = {
  schema_version: 1,
  observed_at: '2026-10-07T11:59:00Z',
  expires_at: '2026-10-07T12:09:00Z',
  state: 'OPERATIONAL',
  checks: Object.fromEntries(names.map(name => [name, { ok: true }])),
}

test('status is unverified when evidence expires or a required check disappears', () => {
  assert.equal(verifiedDocument(fresh, now), true)
  assert.equal(verifiedDocument(fresh, now + 10 * 60_000), false)
  assert.equal(verifiedDocument({ ...fresh, checks: { dns: { ok: true } } }, now), false)
  assert.equal(verifiedDocument({ ...fresh, observed_at: '2026-10-07T12:01:00Z' }, now), false)
})

test('observed availability does not imply 24-hour coverage', () => {
  const sample = { at: '2026-10-07T11:55:00Z', checks: Object.fromEntries(names.map(name => [name, true])) }
  const summary = historySummary([sample], now, 24)
  assert.equal(summary.observed, 1)
  assert.equal(summary.availability, 1)
  assert.ok(summary.coverage < .95)
})

test('failed samples lower availability and missing samples do not count healthy', () => {
  const green = Object.fromEntries(names.map(name => [name, true]))
  const failed = { ...green, customer_sign_in: false }
  const summary = historySummary([
    { at: '2026-10-07T11:55:00Z', checks: green },
    { at: '2026-10-07T11:50:00Z', checks: failed },
    { at: '2026-10-07T11:45:00Z', checks: { dns: true } },
  ], now, 24)
  assert.equal(summary.observed, 2)
  assert.equal(summary.availability, .5)
})
