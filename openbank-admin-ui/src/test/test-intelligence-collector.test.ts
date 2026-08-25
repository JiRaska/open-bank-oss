// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { afterEach, describe, expect, it } from 'vitest'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

const SCRIPT = path.resolve(__dirname, '../../scripts/collect-test-intelligence.mjs')
const dirs: string[] = []
const write = (root: string, relative: string, body: string) => {
  const file = path.join(root, relative)
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, body)
}
afterEach(() => dirs.splice(0).forEach(dir => rmSync(dir, { recursive: true, force: true })))

describe('test-intelligence collector', () => {
  it('derives inventory, classifies an IT from JUnit identity, parses Kover and keeps missing evidence explicit', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-collector-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-beta-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-alpha-service/build/test-results/test/TEST-com.openbank.alpha.PaymentApiIT.xml',
      '<testsuite name="com.openbank.alpha.PaymentApiIT" tests="2" failures="0" errors="0" skipped="1" time="0.2"><testcase classname="com.openbank.alpha.integration.PaymentApiIT"/></testsuite>')
    write(repo, 'openbank-alpha-service/build/reports/kover/report.xml',
      '<report><counter type="BRANCH" missed="4" covered="6"/><counter type="LINE" missed="20" covered="80"/></report>')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services:\n  - openbank-alpha-service\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', `version: 1
journeys:
  - id: edge
    title: Edge
    status: active
    severity: page
    schedule: "*/5 * * * *"
    falsification: break it
  - id: mobile
    title: Mobile
    status: planned
    severity: page
    money_moving: true
    target_schedule: "0 * * * *"
    capability: proves the mobile critical path
    falsification: break the app route
    blocked_by: needs canary devices
`)
    write(repo, 'openbank-alpha-service/src/test/k6/alpha-smoke.js', 'export const options = { thresholds: { checks: ["rate>0.99"] } }')
    write(repo, 'openbank-admin-ui/perf-artifacts/openbank-alpha-service-summary.json', JSON.stringify({ metrics: {
      http_req_duration: { 'p(95)': 321.4, thresholds: { 'p(95)<500': false } },
      http_req_failed: { value: 0.0125, thresholds: { 'rate<0.02': false } },
      checks: { value: 0.9875, thresholds: { 'rate>0.98': false } },
    } }))
    write(repo, 'perf/k6/breached.js', 'export const options = { thresholds: { checks: ["rate==1.0"] } }')
    write(repo, 'openbank-admin-ui/perf-artifacts/breached-summary.json', JSON.stringify({ metrics: {
      checks: { value: 0.9, thresholds: { 'rate==1.0': true } },
    } }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    expect(report.components.map(item => item.component)).toEqual(['openbank-alpha-service', 'openbank-beta-service'])
    expect(report.components[0].moneyPath).toBe(true)
    expect(report.components[0].evidence[0]).toMatchObject({ kind: 'integration', state: 'passed' })
    expect(report.components[0].evidence[0].counts).toMatchObject({ discovered: 2, executed: 1, skipped: 1 })
    expect(report.components[0].coverage.lines.percentage).toBe(80)
    expect(report.components[1].evidence).toEqual([])
    expect(report.totals.missingEvidence).toBe(1)
    expect(report.syntheticJourneys[0]).toMatchObject({ id: 'edge', state: 'unknown', schedule: '*/5 * * * *' })
    expect(report.syntheticJourneys[1]).toMatchObject({ id: 'mobile', state: 'blocked', schedule: '0 * * * *', blocker: 'needs canary devices' })
    expect(report.performance.find(item => item.id === 'openbank-alpha-service-alpha-smoke')).toMatchObject({ state: 'passed', metrics: {
      p95Ms: 321.4, errorRatePercent: 1.25, checkPassRatePercent: 98.75,
    } })
    expect(report.performance.find(item => item.id === 'breached')).toMatchObject({ state: 'failed', detail: '1 threshold result(s), 1 breached' })
    expect(report.clientExperiences).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'admin-ui', rum: expect.objectContaining({ policy: 'rejected' }) }),
      expect.objectContaining({ id: 'openbank-app', evidence: [], blocker: expect.stringMatching(/artifact/i) }),
    ]))
  })

  it('keeps Vitest and multi-suite Playwright fallback evidence when no CI envelope was retained', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-xml-fallback-'))
    dirs.push(repo)
    write(repo, 'openbank-admin-ui/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-admin-ui/build/test-results/test/vitest.xml', '<testsuites><testsuite name="unit" tests="2" failures="0" errors="0" skipped="0" time="1"><testcase classname="guard" name="allows"/><testcase classname="guard" name="denies"/></testsuite></testsuites>')
    write(repo, 'openbank-admin-ui/build/test-results/e2e/playwright.xml', '<testsuites><testsuite name="first" tests="1" failures="0" errors="0" skipped="0" time="2"><testcase classname="first.spec.ts" name="loads"/></testsuite><testsuite name="second" tests="1" failures="1" errors="0" skipped="0" time="3"><testcase classname="second.spec.ts" name="fails"><failure/></testcase></testsuite></testsuites>')
    write(repo, 'openbank-admin-ui/build/test-results/test/not-junit.xml', '<report><counter/></report>')
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    const web = report.clientExperiences.find(item => item.id === 'admin-ui')!
    expect(web.evidence).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'unit', state: 'passed', counts: expect.objectContaining({ discovered: 2, passed: 2 }) }),
      expect.objectContaining({ kind: 'e2e', state: 'failed', counts: expect.objectContaining({ discovered: 2, failed: 1, passed: 1 }) }),
    ]))
  })

  it('prefers the versioned run envelope and preserves provenance plus Testcontainers runtime proof', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-envelope-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-alpha-service/build/test-intelligence/run.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: '42', attempt: 2, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://example.test/run/42', observedAt: '2026-08-22T10:00:00Z' },
      component: 'openbank-alpha-service',
      suites: [{ kind: 'integration', state: 'passed', discovered: 3, executed: 3, passed: 3, failed: 0, skipped: 0, errors: 0, durationMs: 900 }],
      testCases: [{ fingerprint: '0123456789abcdef01234567', kind: 'integration', classname: 'com.openbank.PaymentApiIT', name: 'books payment', state: 'passed', durationMs: 400 }],
      coverage: { lines: { covered: 8, missed: 2, percentage: 80 }, branches: { covered: 3, missed: 1, percentage: 75 } },
      testInfrastructure: { declared: ['postgres'], observed: [
        { resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'started', observedAt: '2026-08-22T09:59:00Z' },
        { resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'stopped', observedAt: '2026-08-22T10:00:00Z' },
      ] },
    }))
    write(repo, 'openbank-admin-ui/test-run-history/previous.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: '41', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://example.test/run/41', observedAt: '2026-08-22T09:00:00Z' },
      component: 'openbank-alpha-service', suites: [], coverage: null,
      testCases: [{ fingerprint: '0123456789abcdef01234567', kind: 'integration', classname: 'com.openbank.PaymentApiIT', name: 'books payment', state: 'failed', durationMs: 500 }],
      testInfrastructure: { declared: [], observed: [] },
    }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    expect(report.components[0].evidence[0]).toMatchObject({ kind: 'integration', run: { id: '42', attempt: 2, commit: 'abcdef012345' } })
    expect(report.components[0].testInfrastructure.observed).toHaveLength(2)
    expect(report.components[0].coverage.branches.percentage).toBe(75)
    expect(report.testCases[0]).toMatchObject({
      fingerprint: '0123456789abcdef01234567', state: 'flaky', observations: 2,
      failureRate: 50, wastedDurationMs: 500, sameCommitTransitions: 1, owner: 'unowned',
    })
  })

  it('projects immutable mobile CI evidence while keeping RUM runtime state independent', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-client-'))
    dirs.push(repo)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, '.app-src/shared/src/androidMain/kotlin/tech/openbank/app/telemetry/RumMonitor.android.kt', 'actual object RumMonitor')
    write(repo, '.app-src/shared/src/iosMain/kotlin/tech/openbank/app/telemetry/RumMonitor.ios.kt', 'actual object RumMonitor')
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-unit.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: '9', attempt: 1, commit: 'abc', branch: 'main', workflow: 'app build', url: 'https://example.test/9', observedAt: '2026-08-23T10:00:00Z' },
      suites: [{ kind: 'unit', state: 'passed', durationMs: 100, counts: { discovered: 2, executed: 2, passed: 2, failed: 0, skipped: 0, errors: 0 } }],
    }))
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-older.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: '8', attempt: 1, commit: 'older', branch: 'main', workflow: 'app build', url: 'https://example.test/8', observedAt: '2026-08-22T10:00:00Z' },
      suites: [{ kind: 'unit', state: 'failed', durationMs: 100, counts: { discovered: 2, executed: 2, passed: 1, failed: 1, skipped: 0, errors: 0 } }],
    }))
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-e2e.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: '10', attempt: 1, commit: 'abc', branch: 'main', workflow: 'app build', url: 'https://example.test/10', observedAt: '2026-08-23T11:00:00Z' },
      suites: [{ kind: 'e2e', state: 'passed', durationMs: 200, counts: { discovered: 2, executed: 2, passed: 2, failed: 0, skipped: 0, errors: 0 } }],
    }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    const app = report.clientExperiences.find(item => item.id === 'openbank-app')!
    expect(app.evidence.find(item => item.kind === 'unit')).toMatchObject({
      kind: 'unit', state: 'passed', run: { id: '9' },
    })
    expect(app.evidence.some(item =>
      item.kind === 'e2e' && item.state === 'passed' && item.run?.id === '10',
    )).toBe(true)
    expect(app.rum).toMatchObject({ policy: 'consent-gated', state: 'unknown' })
    expect(report.runHistory.filter(item => item.component === 'openbank-app').map(item => item.run.id)).toEqual(['10', '9', '8'])
  })
})
