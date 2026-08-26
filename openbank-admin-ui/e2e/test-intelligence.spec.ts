// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/test-intelligence', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
    schemaVersion: 1, collectedAt: '2026-08-22T12:00:00.000Z',
    components: [{ component: 'openbank-ledger-service', released: true, moneyPath: true, evidence: [{ kind: 'integration', state: 'passed', observedAt: '2026-08-22T11:00:00.000Z', source: 'JUnit:test', environment: 'ci', counts: { discovered: 12, executed: 12, passed: 12, failed: 0, skipped: 0, errors: 0 } }, { kind: 'trace', state: 'passed', observedAt: '2026-08-22T11:00:00.000Z', source: 'trace-contract:ledger-posting', environment: 'ci', detail: '1 executed marker(s); JUnit suite passed', run: { id: '4242', attempt: 1, commit: '1234567890abcdef', branch: 'main', workflow: 'Services CI', url: 'https://example.test/run/4242', observedAt: '2026-08-22T11:00:00.000Z' } }, { kind: 'e2e', state: 'failed', observedAt: '2026-08-22T11:00:00.000Z', source: 'test-intelligence-run:v1', environment: 'ci', counts: { discovered: 2, executed: 2, passed: 1, failed: 1, skipped: 0, errors: 0 }, diagnostics: [{ kind: 'playwright-report', name: 'playwright-report-4242-a1', url: 'https://example.test/run/4242#artifacts', retentionDays: 7, access: 'github-run-authenticated', mayContainSensitiveData: true }] }], coverage: { state: 'passed', observedAt: '2026-08-22T11:00:00.000Z', source: 'kover.xml', lines: { covered: 80, missed: 20, percentage: 80 }, branches: { covered: 6, missed: 4, percentage: 60 } }, testInfrastructure: { declared: ['postgres'], observed: [{ resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'started', observedAt: '2026-08-22T10:59:00.000Z' }, { resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'stopped', observedAt: '2026-08-22T11:01:00.000Z' }] } }],
    contracts: [], mutations: [], performance: [{ id: 'money-path-smoke', component: null, state: 'passed', observedAt: '2026-08-22T10:00:00.000Z', source: 'perf/k6/money-path-smoke.js', thresholds: 1, metrics: { p95Ms: 220.4, errorRatePercent: 0.2, checkPassRatePercent: 99.8, requests: 150 } }],
    syntheticJourneys: [{ id: 'public-edge', title: 'Public edge reachability', status: 'active', state: 'passed', severity: 'page', schedule: '*/5 * * * *', environment: 'sandbox', covers: [], falsifies: 'Break the public edge.', blocker: null, live: { source: 'prometheus', observedAt: '2026-08-22T12:00:00.000Z', lastScheduledAt: '2026-08-22T11:55:00.000Z', lastSuccessfulAt: '2026-08-22T11:56:00.000Z', failuresWithinWindow: 0, failureWindowSeconds: 900, activeJobs: 0, freshnessSeconds: 240, recentRuns: [{ id: 'public-edge-1', state: 'passed', observedAt: '2026-08-22T11:56:00.000Z' }] } }, { id: 'mobile-critical-path', title: 'Mobile login, overview and payment UI', status: 'planned', state: 'blocked', severity: 'page', schedule: '0 * * * *', environment: null, covers: [], falsifies: 'Break one app route.', blocker: 'Needs managed Android/iOS canary devices.' }],
    journeyCoverage: { moneyPathTotal: 2, activelyCovered: 1, explicitlyUnwatched: 1, services: [{ component: 'openbank-ledger-service', state: 'covered', journeys: ['public-edge'], reason: null }, { component: 'openbank-payment-service', state: 'unwatched', journeys: [], reason: 'Needs a safe synthetic payment target.' }] },
    clientExperiences: [{ id: 'openbank-app', title: 'OpenBank customer app', surface: 'mobile', platforms: ['android', 'ios'], evidence: [{ kind: 'visual', state: 'passed', observedAt: '2026-08-22T11:00:00.000Z', source: 'openbank-app CI', environment: 'ci', counts: { discovered: 1, executed: 1, passed: 1, failed: 0, skipped: 0, errors: 0 }, detail: 'Roborazzi committed-golden verification' }], rum: { state: 'passed', policy: 'consent-gated', detail: 'Mobile RUM is opt-in and its runtime signal is independent of CI.', observedAt: '2026-08-22T12:00:00.000Z', source: 'tempo', sampledSpansLast7d: 12, errorSpansLast7d: 2 }, blocker: null }],
    history: [{ collectedAt: '2026-08-22T12:00:00.000Z', components: 1, componentsWithExecutionEvidence: 1, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }],
    runHistory: [{ component: 'openbank-ledger-service', run: { id: '4242', attempt: 1, commit: '1234567890abcdef', branch: 'main', workflow: 'Services CI', url: 'https://example.test/run/4242', observedAt: '2026-08-22T11:00:00.000Z' }, states: { integration: 'passed' }, infrastructureStarted: 1, infrastructureStopped: 1 }],
    testCases: [{ fingerprint: '0123456789abcdef01234567', component: 'openbank-ledger-service', kind: 'integration', classname: 'com.openbank.LedgerApiIT', name: 'posts balanced journal', owner: '@JiRaska', state: 'flaky', lastState: 'passed', observations: 3, failureRate: 33.33, averageDurationMs: 400, wastedDurationMs: 420, sameCommitTransitions: 1, lastObservedAt: '2026-08-22T11:00:00.000Z' }],
    testImpact: { schemaVersion: 1, mode: 'shadow', mappingState: 'unknown', selectionState: 'unavailable', declaredByAllRetainedRuns: true, detail: 'Every retained run explicitly reports that no verified test-to-production mapping was collected. Full suites remain authoritative.' },
    totals: { components: 1, componentsWithExecutionEvidence: 1, moneyPathComponents: 1, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }, warnings: [],
  }) }))
  await page.route('**/api/test-intelligence/agents', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ findings: [], available: true }) }))
})

