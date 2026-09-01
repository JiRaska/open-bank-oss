// SPDX-License-Identifier: Apache-2.0
import { execFile, execFileSync } from 'child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs'
import { createServer } from 'http'
import { tmpdir } from 'os'
import path from 'path'
import { promisify } from 'util'
import { afterEach, describe, expect, it } from 'vitest'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

const SCRIPT = path.resolve(__dirname, '../../scripts/collect-test-intelligence.mjs')
const QUALITY_SCRIPT = path.resolve(__dirname, '../../scripts/collect-quality-report.mjs')
const execFileAsync = promisify(execFile)
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
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services:\n  - openbank-alpha-service\n  - openbank-beta-service\n')
    write(repo, 'openbank-admin-ui/quality-report.json', JSON.stringify({ contracts: [{
      consumer: 'openbank-alpha-service', provider: 'openbank-provider-not-inventory', pactFile: 'alpha-provider.json',
      status: 'pending', verifiedAt: null, interactions: [{ description: 'create', status: 'pending' }],
    }] }))
    write(repo, 'openbank-libs/governance/journeys.yaml', `version: 1
journeys:
  - id: edge
    title: Edge
    status: active
    severity: page
    capability: proves the public edge
    covers: [openbank-alpha-service]
    schedule: "*/5 * * * *"
    runtime_note: requires a dedicated synthetic identity
    falsification: break it
  - id: mobile
    title: Mobile
    status: planned
    severity: page
    money_moving: true
    covers: [openbank-beta-service]
    target_schedule: "0 * * * *"
    capability: proves the mobile critical path
    falsification: break the app route
    blocked_by: needs canary devices
  - id: admin-ui-sso-boundary
    title: Admin UI SSO boundary
    status: active
    severity: ticket
    money_moving: false
    workflow: .github/workflows/admin-ui-browser-synthetic.yml
    workflow_name: Admin UI browser synthetic
    schedule: "13 */2 * * *"
    capability: proves the public SSO hand-off
    falsification: remove the SSO boundary
money_path_accountability:
  default_blocker: needs synthetic parties
  services:
    - service: openbank-beta-service
`)
    write(repo, 'openbank-alpha-service/src/test/k6/alpha-smoke.js', 'export const options = { thresholds: { checks: ["rate>0.99"] } }')
    write(repo, 'openbank-admin-ui/perf-artifacts/openbank-alpha-service-summary.json', JSON.stringify({ metrics: {
      http_req_duration: { 'p(95)': 321.4, thresholds: { 'p(95)<500': false } },
      http_req_failed: { value: 0.0125, thresholds: { 'rate<0.02': false } },
      checks: { value: 0.9875, thresholds: { 'rate>0.98': false } },
      http_reqs: { count: 80 },
    } }))
    write(repo, 'openbank-admin-ui/perf-artifacts/openbank-alpha-service-run.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: 'perf-42', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Performance gate', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/perf-42', observedAt: '2026-08-25T06:00:00Z' },
      component: 'openbank-alpha-service', suites: [], coverage: null,
      testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'performance', state: 'passed', source: 'summary.json', detail: '3 threshold result(s), 0 breached' }],
    }))
    write(repo, 'perf/k6/breached.js', 'export const options = { thresholds: { checks: ["rate==1.0"] } }')
    write(repo, 'openbank-admin-ui/perf-artifacts/breached-summary.json', JSON.stringify({ metrics: {
      checks: { value: 0.9, thresholds: { 'rate==1.0': true } },
    } }))
    write(repo, 'perf/k6/money-path-smoke.js', 'export const options = { thresholds: { checks: ["rate>0.99"] } }')
    write(repo, 'perf/scenarios.yaml', `version: 1
scenarios:
  - id: money-path-smoke
    definition: perf/k6/money-path-smoke.js
    execution_mode: planned-read-only-sandbox
    safety_boundary: read-only target only
    target_schedule: "0 5 * * *"
    blocker: no safe target or runner configured
`)
    write(repo, 'openbank-admin-ui/perf-artifacts/money-path-smoke-summary.json.run.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: 'baseline-7', attempt: 1, commit: 'fedcba987654', branch: 'main', workflow: 'Perf baseline', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/baseline-7', observedAt: '2026-08-25T07:00:00Z' },
      component: 'openbank-money-path', suites: [], coverage: null,
      testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'performance', state: 'not-run', source: 'perf-summary.json', detail: 'No safe money-path target is configured for this GitHub-hosted runner.' }],
    }))
    write(repo, 'openbank-admin-ui/test-run-history/synthetic.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: 'synthetic-9', attempt: 1, commit: '123456789abc', branch: 'main', workflow: 'Synthetic journeys', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/synthetic-9', observedAt: '2026-08-25T08:00:00Z' },
      component: 'openbank-platform', suites: [], coverage: null, testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [
        { kind: 'synthetic', state: 'passed', source: 'journey:edge', detail: '2 threshold result(s), 0 breached' },
      ],
    }))
    write(repo, 'openbank-admin-ui/test-run-history/browser-synthetic.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: 'browser-10', attempt: 1, commit: '123456789abc', branch: 'main', workflow: 'Admin UI browser synthetic', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/browser-10', observedAt: '2026-08-25T08:10:00Z' },
      component: 'openbank-admin-ui', suites: [], coverage: null, testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'synthetic', state: 'passed', source: 'journey:admin-ui-sso-boundary', detail: '1/1 browser E2E checks executed' }],
    }))
    write(repo, 'openbank-admin-ui/test-intelligence-history/previous-snapshot.json', JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-24T06:00:00Z', totals: { components: 2 },
      performance: [{ id: 'openbank-alpha-service-alpha-smoke', state: 'passed', observedAt: '2026-08-24T05:59:00Z', metrics: {
        p95Ms: 280, errorRatePercent: 0, checkPassRatePercent: 100, requests: 75,
      }, run: { id: 'perf-previous', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Performance gate', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/perf-previous' } }],
    }))
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
    expect(report.totals.unknownEvidence).toBe(1)
    expect(report.totals.unresolvedEvidence).toBe(1)
    expect(report.contracts).toEqual([expect.objectContaining({
      pactFile: 'alpha-provider.json', state: 'unknown',
      verificationDetail: expect.stringMatching(/not a passing result/i),
    })])
    expect(report.syntheticJourneys[0]).toMatchObject({ id: 'edge', state: 'unknown', schedule: '*/5 * * * *', runtimeNote: 'requires a dedicated synthetic identity', ci: {
      state: 'passed', detail: '2 threshold result(s), 0 breached', run: { id: 'synthetic-9', workflow: 'Synthetic journeys' },
    } })
    expect(report.syntheticJourneys[1]).toMatchObject({ id: 'mobile', state: 'blocked', schedule: '0 * * * *', blocker: 'needs canary devices' })
    expect(report.syntheticJourneys.find(item => item.id === 'admin-ui-sso-boundary')).toMatchObject({
      status: 'active', executor: 'github-actions', ci: { state: 'passed', detail: '1/1 browser E2E checks executed' },
    })
    expect(report.journeyCoverage).toMatchObject({ moneyPathTotal: 2, activelyCovered: 1, explicitlyUnwatched: 1 })
    expect(report.journeyCoverage?.services).toEqual([
      expect.objectContaining({ component: 'openbank-alpha-service', state: 'covered', journeys: ['edge'] }),
      expect.objectContaining({ component: 'openbank-beta-service', state: 'unwatched', reason: 'needs synthetic parties' }),
    ])
    expect(report.performance.find(item => item.id === 'openbank-alpha-service-alpha-smoke')).toMatchObject({ state: 'passed', metrics: {
      p95Ms: 321.4, errorRatePercent: 1.25, checkPassRatePercent: 98.75, requests: 80,
    }, run: { id: 'perf-42', workflow: 'Performance gate' } })
    expect(report.performanceHistory.filter(item => item.id === 'openbank-alpha-service-alpha-smoke')).toEqual(expect.arrayContaining([
      expect.objectContaining({ collectedAt: '2026-08-24T06:00:00Z', metrics: expect.objectContaining({ p95Ms: 280 }), run: { id: 'perf-previous', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Performance gate', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/perf-previous' } }),
      expect.objectContaining({ metrics: expect.objectContaining({ p95Ms: 321.4 }), run: expect.objectContaining({ id: 'perf-42' }) }),
    ]))
    expect(report.performance.find(item => item.id === 'breached')).toMatchObject({ state: 'failed', detail: '1 threshold result(s), 1 breached' })
    expect(report.performance.find(item => item.id === 'money-path-smoke')).toMatchObject({
      state: 'not-run', detail: 'No safe money-path target is configured for this GitHub-hosted runner.', run: { id: 'baseline-7', workflow: 'Perf baseline' },
      plan: { executionMode: 'planned-read-only-sandbox', safetyBoundary: 'read-only target only', targetSchedule: '0 5 * * *', blocker: 'no safe target or runner configured' },
    })
    expect(report.clientExperiences).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'admin-ui', rum: expect.objectContaining({ policy: 'authenticated', state: 'unknown' }) }),
      expect.objectContaining({ id: 'openbank-app', evidence: [], blocker: expect.stringMatching(/artifact/i) }),
    ]))
  })

  it('derives required mutation controls from the full commented Pitest matrix', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-required-mutation-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-beta-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-libs/governance/test-intelligence-capabilities.yaml', 'version: 1\ncapabilities:\n  - id: probes\n    title: Independent probes\n    state: external-blocked\n    blocker: no external fleet\n    evidence: issue-1\n')
    write(repo, '.github/workflows/pitest.yml', 'jobs:\n  pitest:\n    strategy:\n      matrix:\n        service:\n          - openbank-alpha-service\n          # comments must not truncate the denominator\n          - openbank-beta-service\n    steps:\n      - run: true\n')
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    expect(report.requiredControls?.filter(control => control.kind === 'mutation')).toEqual([
      expect.objectContaining({ id: 'openbank-alpha-service:mutation', state: 'not-run' }),
      expect.objectContaining({ id: 'openbank-beta-service:mutation', state: 'not-run' }),
    ])
    expect(report.totals).toMatchObject({ requiredControls: 4, requiredControlGaps: 4 })
    expect(report.platformCapabilities).toContainEqual(expect.objectContaining({ id: 'probes', state: 'external-blocked' }))
  })

  it('does not project a malformed capability register as a partial platform matrix', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-capability-register-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-libs/governance/test-intelligence-capabilities.yaml', `version: 1
capabilities:
  - id: probes
    title: Independent probes
    state: implemented
    state: external-blocked
    evidence: issue-1
`)
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport

    expect(report.platformCapabilities).toEqual([])
    expect(report.warnings).toEqual(expect.arrayContaining([
      expect.stringMatching(/capability register unavailable:.*unique/i),
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

  it('recognizes a named JVM E2E suite under the ordinary Gradle test task in fallback evidence', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-jvm-e2e-fallback-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-alpha-service/build/test-results/test/TEST-com.openbank.alpha.PaymentJourneyE2E.xml',
      '<testsuite name="com.openbank.alpha.PaymentJourneyE2E" tests="2" failures="0" errors="0" skipped="0" time="1"><testcase classname="com.openbank.alpha.e2e.PaymentJourneyE2E" name="books payment"/><testcase classname="com.openbank.alpha.e2e.PaymentJourneyE2E" name="reads booking"/></testsuite>')
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    expect(report.components[0].evidence).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'e2e', state: 'passed', source: 'JUnit:test', counts: expect.objectContaining({ discovered: 2, executed: 2, passed: 2 }) }),
    ]))
    expect(report.components[0].evidence).not.toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'unit', source: 'JUnit:test' }),
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
      run: { id: '42', attempt: 2, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/42', observedAt: '2026-08-22T10:00:00Z' },
      component: 'openbank-alpha-service',
      suites: [
        { kind: 'integration', state: 'passed', discovered: 3, executed: 3, passed: 3, failed: 0, skipped: 0, errors: 0, durationMs: 900 },
        { kind: 'e2e', state: 'failed', discovered: 2, executed: 2, passed: 1, failed: 1, skipped: 0, errors: 0, durationMs: 1200 },
      ],
      specializedEvidence: [{ kind: 'trace', state: 'passed', source: 'trace-contract:payment-booking', detail: '1 executed marker(s); JUnit suite passed' }],
      testCases: [{ fingerprint: '0123456789abcdef01234567', kind: 'integration', classname: 'com.openbank.PaymentApiIT', name: 'books payment', state: 'passed', durationMs: 400, testDefinitionPath: 'src/test/kotlin/com/openbank/PaymentApiIT.kt' }],
      coverage: { lines: { covered: 8, missed: 2, percentage: 80 }, branches: { covered: 3, missed: 1, percentage: 75 } },
      testInfrastructure: { declared: ['postgres'], observed: [
        { resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'started', observedAt: '2026-08-22T09:59:00Z' },
        { resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'stopped', observedAt: '2026-08-22T10:00:00Z' },
      ] },
      diagnostics: [
        { kind: 'playwright-report', suiteKind: 'e2e', name: 'playwright-report-42-a2', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/42#artifacts', retentionDays: 7, access: 'github-run-authenticated', mayContainSensitiveData: true },
        { kind: 'playwright-report', suiteKind: 'e2e', name: 'playwright-report-phishing', url: 'https://attacker.example/report', retentionDays: 7, access: 'github-run-authenticated', mayContainSensitiveData: true },
      ],
    }))
    write(repo, 'openbank-admin-ui/test-run-history/previous.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: '41', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/41', observedAt: '2026-08-22T09:00:00Z' },
      component: 'openbank-alpha-service', suites: [], coverage: null,
      testCases: [{ fingerprint: '0123456789abcdef01234567', kind: 'integration', classname: 'com.openbank.PaymentApiIT', name: 'books payment', state: 'failed', durationMs: 500 }],
      testInfrastructure: { declared: [], observed: [] },
    }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    expect(report.components[0].evidence[0]).toMatchObject({ kind: 'integration', run: { id: '42', attempt: 2, commit: 'abcdef012345' } })
    expect(report.components[0].evidence.find(item => item.kind === 'trace')).toMatchObject({
      kind: 'trace', state: 'passed', source: 'trace-contract:payment-booking',
      detail: '1 executed marker(s); JUnit suite passed', run: { id: '42', attempt: 2 },
    })
    expect(report.components[0].evidence.find(item => item.kind === 'e2e')).toMatchObject({
      state: 'failed', diagnostics: [{ kind: 'playwright-report', name: 'playwright-report-42-a2', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/42#artifacts', retentionDays: 7, access: 'github-run-authenticated', mayContainSensitiveData: true }],
    })
    expect(report.components[0].evidence.find(item => item.kind === 'e2e')?.diagnostics).toHaveLength(1)
    expect(report.components[0].testInfrastructure.observed).toHaveLength(2)
    expect(report.runHistory[0].states).toMatchObject({ integration: 'passed', trace: 'passed' })
    expect(report.components[0].coverage.branches.percentage).toBe(75)
    expect(report.testCases[0]).toMatchObject({
      fingerprint: '0123456789abcdef01234567', state: 'flaky', observations: 2,
      failureRate: 50, wastedDurationMs: 500, sameCommitTransitions: 1, owner: 'unowned',
      testDefinitionPath: 'src/test/kotlin/com/openbank/PaymentApiIT.kt',
    })
    expect(report.testImpact).toEqual({
      schemaVersion: 1, mode: 'shadow', mappingState: 'unknown', selectionState: 'unavailable',
      declaredByAllRetainedRuns: false,
      detail: expect.stringContaining('No test-to-production mapping is assumed'),
    })
  })

  it('projects the unreleased simulation tooling envelope instead of reporting missing evidence', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-simulation-'))
    dirs.push(repo)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-simulation/build/test-intelligence/run.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: 'simulation-42', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/simulation-42', observedAt: '2026-08-22T10:00:00Z' },
      component: 'openbank-simulation',
      suites: [{ kind: 'simulation', state: 'passed', discovered: 51, executed: 51, passed: 51, failed: 0, skipped: 0, errors: 0, durationMs: 48_278 }],
      testCases: [{ fingerprint: '0123456789abcdef01234567', kind: 'simulation', classname: 'com.openbank.simulation.DstSimulationTest', name: 'holds every invariant', state: 'passed', durationMs: 100 }],
      coverage: { lines: { covered: 857, missed: 16, percentage: 98.17 }, branches: { covered: 106, missed: 32, percentage: 76.81 } },
      testInfrastructure: { declared: [], observed: [] },
    }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    expect(report.components).toHaveLength(1)
    expect(report.components[0]).toMatchObject({
      component: 'openbank-simulation', released: false,
      evidence: [expect.objectContaining({
        kind: 'simulation', state: 'passed', run: expect.objectContaining({ id: 'simulation-42' }),
      })],
      coverage: expect.objectContaining({
        source: 'test-intelligence-run:v1', lines: expect.objectContaining({ percentage: 98.17 }),
      }),
    })
    expect(report.testCases).toEqual([expect.objectContaining({
      component: 'openbank-simulation', state: 'stable', observations: 1,
    })])
    expect(report.totals).toMatchObject({ componentsWithExecutionEvidence: 1, missingEvidence: 0 })
  })

  it('keeps verdicts but omits outbound provenance from an untrusted run host', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-untrusted-run-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-alpha-service/build/test-intelligence/run.json', JSON.stringify({
      schemaVersion: 1,
      run: { id: '42', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://attacker.example/actions/runs/42', observedAt: '2026-08-22T10:00:00Z' },
      component: 'openbank-alpha-service',
      suites: [{ kind: 'unit', state: 'passed', discovered: 1, executed: 1, passed: 1, failed: 0, skipped: 0, errors: 0, durationMs: 10 }],
      coverage: null,
      testInfrastructure: { declared: [], observed: [] },
    }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport

    expect(report.components[0].evidence[0]).toMatchObject({ kind: 'unit', state: 'passed' })
    expect(report.components[0].evidence[0].run).toBeUndefined()
    expect(report.runHistory).toEqual([])
    expect(report.warnings).toContain('untrusted run URL omitted: openbank-alpha-service')
    expect(report.warnings).toContain('untrusted run URL omitted: history:openbank-alpha-service')
  })

  it('projects immutable mobile CI evidence while keeping RUM runtime state independent', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-client-'))
    dirs.push(repo)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, '.app-src/shared/src/androidMain/kotlin/tech/openbank/app/telemetry/RumMonitor.android.kt', 'actual object RumMonitor { val keys = "app.version os.type os.version device.model screen.name" }')
    write(repo, '.app-src/shared/src/iosMain/kotlin/tech/openbank/app/telemetry/RumMonitor.ios.kt', 'actual object RumMonitor { val keys = "app.version os.type os.version device.model screen.name" }')
    write(repo, '.app-src/shared/src/commonMain/kotlin/tech/openbank/app/telemetry/TraceparentPlugin.kt', 'traceparent x-correlation-id')
    write(repo, '.app-src/shared/src/commonTest/kotlin/tech/openbank/app/telemetry/TraceparentPluginTest.kt', 'traceparent x-correlation-id')
    write(repo, '.app-src/shared/src/iosTest/kotlin/tech/openbank/app/telemetry/RumMonitorIosTest.kt', 'app.version os.type os.version device.model screen.name')
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-unit.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: '9', attempt: 1, commit: 'abc', branch: 'main', workflow: 'app build', url: 'https://github.com/JiRaska/open-bank-app/actions/runs/9', observedAt: '2026-08-23T10:00:00Z' },
      suites: [{ kind: 'unit', state: 'passed', durationMs: 100, counts: { discovered: 2, executed: 2, passed: 2, failed: 0, skipped: 0, errors: 0 } }],
    }))
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-older.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: '8', attempt: 1, commit: 'older', branch: 'main', workflow: 'app build', url: 'https://github.com/JiRaska/open-bank-app/actions/runs/8', observedAt: '2026-08-22T10:00:00Z' },
      suites: [{ kind: 'unit', state: 'failed', durationMs: 100, counts: { discovered: 2, executed: 2, passed: 1, failed: 1, skipped: 0, errors: 0 } }],
    }))
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-e2e.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: '10', attempt: 1, commit: 'abc', branch: 'main', workflow: 'app build', url: 'https://github.com/JiRaska/open-bank-app/actions/runs/10', observedAt: '2026-08-23T11:00:00Z' },
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
    expect(app.rum.platforms).toEqual([
      expect.objectContaining({ platform: 'android', capability: 'passed', runtime: 'unknown' }),
      expect.objectContaining({ platform: 'ios', capability: 'passed', runtime: 'unknown' }),
    ])
    expect(report.runHistory.filter(item => item.component === 'openbank-app').map(item => item.run.id)).toEqual(['10', '9', '8'])
  })

  it('does not promote a mobile RUM source file missing its closed attribute contract', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-rum-contract-'))
    dirs.push(repo)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, '.app-src/shared/src/androidMain/kotlin/tech/openbank/app/telemetry/RumMonitor.android.kt', 'app.version os.type screen.name')
    write(repo, '.app-src/shared/src/iosMain/kotlin/tech/openbank/app/telemetry/RumMonitor.ios.kt', 'app.version os.type os.version device.model screen.name')
    write(repo, '.app-src/shared/src/commonMain/kotlin/tech/openbank/app/telemetry/TraceparentPlugin.kt', 'traceparent x-correlation-id')
    write(repo, '.app-src/shared/src/commonTest/kotlin/tech/openbank/app/telemetry/TraceparentPluginTest.kt', 'traceparent x-correlation-id')
    write(repo, '.app-src/shared/src/iosTest/kotlin/tech/openbank/app/telemetry/RumMonitorIosTest.kt', 'app.version os.type os.version device.model screen.name')
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    const platforms = report.clientExperiences.find(item => item.id === 'openbank-app')!.rum.platforms!
    expect(platforms).toEqual(expect.arrayContaining([
      expect.objectContaining({ platform: 'android', capability: 'not-run', runtime: 'unknown' }),
      expect.objectContaining({ platform: 'ios', capability: 'passed', runtime: 'unknown' }),
    ]))
  })

  it('marks old mobile CI evidence stale without hiding a recorded failure', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-client-freshness-'))
    dirs.push(repo)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-unit.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: 'old-pass', attempt: 1, commit: 'abc', branch: 'main', workflow: 'app build', url: 'https://github.com/JiRaska/open-bank-app/actions/runs/old-pass', observedAt: '2020-01-01T00:00:00Z' },
      suites: [{ kind: 'unit', state: 'passed', durationMs: 100, counts: { discovered: 1, executed: 1, passed: 1, failed: 0, skipped: 0, errors: 0 } }],
    }))
    write(repo, 'openbank-admin-ui/client-test-evidence/openbank-app-e2e.json', JSON.stringify({
      schemaVersion: 1, component: 'openbank-app',
      run: { id: 'old-fail', attempt: 1, commit: 'abc', branch: 'main', workflow: 'app build', url: 'https://github.com/JiRaska/open-bank-app/actions/runs/old-fail', observedAt: '2020-01-01T00:00:00Z' },
      suites: [{ kind: 'e2e', state: 'failed', durationMs: 100, counts: { discovered: 1, executed: 1, passed: 0, failed: 1, skipped: 0, errors: 0 } }],
    }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '1'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    const app = report.clientExperiences.find(item => item.id === 'openbank-app')!
    expect(app.evidence.find(item => item.kind === 'unit')).toMatchObject({ state: 'stale', run: { id: 'old-pass' } })
    expect(app.evidence.find(item => item.kind === 'e2e')).toMatchObject({ state: 'failed', run: { id: 'old-fail' } })
  })

  it('expires retained backend, mutation, performance and synthetic successes without hiding failures', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-fleet-freshness-'))
    dirs.push(repo)
    write(repo, 'openbank-alpha-service/version.txt', '1.0.0\n')
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', `version: 1
journeys:
  - id: old-journey
    title: Old journey
    status: active
    severity: ticket
    covers: [openbank-alpha-service]
    schedule: "0 * * * *"
    falsification: old success must expire
`)
    const oldRun = { id: 'old-42', attempt: 1, commit: 'abcdef012345', branch: 'main', workflow: 'Services CI', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/old-42', observedAt: '2020-01-01T00:00:00Z' }
    write(repo, 'openbank-alpha-service/build/test-intelligence/run.json', JSON.stringify({
      schemaVersion: 1, run: oldRun, component: 'openbank-alpha-service',
      suites: [
        { kind: 'unit', state: 'passed', discovered: 1, executed: 1, passed: 1, failed: 0, skipped: 0, errors: 0, durationMs: 10 },
        { kind: 'integration', state: 'failed', discovered: 1, executed: 1, passed: 0, failed: 1, skipped: 0, errors: 0, durationMs: 10 },
      ],
      specializedEvidence: [{ kind: 'trace', state: 'passed', source: 'trace-contract:old' }],
      coverage: null, testInfrastructure: { declared: [], observed: [] },
    }))
    write(repo, 'openbank-alpha-service/src/test/k6/alpha-smoke.js', 'export const options = { thresholds: { checks: ["rate>0.99"] } }')
    write(repo, 'openbank-admin-ui/perf-artifacts/openbank-alpha-service-summary.json', JSON.stringify({ metrics: { checks: { value: 1, thresholds: { 'rate>0.99': false } } } }))
    write(repo, 'openbank-admin-ui/perf-artifacts/openbank-alpha-service-run.json', JSON.stringify({
      schemaVersion: 1, run: oldRun, component: 'openbank-alpha-service', suites: [], coverage: null,
      testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'performance', state: 'passed', source: 'summary.json' }],
    }))
    write(repo, 'perf/k6/future.js', 'export const options = { thresholds: { checks: ["rate>0.99"] } }')
    write(repo, 'openbank-admin-ui/perf-artifacts/future-summary.json', JSON.stringify({ metrics: { checks: { value: 1, thresholds: { 'rate>0.99': false } } } }))
    write(repo, 'openbank-admin-ui/perf-artifacts/future-run.json', JSON.stringify({
      schemaVersion: 1,
      run: { ...oldRun, id: 'future-42', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/future-42', observedAt: '2999-01-01T00:00:00Z' },
      component: 'openbank-platform', suites: [], coverage: null,
      testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'performance', state: 'passed', source: 'summary.json' }],
    }))
    write(repo, 'openbank-alpha-service/build/reports/pitest/mutations.xml', '<mutations><mutation status="KILLED"/></mutations>')
    write(repo, 'openbank-alpha-service/build/reports/pitest/test-intelligence-run.json', JSON.stringify({
      schemaVersion: 1, run: oldRun, component: 'openbank-alpha-service', suites: [], coverage: null,
      testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'mutation', state: 'passed', source: 'mutations.xml' }],
    }))
    write(repo, 'openbank-admin-ui/test-run-history/synthetic-old.json', JSON.stringify({
      schemaVersion: 1, run: oldRun, component: 'openbank-platform', suites: [], coverage: null,
      testInfrastructure: { declared: [], observed: [] },
      specializedEvidence: [{ kind: 'synthetic', state: 'passed', source: 'journey:old-journey' }],
    }))
    write(repo, 'openbank-admin-ui/quality-report.json', JSON.stringify({ contracts: [{
      consumer: 'openbank-alpha-service', provider: 'openbank-provider', pactFile: 'alpha-provider.json',
      status: 'passed', verifiedAt: '2020-01-01T00:00:00Z', interactions: [{ description: 'old contract', status: 'passed' }],
    }] }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '1'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport

    expect(report.components[0].evidence).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'unit', state: 'stale' }),
      expect.objectContaining({ kind: 'integration', state: 'failed' }),
      expect.objectContaining({ kind: 'trace', state: 'stale' }),
      expect.objectContaining({ kind: 'mutation', state: 'stale' }),
      expect.objectContaining({ kind: 'performance', state: 'stale' }),
    ]))
    expect(report.mutations[0]).toMatchObject({ state: 'stale' })
    expect(report.performance.find(item => item.id === 'openbank-alpha-service-alpha-smoke')).toMatchObject({ state: 'stale' })
    expect(report.performance.find(item => item.id === 'future')).toMatchObject({ state: 'unknown' })
    expect(report.syntheticJourneys[0].ci).toMatchObject({ state: 'stale' })
    expect(report.contracts[0]).toMatchObject({ state: 'stale' })
    expect(report.totals).toMatchObject({ failingEvidence: 1, staleEvidence: 5 })
  })

  it('classifies why a Pact Broker verdict is unresolved and never turns an unresolved pact into a pass (#7544)', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'test-intelligence-contract-reason-'))
    dirs.push(repo)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
    write(repo, 'openbank-admin-ui/quality-report.json', JSON.stringify({ contracts: [
      {
        consumer: 'openbank-admin-ui', provider: 'openbank-case-coordinator-agent', pactFile: 'error.json',
        status: 'pending', verifiedAt: null, reasonCode: 'query-error',
        detail: 'Pact Broker matrix query returned HTTP 400 for openbank-admin-ui → openbank-case-coordinator-agent.',
        interactions: [{ description: 'a', status: 'pending' }],
      },
      {
        consumer: 'openbank-alpha-service', provider: 'openbank-document-service', pactFile: 'no-main.json',
        status: 'pending', verifiedAt: null, reasonCode: 'no-provider-main-version',
        detail: 'openbank-document-service has no published main-branch version in the Pact Broker, so provider verification cannot be dispatched yet.',
        interactions: [{ description: 'b', status: 'pending' }],
      },
      {
        consumer: 'openbank-alpha-service', provider: 'openbank-incentive-service', pactFile: 'pending.json',
        status: 'pending', verifiedAt: null, reasonCode: 'pending-verification',
        detail: 'openbank-incentive-service has a main-branch version, but no verification result for the latest openbank-alpha-service pact yet.',
        interactions: [{ description: 'c', status: 'pending' }],
      },
      // A legacy/unclassified snapshot (no reasonCode) must still read as unresolved, not passed.
      {
        consumer: 'openbank-alpha-service', provider: 'openbank-legacy-provider', pactFile: 'legacy.json',
        status: 'pending', verifiedAt: null, interactions: [{ description: 'd', status: 'pending' }],
      },
      {
        consumer: 'openbank-alpha-service', provider: 'openbank-real-provider', pactFile: 'passed.json',
        status: 'passed', verifiedAt: '2026-08-28T00:00:00Z', interactions: [{ description: 'e', status: 'passed' }],
      },
    ] }))
    const out = path.join(repo, 'report.json')
    execFileSync('node', [SCRIPT, '--repo', repo, '--out', out, '--stale-after-days', '99999'])
    const report = JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
    const byFile = (file: string) => report.contracts.find(c => c.pactFile === file)

    expect(byFile('error.json')).toMatchObject({
      state: 'unknown', unavailableReason: 'query-error', verificationDetail: expect.stringMatching(/HTTP 400/),
    })
    expect(byFile('no-main.json')).toMatchObject({
      state: 'unknown', unavailableReason: 'no-provider-main-version',
      verificationDetail: expect.stringMatching(/no published main-branch version/),
    })
    expect(byFile('pending.json')).toMatchObject({
      state: 'unknown', unavailableReason: 'pending-verification',
      verificationDetail: expect.stringMatching(/no verification result/),
    })
    // Every unresolved pact stays 'unknown' — having a pact file, interactions, and even a
    // consumer/provider pair is never itself sufficient for 'passed'.
    for (const file of ['error.json', 'no-main.json', 'pending.json', 'legacy.json']) {
      expect(byFile(file)?.state).not.toBe('passed')
      expect(byFile(file)?.state).toBe('unknown')
    }
    expect(byFile('legacy.json')).toMatchObject({ state: 'unknown', unavailableReason: null })
    // Only a real broker 'passed' verdict produces state 'passed', and it carries no reason.
    expect(byFile('passed.json')).toMatchObject({ state: 'passed', unavailableReason: null })
  })

  it('pins a Pact verdict to the committed consumer version instead of the broker latest pair', async () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'quality-contract-provenance-'))
    const output = mkdtempSync(path.join(tmpdir(), 'quality-contract-output-'))
    dirs.push(repo, output)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'pacts/openbank-consumer-openbank-provider.json', JSON.stringify({
      consumer: { name: 'openbank-consumer' }, provider: { name: 'openbank-provider' }, interactions: [],
    }))
    execFileSync('git', ['init'], { cwd: repo })
    execFileSync('git', ['config', 'user.email', 'test@example.test'], { cwd: repo })
    execFileSync('git', ['config', 'user.name', 'Test'], { cwd: repo })
    execFileSync('git', ['config', 'commit.gpgSign', 'false'], { cwd: repo })
    execFileSync('git', ['add', '.'], { cwd: repo })
    execFileSync('git', ['commit', '-m', 'test pact provenance'], { cwd: repo })
    const consumerVersion = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim()

    let requestUrl = ''
    const server = createServer((request, response) => {
      requestUrl = request.url ?? ''
      response.setHeader('content-type', 'application/json')
      response.end(JSON.stringify({ matrix: [{
        providerVersion: { number: 'provider-replay-sha' },
        verificationResult: { success: true, verifiedAt: '2026-08-26T12:00:00Z' },
      }] }))
    })
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    if (!address || typeof address === 'string') throw new Error('test broker did not bind a TCP port')
    try {
      await execFileAsync('node', [QUALITY_SCRIPT, '--repo-root', repo], {
        cwd: output,
        env: { ...process.env, PACT_BROKER_URL: `http://127.0.0.1:${address.port}` },
      })
    } finally {
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
    }
    const report = JSON.parse(readFileSync(path.join(output, 'quality-report.json'), 'utf8'))
    expect(report.contracts).toEqual([expect.objectContaining({
      consumerVersion, providerVersion: 'provider-replay-sha', status: 'passed',
    })])
    const query = new URL(`http://broker${requestUrl}`).searchParams
    expect(query.getAll('q[][pacticipant]')).toEqual(['openbank-consumer', 'openbank-provider'])
    expect(query.getAll('q[][version]')).toEqual([consumerVersion])
    expect(query.getAll('q[][latest]')).toEqual(['true'])
  })

  it('does not query a broker latest pair when the committed Pact has no Git provenance', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'quality-contract-no-provenance-'))
    const output = mkdtempSync(path.join(tmpdir(), 'quality-contract-no-provenance-output-'))
    dirs.push(repo, output)
    write(repo, 'openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
    write(repo, 'pacts/openbank-consumer-openbank-provider.json', JSON.stringify({
      consumer: { name: 'openbank-consumer' }, provider: { name: 'openbank-provider' }, interactions: [],
    }))
    execFileSync('node', [QUALITY_SCRIPT, '--repo-root', repo], {
      cwd: output,
      // A deliberately unavailable endpoint proves the collector returns before
      // network I/O when it cannot pin the Pact to a committed consumer version.
      env: { ...process.env, PACT_BROKER_URL: 'http://127.0.0.1:1' },
    })
    const report = JSON.parse(readFileSync(path.join(output, 'quality-report.json'), 'utf8'))
    expect(report.contracts).toEqual([expect.objectContaining({
      consumerVersion: null, providerVersion: null, status: 'pending', verifiedAt: null,
    })])
  })
})
