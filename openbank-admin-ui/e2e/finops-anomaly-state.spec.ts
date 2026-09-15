// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const LIFECYCLE = {
  currentVersion: '1.32', currentTier: 'standard', daysToStandardEnd: 120, runwayStatus: 'ok',
  standardSupportEnds: '2027-01-01', extendedSupportEnds: '2028-01-01', monthlyEstimate: 1000,
  annualEstimate: 12000, monthlySavingsVsExtended: 100, annualSavingsVsExtended: 1200,
  minRunwayDays: 90, versions: [], components: [], dataSource: 'embedded',
  lastRefreshed: '2026-09-15T00:00:00Z', adrRef: 'ADR-0054',
}

test('distinguishes an unreachable anomaly source from verified zero findings', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let anomalySourceAvailable = false
  await page.route('**/api/finops/lifecycle', route => route.fulfill({ json: LIFECYCLE }))
  for (const endpoint of ['resources', 'costs', 'right-sizing', 'ai-costs']) {
    await page.route(`**/api/finops/${endpoint}`, route => route.fulfill({ json: { available: false } }))
  }
  await page.route('**/api/finops/anomalies', route => anomalySourceAvailable
    ? route.fulfill({ json: { anomalies: [] } })
    : route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' }))

  await page.goto('/finops')
  await expect(page.getByText('Detector state cannot be verified. An empty panel would not prove that no cost anomaly is firing.')).toBeVisible()
  await expect(page.getByText('Alertmanager answered successfully and returned no active finops-agent alerts.')).toHaveCount(0)

  anomalySourceAvailable = true
  await page.getByRole('button', { name: 'Refresh FinOps costs' }).click()
  await expect(page.getByText('Alertmanager answered successfully and returned no active finops-agent alerts.')).toBeVisible()
  await expect(page.getByText(/Detector state cannot be verified/)).toHaveCount(0)
})