test('renders the animated evidence system and consolidates test dimensions', async ({ page }) => {
  await page.goto('/dashboard')
  const navigation = page.getByRole('navigation')
  const testIntelligenceLink = navigation.getByRole('link', { name: /Test Intelligence/ }).first()
  await expect(testIntelligenceLink).toBeVisible()
  await testIntelligenceLink.click()
  await expect(page).toHaveURL(/\/system\/tests$/)
  await expect(page.getByRole('heading', { name: 'Test Intelligence', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: /From change to confidence/ })).toBeVisible()
  await expect(page.getByRole('group', { name: /Seven Test Intelligence layers/ })).toBeVisible()
  await expect(page.getByRole('region', { name: /Testing assurance map/ })).toBeVisible()
  // A planned, blocked customer journey is unresolved evidence. The prominent health signal
  // must remain attention-worthy even when the ordinary component evidence is otherwise green.
  await expect(page.getByText('NEEDS ATTENTION', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /Intent enters/ }).click()
  await expect(page.getByText('Path-scoped CI is not per-test impact analysis; a prediction must not select a required gate yet.')).toBeVisible()
  await page.getByRole('button', { name: /Real experience/ }).click()
  await expect(page.getByText('Runtime telemetry arrived; correlation to a backend journey requires concrete trace evidence.')).toBeVisible()
  await expect(page.getByText('Agents may explain and propose a next step. They do not raise a verdict, delete evidence, or approve a release.')).toBeVisible()
  await page.getByRole('button', { name: /AI finds relationships/ }).click()
  await expect(page.getByText('AI cannot rewrite evidence, skip a gate or approve its own remediation.')).toBeVisible()
  await expect(page.getByText('openbank-ledger-service')).toBeVisible()
  await page.getByRole('button', { name: /Běhy|Execution/ }).click()
  await expect(page.getByText('trace', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'trace-contract:ledger-posting' })).toHaveAttribute('href', 'https://example.test/run/4242')
  await expect(page.getByText('1 executed marker(s); JUnit suite passed')).toBeVisible()
  await expect(page.getByRole('link', { name: 'playwright-report' })).toHaveAttribute('href', 'https://example.test/run/4242#artifacts')
  await expect(page.getByText('GitHub-authenticated · 7d · may contain sensitive browser data')).toBeVisible()
  await page.getByRole('button', { name: /Client & RUM/ }).click()
  await expect(page.getByText('OpenBank customer app')).toBeVisible()
  await page.getByRole('button', { name: /Testy a flaky|Tests & flaky/ }).click()
  await expect(page.getByText('posts balanced journal')).toBeVisible()
  await expect(page.getByText('1 same-commit pass/fail transition(s)')).toBeVisible()
  await expect(page.getByLabel('Test impact mapping state')).toContainText('Test impact selection: shadow only')
  await expect(page.getByText('AI must not infer it or select a required gate.')).toBeVisible()
  await page.getByRole('button', { name: /Testovací runtime|Test runtime/ }).click()
  await expect(page.getByText('1 started · 1 stopped')).toBeVisible()
  await page.getByRole('button', { name: /Historie|History/ }).click()
  await expect(page.getByText('4242 / 1')).toBeVisible()
  await page.getByRole('button', { name: /^(Syntetika|Synthetics)$/ }).click()
  await expect(page.getByText('Public edge reachability')).toBeVisible()
  await expect(page.getByText('ledger · Covered')).toBeVisible()
  await expect(page.getByText('payment · Unwatched')).toBeVisible()
  await expect(page.getByLabel('openbank-ledger-service: Covered. public-edge')).toBeVisible()
  await expect(page.getByLabel('openbank-payment-service: Unwatched. Needs a safe synthetic payment target.')).toBeVisible()
  await expect(page.getByText('*/5 * * * *')).toBeVisible()
  await expect(page.getByText('Recent Kubernetes runs')).toBeVisible()
  await expect(page.getByLabel('Recent runs for Public edge reachability')).toContainText('passed')
  await expect(page.getByText('Mobile login, overview and payment UI', { exact: true })).toBeVisible()
  await expect(page.getByText('Target schedule: 0 * * * *')).toBeVisible()
  await page.getByRole('button', { name: /Výkon|Performance/ }).click()
  await expect(page.getByText('p95')).toBeVisible()
  await expect(page.getByText('220 ms')).toBeVisible()
  await expect(page.getByText('0.2%')).toBeVisible()
  await expect(page.getByText('99.8%')).toBeVisible()
  await expect(page.getByText('150')).toBeVisible()
  await page.getByRole('button', { name: /Client experience/ }).click()
  await expect(page.getByText('OpenBank customer app')).toBeVisible()
  await expect(page.getByText('Roborazzi committed-golden verification')).toBeVisible()
  await expect(page.getByText('Mobile RUM is opt-in and its runtime signal is independent of CI.')).toBeVisible()
  await expect(page.getByText('12 sampled traces · 2 error span-counter increments')).toBeVisible()
  await expect(page.getByText(/AI AGENT/)).toBeVisible()
})

