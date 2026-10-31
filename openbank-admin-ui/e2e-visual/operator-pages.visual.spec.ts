// SPDX-License-Identifier: Apache-2.0
// Visual regression baselines for the key operator pages (playwright.visual.config.ts).
//
// Determinism contract — every input a pixel depends on is pinned here:
//   * data: every browser-side /api/** call is answered from the fixtures below; an
//     unexpected endpoint gets one fixed 404 body, so a new fetch cannot pull live state in
//   * clock: Date is frozen, so "3 minutes ago" renders the same text on every run
//   * theme + language: set before first paint (localStorage + cookie), not by clicking
//   * motion: reducedMotion + toHaveScreenshot({ animations: 'disabled' })
//   * server: all four pages are 'use client'; their route layouts read no data and no clock,
//     and the root layout reads only the cookies minted here. Every data fetch is therefore a
//     browser /api call (pinned above). SSR may format a time with the real clock, but the
//     client re-renders under the frozen clock before the screenshot. Re-check this if a page
//     becomes a server component or a layout starts fetching.
// A failure therefore means the rendered page changed. Update baselines only through
// `npm run test:visual:update` (container), never with a host-local --update-snapshots.

import { expect, test, type Page } from '@playwright/test'
import { signInAsOperator } from '../e2e/helpers/auth'
import { APPROVAL_DOMAINS } from '../src/lib/approvals/evidence'

const FROZEN_NOW = new Date('2026-10-19T12:00:00Z')

const run = {
  id: '4242', attempt: 1, commit: '1234567890abcdef', branch: 'main', workflow: 'CI',
  url: 'https://example.test/run/4242', observedAt: '2026-10-19T11:00:00.000Z',
}
const counts = (passed: number, failed = 0) => ({
  discovered: passed + failed, executed: passed + failed, passed, failed, skipped: 0, errors: 0,
})

const testIntelligence = {
  schemaVersion: 1, collectedAt: '2026-10-19T11:30:00.000Z',
  components: [
    {
      component: 'openbank-ledger-service', released: true, moneyPath: true,
      evidence: [
        { kind: 'unit', state: 'passed', observedAt: run.observedAt, source: 'JUnit:test', environment: 'ci', counts: counts(212), run },
        { kind: 'integration', state: 'passed', observedAt: run.observedAt, source: 'JUnit:integrationTest', environment: 'ci', counts: counts(48), run },
        { kind: 'contract', state: 'passed', observedAt: run.observedAt, source: 'Pact:provider', environment: 'ci' },
      ],
      coverage: { state: 'passed', observedAt: run.observedAt, source: 'kover.xml', lines: { covered: 880, missed: 120, percentage: 88 }, branches: { covered: 70, missed: 30, percentage: 70 } },
      testInfrastructure: { declared: ['postgres'], observed: [] },
    },
    {
      component: 'openbank-admin-ui', released: true, moneyPath: false,
      evidence: [
        { kind: 'unit', state: 'passed', observedAt: run.observedAt, source: 'JUnit:test', environment: 'ci', counts: counts(1900), run },
        { kind: 'e2e', state: 'failed', observedAt: run.observedAt, source: 'test-intelligence-run:v1', environment: 'ci', counts: counts(240, 1), run },
        { kind: 'visual', state: 'passed', observedAt: run.observedAt, source: 'playwright-visual:openbank-admin-ui', environment: 'ci', detail: '8/8 screenshots matched committed baselines', run },
      ],
      coverage: { state: 'not-run', observedAt: null, source: null, lines: { covered: 0, missed: 0, percentage: null }, branches: { covered: 0, missed: 0, percentage: null } },
      testInfrastructure: { declared: [], observed: [] },
    },
  ],
  contracts: [], mutations: [], requiredControls: [], platformCapabilities: [],
  performance: [], performanceHistory: [], syntheticJourneys: [],
  journeyCoverage: { moneyPathTotal: 1, activelyCovered: 0, explicitlyUnwatched: 1, services: [] },
  clientExperiences: [],
  history: [{ collectedAt: '2026-10-19T11:30:00.000Z', components: 2, componentsWithExecutionEvidence: 2, failingEvidence: 1, missingEvidence: 0, staleEvidence: 0 }],
  runHistory: [], testCases: [],
  totals: { components: 2, componentsWithExecutionEvidence: 2, moneyPathComponents: 1, failingEvidence: 1, missingEvidence: 0, staleEvidence: 0, requiredControls: 0, requiredControlGaps: 0 },
  warnings: [],
}

