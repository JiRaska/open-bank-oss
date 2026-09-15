// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const lifecycle = {
  currentVersion: '1.32', currentTier: 'standard', daysToStandardEnd: 120, runwayStatus: 'ok',
  standardSupportEnds: '2027-01-01', extendedSupportEnds: '2028-01-01', monthlyEstimate: 1000,
  annualEstimate: 12000, monthlySavingsVsExtended: 100, annualSavingsVsExtended: 1200,
  minRunwayDays: 90, versions: [], components: [], dataSource: 'embedded',
  lastRefreshed: '2026-09-15T00:00:00Z', adrRef: 'ADR-0054',
}

test('presents agent costs as labelled evidence without mobile overflow', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.setViewportSize({ width: 320, height: 800 })
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
  await page.route('**/api/finops/lifecycle', route => route.fulfill({ json: lifecycle }))
  for (const endpoint of ['resources', 'costs', 'right-sizing']) {
    await page.route(`**/api/finops/${endpoint}`, route => route.fulfill({ json: { available: false } }))
  }
  await page.route('**/api/finops/anomalies', route => route.fulfill({ json: { anomalies: [] } }))
  await page.route('**/api/finops/ai-costs', route => route.fulfill({ json: {
    available: true,
    collectedAt: '2026-09-15T08:00:00Z',
    totalCostLast7dUsd: 42.5,
    totalCostLast30dUsd: 180,
    selfHostedPct: 75,
    coverage: {
      source: 'prometheus', retentionHours: 720, dataFrom: '2026-09-08T08:00:00Z', dataTo: '2026-09-15T08:00:00Z',
      lastSuccessfulLoad: '2026-09-15T08:00:00Z',
      windows: {
        '24h': { requestedHours: 24, availableHours: 24, partial: false },
        '7d': { requestedHours: 168, availableHours: 168, partial: false },
        '30d': { requestedHours: 720, availableHours: 720, partial: false },
      },
    },
    agents: [{
      agentId: 'case-coordinator-agent-with-a-long-identifier', model: 'vllm', tokensLast24h: 1000, tokensLast7d: 7000,
      costLast24hUsd: 6.5, costLast7dUsd: 42.5, budgetMonthlyUsd: 200, budgetUsedPct: 63, burnRate: 'normal', anomalyZ: null,
    }],
    anomalies: [],
  } }))

  await page.goto('/finops')
  const agent = page.getByText('case-coordinator-agent-with-a-long-identifier', { exact: true })
  await expect(agent).toBeVisible()
  await expect.poll(() => agent.evaluate(element => getComputedStyle(element, '::before').content)).toBe('"Agent"')
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
