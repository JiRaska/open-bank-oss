// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  assertValidEnvelope,
  mergeSyntheticRunEnvelopes,
} from './merge-synthetic-run-envelopes.mjs'

const COMMIT = 'a'.repeat(40)

const envelope = (overrides = {}) => ({
  schemaVersion: 1,
  component: 'openbank-admin-ui',
  run: {
    id: '100',
    attempt: 1,
    commit: 'b'.repeat(40),
    branch: 'main',
    workflow: 'admin-ui-browser-synthetic.yml',
    url: 'https://github.com/jiraska/open-bank-oss/actions/runs/100',
    observedAt: '2026-10-31T06:00:00Z',
    ...overrides.run,
  },
  suites: [],
  coverage: null,
  testInfrastructure: { declared: [], observed: [] },
  specializedEvidence: [],
  testCases: [],
  diagnostics: [],
  ...Object.fromEntries(Object.entries(overrides).filter(([key]) => key !== 'run')),
})

test('rejects envelopes that are not valid run envelopes', () => {
  assert.throws(() => assertValidEnvelope(null, 'x'), /not a JSON object/)
  assert.throws(() => assertValidEnvelope({ schemaVersion: 3 }, 'x'), /schemaVersion/)
  assert.throws(() => assertValidEnvelope(envelope({ component: 'other' }), 'x'), /component/)
  assert.throws(() => assertValidEnvelope(envelope({ run: { id: '1', attempt: 0 } }), 'x'), /provenance|required|invalid/)
})

test('rejects a non-40-hex target commit and an empty envelope list', () => {
  assert.throws(() => mergeSyntheticRunEnvelopes([envelope()], { commit: 'short' }), /40-hex/)
  assert.throws(() => mergeSyntheticRunEnvelopes([], { commit: COMMIT }), /no envelopes/)
})

test('rejects mixed components', () => {
  const other = envelope({ component: 'openbank-ledger' })
  assert.throws(() => mergeSyntheticRunEnvelopes([envelope(), other], { commit: COMMIT }), /mixed components/)
})

const suite = (kind, counts = {}) => ({
  kind, discovered: 0, executed: 0, passed: 0, failed: 0, skipped: 0, errors: 0, durationMs: 0, ...counts,
})

test('merges suites by kind, summing counts and failing on any failure', () => {
  const e2eChromium = envelope({
    suites: [suite('e2e', { discovered: 6, executed: 6, passed: 6, durationMs: 3000 })],
  })
  const e2eFirefox = envelope({
    run: { id: '100', attempt: 1, commit: 'b'.repeat(40), branch: 'main', workflow: 'admin-ui-browser-synthetic.yml', url: 'https://github.com/jiraska/open-bank-oss/actions/runs/100', observedAt: '2026-10-31T06:05:00Z' },
    suites: [suite('e2e', { discovered: 6, executed: 6, passed: 5, failed: 1, durationMs: 4000 })],
  })
  const merged = mergeSyntheticRunEnvelopes([e2eChromium, e2eFirefox], { commit: COMMIT })
  assert.equal(merged.suites.length, 1)
  assert.deepEqual(merged.suites[0], {
    kind: 'e2e', discovered: 12, executed: 12, passed: 11, failed: 1, skipped: 0, errors: 0,
    durationMs: 7000, state: 'failed',
  })
})

test('a suite every input skipped stays skipped, zero-failure merge passes', () => {
  const a = envelope({ suites: [suite('e2e', { discovered: 2, skipped: 2 })] })
  const b = envelope({ suites: [suite('e2e', { discovered: 2, skipped: 2 })] })
  const merged = mergeSyntheticRunEnvelopes([a, b], { commit: COMMIT })
  assert.equal(merged.suites[0].state, 'skipped')
  const c = envelope({ suites: [suite('e2e', { discovered: 1, executed: 1, passed: 1 })] })
  const d = envelope({ suites: [suite('e2e', { discovered: 1, executed: 1, passed: 1 })] })
  assert.equal(mergeSyntheticRunEnvelopes([c, d], { commit: COMMIT }).suites[0].state, 'passed')
})

