// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/test-intelligence', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
    schemaVersion: 1, collectedAt: '2026-08-22T12:00:00.000Z',
    components: [{ component: 'openbank-ledger-service', released: true, moneyPath: true, evidence: [{ kind: 'integration', state: 'passed', observedAt: '2026-08-22T11:00:00.000Z', source: 'JUnit:test', environment: 'ci', counts: { discovered: 12, executed: 12, passed: 12, failed: 0, skipped: 0, errors: 0 } }], coverage: { state: 'passed', observedAt: '2026-08-22T11:00:00.000Z', source: 'kover.xml', lines: { covered: 80, missed: 20, percentage: 80 }, branches: { covered: 6, missed: 4, percentage: 60 } }, testInfrastructure: { declared: ['postgres'], observed: [{ resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'started', observedAt: '2026-08-22T10:59:00.000Z' }, { resource: 'postgres', image: 'postgres:16.3-alpine', lifecycle: 'stopped', observedAt: '2026-08-22T11:01:00.000Z' }] } }],
    contracts: [], mutations: [], performance: [{ id: 'money-path-smoke', component: null, state: 'passed', observedAt: '2026-08-22T10:00:00.000Z', source: 'perf/k6/money-path-smoke.js', thresholds: 1 }],
    syntheticJourneys: [{ id: 'public-edge', title: 'Public edge reachability', status: 'active', state: 'passed', severity: 'page', schedule: '*/5 * * * *', environment: 'sandbox', covers: [], falsifies: 'Break the public edge.', blocker: null, live: { source: 'prometheus', observedAt: '2026-08-22T12:00:00.000Z', lastScheduledAt: '2026-08-22T11:55:00.000Z', lastSuccessfulAt: '2026-08-22T11:56:00.000Z', failuresLast30m: 0, freshnessSeconds: 240, recentRuns: [{ id: 'public-edge-1', state: 'passed', observedAt: '2026-08-22T11:56:00.000Z' }] } }],
    history: [{ collectedAt: '2026-08-22T12:00:00.000Z', components: 1, componentsWithExecutionEvidence: 1, failingEvidence: 0, missingEvidence: 0, staleEvidence: 0 }],
    runHistory: [{ component: 'openbank-ledger-service', run: { id: '4242', attempt: 1, commit: '1234567890abcdef', branch: 'main', workflow: 'Services CI', url: 'https://example.test/run/4242', observedAt: '2026-08-22T11:00:00.000Z' }, states: { integration: 'passed' }, infrastructureStarted: 1, infrastructureStopped: 1 }],
    testCases: [{ fingerprint: '0123456789abcdef01234567', component: 'openbank-ledger-service', kind: 'integration', classname: 'com.openbank.LedgerApiIT', name: 'posts balanced journal', owner: '@JiRaska', state: 'flaky', lastState: 'passed', observations: 3, failureRate: 33.33, averageDurationMs: 400, wastedDurationMs: 420, sameCommitTransitions: 1, lastObservedAt: '2026-08-22T11:00:00.000Z' }],
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
  await expect(page.getByRole('img', { name: /Animated flow from a code change/i })).toBeVisible()
  await expect(page.getByText('openbank-ledger-service')).toBeVisible()
  await page.getByRole('button', { name: /Testy a flaky|Tests & flaky/ }).click()
  await expect(page.getByText('posts balanced journal')).toBeVisible()
  await expect(page.getByText('1 same-commit pass/fail transition(s)')).toBeVisible()
  await page.getByRole('button', { name: /Testovací runtime|Test runtime/ }).click()
  await expect(page.getByText('1 started · 1 stopped')).toBeVisible()
  await page.getByRole('button', { name: /Historie|History/ }).click()
  await expect(page.getByText('4242 / 1')).toBeVisible()
  await page.getByRole('button', { name: /Syntetika|Synthetics/ }).click()
  await expect(page.getByText('Public edge reachability')).toBeVisible()
  await expect(page.getByText('*/5 * * * *')).toBeVisible()
  await expect(page.getByText('Recent Kubernetes runs')).toBeVisible()
  await expect(page.getByText(/AI AGENT/)).toBeVisible()
})

test('keeps the evidence flow usable at the mobile breakpoint', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/system/tests')
  await expect(page.getByRole('img', { name: /Animated flow from a code change/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /Výkon|Performance/ })).toBeVisible()
})
