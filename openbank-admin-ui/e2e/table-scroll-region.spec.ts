// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
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