const approvals = {
  items: [
    { id: 'sct-inst-approval-7', domain: 'sepa-instant', action: 'payment.release', resourceId: 'payment-7', maker: 'maker.operator', proposedAt: '2026-10-19T11:59:00Z' },
    { id: 'ledger-approval-3', domain: 'ledger', action: 'journal.post', resourceId: 'journal-3', maker: 'finance.maker', proposedAt: '2026-10-19T10:15:00Z' },
  ],
  sources: Object.fromEntries(APPROVAL_DOMAINS.map(domain => [domain, ['sepa-instant', 'ledger'].includes(domain) ? 'ok' : 'not-configured'])),
}

const finops: Record<string, unknown> = {
  lifecycle: {
    currentVersion: '1.36', currentTier: 'standard', daysToStandardEnd: 365, runwayStatus: 'ok',
    standardSupportEnds: '2027-10-19', extendedSupportEnds: '2028-10-19', monthlyEstimate: 73,
    annualEstimate: 876, monthlySavingsVsExtended: 365, annualSavingsVsExtended: 4380,
    minRunwayDays: 180, versions: [], components: [], dataSource: 'snapshot', lastRefreshed: '2026-10-19', adrRef: 'ADR-0054',
  },
  costs: {
    available: true, currency: 'USD', periodStart: '2026-10-01', periodEnd: '2026-10-19', total: 4321,
    services: [], daily: [], collectedAt: '2026-10-19T10:00:00Z', source: 'snapshot',
  },
}

const fixtures: Record<string, unknown> = {
  '/api/test-intelligence': testIntelligence,
  '/api/test-intelligence/agents': { findings: [], available: true },
  '/api/approvals/pending': approvals,
  '/api/governance/agent-identities': { available: true, agents: [] },
  '/api/agent/proposals': [],
}

async function pinInputs(page: Page, theme: 'light' | 'dark', baseURL: string) {
  await page.clock.setFixedTime(FROZEN_NOW)
  await signInAsOperator(page.context(), baseURL)
  await page.context().addCookies([
    { name: 'openbank-admin-lang', value: 'en', url: baseURL },
    { name: 'ob-admin-theme', value: theme, url: baseURL },
  ])
  await page.addInitScript(value => localStorage.setItem('ob-admin-theme', value), theme)
  await page.route('**/api/**', route => {
    const { pathname } = new URL(route.request().url())
    // Auth.js owns its own session endpoints; the minted cookie answers them.
    if (pathname.startsWith('/api/auth/')) return route.fallback()
    if (pathname in fixtures) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(fixtures[pathname]) })
    }
    if (pathname.startsWith('/api/finops/')) {
      const body = finops[pathname.split('/').pop() ?? ''] ?? { available: false }
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'Unknown service' }) })
  })
}

const pages = [
  { id: 'security-posture', path: '/security', heading: /Security/ },
  { id: 'test-intelligence', path: '/system/tests', heading: 'Test Intelligence' },
  { id: 'finops', path: '/finops', heading: /FinOps/ },
  { id: 'approvals', path: '/approvals', heading: /Approval/ },
] as const

for (const target of pages) {
  test(`${target.id} matches its committed baseline`, async ({ page, baseURL }, testInfo) => {
    const theme = testInfo.project.name as 'light' | 'dark'
    await pinInputs(page, theme, baseURL!)
    await page.goto(target.path)
    await expect(page.getByRole('heading', { level: 1, name: target.heading }).first()).toBeVisible()
    await page.waitForLoadState('networkidle')
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*\bdark\b)/)
    await expect(page).toHaveScreenshot(`${target.id}-${theme}.png`, { fullPage: false })
  })
}
