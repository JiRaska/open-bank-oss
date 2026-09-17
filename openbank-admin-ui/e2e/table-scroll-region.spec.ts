// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
})

test('keeps the financial tie-out table keyboard-scrollable at mobile width', async ({ page }) => {
  await page.route('**/api/svc/balance-service/api/v1/balances/reconciliation/latest', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      asOf: '2026-09-17', generatedAt: '2026-09-17T23:30:00Z', tolerance: '0.01',
      currencies: [{
        currency: 'EUR', ledgerControlBalance: '10.00', subLedgerBookedSum: '10.00',
        futureValueDatedPipeline: '0.00', difference: '0.00', withinTolerance: true,
      }],
    }),
  }))
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/day-end?tab=eod')

  const region = page.getByRole('region', { name: 'Scrollable per-currency tie-out table' })
  await expect(region.getByRole('table')).toBeVisible()
  expect(await region.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true)
  await region.focus()
  await expect(region).toBeFocused()
  await page.keyboard.press('ArrowRight')
  await expect.poll(() => region.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
})

test('keeps loaded FinOps evidence tables keyboard-scrollable and accessible', async ({ page }) => {
  await page.route('**/api/finops/**', route => {
    const path = new URL(route.request().url()).pathname
    const lifecycle = {
        currentVersion: '1.33', currentTier: 'standard', daysToStandardEnd: 180,
        runwayStatus: 'ok', standardSupportEnds: '2027-03-01', extendedSupportEnds: '2028-03-01',
        monthlyEstimate: 100, annualEstimate: 1200, monthlySavingsVsExtended: 10,
        annualSavingsVsExtended: 120, minRunwayDays: 90, dataSource: 'embedded',
        lastRefreshed: '2026-09-17', adrRef: 'ADR-0054', components: [],
        versions: [{
          version: '1.33', eksRelease: '2026-01-01', standardSupportEnds: '2027-03-01',
          extendedSupportEnds: '2028-03-01', daysToStandardEnd: 180, daysToExtendedEnd: 545,
          tier: 'standard', runwayStatus: 'ok', isCurrent: true,
        }],
      }
    const resources = {
      available: true, fleetHeapPct: 42, underutilisedCount: 0, serviceCount: 1,
      collectedAt: '2026-09-17T08:00:00Z', services: [{
        name: 'account-service', short: 'Accounts', heap: { usedBytes: 1024, maxBytes: 2048, pct: 50 },
        cpuCoresUsed: 0.2, requestsPerSec: 10, efficiency: 'normal',
      }],
    }
    const rightSizing = {
      available: true, collectedAt: '2026-09-17T08:00:00Z', fleetCpuEfficiencyPct: 40,
      fleetMemEfficiencyPct: 50, highSavingsCount: 0, vpaHasData: false,
      services: [{
        namespace: 'accounts', displayName: 'Accounts',
        cpu: { requestedMillicores: 500, usedMillicores: 200, efficiencyPct: 40 },
        memory: { requestedMiB: 512, usedMiB: 256, efficiencyPct: 50 },
        savingsPotential: 'low', vpaRecommendation: { cpuMillicores: null, memoryMiB: null },
      }],
    }
    const data = path.endsWith('/lifecycle') ? lifecycle
      : path.endsWith('/resources') ? resources
        : path.endsWith('/right-sizing') ? rightSizing : null
    if (!data) return route.fulfill({ status: 503, body: '{}' })
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
  })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/finops')

  for (const name of [
    'Scrollable EKS version table',
    'Scrollable service utilisation table',
    'Scrollable right-sizing recommendations table',
  ]) {
    const region = page.getByRole('region', { name })
    await expect(region.getByRole('table')).toBeVisible()
    expect(await region.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true)
    await region.focus()
    await expect(region).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect.poll(() => region.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)

    const scan = await new AxeBuilder({ page })
      .include(`[aria-label="${name}"]`)
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations, `${name}: ${scan.violations.map(violation => violation.id).join(', ')}`).toEqual([])
  }
})