test('keeps the evidence flow usable at the mobile breakpoint', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/system/tests')
  await expect(page.getByRole('group', { name: /Seven Test Intelligence layers/ })).toBeVisible()
  await expect(page.getByRole('region', { name: /Testing assurance map/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /CI breaks assumptions/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Výkon|Performance/ })).toBeVisible()
})

test('meets WCAG A and AA rules in the rendered Test Intelligence workspace', async ({ page }) => {
  await page.goto('/system/tests')
  await expect(page.getByRole('heading', { name: 'Test Intelligence', exact: true })).toBeVisible()

  const scan = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()

  expect(scan.violations, scan.violations.map(violation =>
    `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
  ).join('\n')).toEqual([])
})

test('removes decorative motion when the operator requests reduced motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/system/tests')

  const animationNames = await Promise.all([
    page.locator('.ti-aurora'),
    page.locator('.ti-beam i'),
    page.locator('.ti-health i'),
    page.locator('.ti-stage').first(),
  ].map(locator => locator.evaluate(element => getComputedStyle(element).animationName)))

  expect(animationNames).toEqual(['none', 'none', 'none', 'none'])
  const transitionSeconds = await page.locator('.ti-stage').first().evaluate(element =>
    Number.parseFloat(getComputedStyle(element).transitionDuration),
  )
  // The global reduced-motion guard deliberately uses 0.01 ms instead of a literal zero so
  // transitionend-dependent components still settle; this is five orders below one second.
  expect(transitionSeconds).toBeLessThanOrEqual(0.00001)
})

test('uses Test Intelligence as the only DevOps test-evidence destination', async ({ page }) => {
  await page.route('**/api/devops/dora', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
    overall: 'high', metrics: {
      deploymentFrequency: { level: 'high', description: 'daily' }, leadTime: { level: 'high', description: 'hours' },
      changeFailureRate: { level: 'high', description: 'low' }, mttr: { level: 'high', description: 'hours' },
    }, recentDeployments: [], sources: { git: true, prometheus: true }, collectedAt: '2026-08-24T10:00:00.000Z',
  }) }))
  await page.route('**/api/devops/insights', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ findings: [] }) }))
  await page.route('**/api/test-results', route => route.abort('failed'))

  await page.goto('/devops')
  const destination = page.getByRole('link', { name: /Otevřít Test Intelligence|Open Test Intelligence/ })
  await expect(destination).toBeVisible()
  await destination.click()
  await expect(page).toHaveURL(/\/system\/tests$/)
})