test('testCases dedupe by fingerprint with the newest observedAt winning', () => {
  const testCase = fingerprint => ({ fingerprint, kind: 'e2e', classname: 'journey', name: `case-${fingerprint}`, state: 'passed', durationMs: 10 })
  const older = envelope({
    testCases: [testCase('a'.repeat(24)), testCase('b'.repeat(24))],
    specializedEvidence: [{ kind: 'synthetic', state: 'passed', source: 'x', variant: 'chromium' }],
  })
  const newer = envelope({
    run: { id: '101', attempt: 1, commit: 'b'.repeat(40), branch: 'main', workflow: 'admin-ui-browser-synthetic.yml', url: 'https://github.com/jiraska/open-bank-oss/actions/runs/101', observedAt: '2026-10-31T06:10:00Z' },
    testCases: [{ ...testCase('a'.repeat(24)), state: 'failed', durationMs: 30 }],
    specializedEvidence: [{ kind: 'synthetic', state: 'passed', source: 'x', variant: 'firefox' }],
  })
  const merged = mergeSyntheticRunEnvelopes([newer, older], { commit: COMMIT })
  assert.equal(merged.testCases.length, 2)
  const retried = merged.testCases.find(item => item.fingerprint === 'a'.repeat(24))
  assert.equal(retried.state, 'failed')
  assert.equal(retried.durationMs, 30)
  // specializedEvidence keeps every journey/browser row verbatim.
  assert.equal(merged.specializedEvidence.length, 2)
})

test('run provenance comes from the newest envelope with commit rewritten to the deployed one', () => {
  const older = envelope({ run: { id: '100', attempt: 1, commit: 'b'.repeat(40), branch: 'main', workflow: 'admin-ui-browser-synthetic.yml', url: 'https://github.com/jiraska/open-bank-oss/actions/runs/100', observedAt: '2026-10-31T06:00:00Z' } })
  const newer = envelope({ run: { id: '102', attempt: 2, commit: 'c'.repeat(40), branch: 'main', workflow: 'admin-ui-browser-synthetic.yml', url: 'https://github.com/jiraska/open-bank-oss/actions/runs/102', observedAt: '2026-10-31T06:20:00Z' } })
  const merged = mergeSyntheticRunEnvelopes([newer, older], { commit: COMMIT })
  assert.equal(merged.run.id, '102')
  assert.equal(merged.run.attempt, 2)
  assert.equal(merged.run.commit, COMMIT)
  assert.equal(merged.run.observedAt, '2026-10-31T06:20:00Z')
})

test('infrastructure declared resources union and diagnostics dedupe by name', () => {
  const a = envelope({
    testInfrastructure: { declared: ['postgres'], observed: [{ resource: 'postgres', image: 'i', lifecycle: 'started', observedAt: '2026-10-31T06:00:00Z' }] },
    diagnostics: [{ kind: 'playwright-report', suiteKind: 'e2e', name: 'report-1', url: 'https://github.com/jiraska/open-bank-oss/actions/runs/100#artifacts', retentionDays: 7, access: 'github-run-authenticated', mayContainSensitiveData: true }],
  })
  const b = envelope({
    testInfrastructure: { declared: ['keycloak'], observed: [] },
    diagnostics: [{ kind: 'playwright-report', suiteKind: 'e2e', name: 'report-1', url: 'https://github.com/jiraska/open-bank-oss/actions/runs/100#artifacts', retentionDays: 7, access: 'github-run-authenticated', mayContainSensitiveData: true }],
  })
  const merged = mergeSyntheticRunEnvelopes([a, b], { commit: COMMIT })
  assert.deepEqual(merged.testInfrastructure.declared, ['keycloak', 'postgres'])
  assert.equal(merged.testInfrastructure.observed.length, 1)
  assert.equal(merged.diagnostics.length, 1)
})
