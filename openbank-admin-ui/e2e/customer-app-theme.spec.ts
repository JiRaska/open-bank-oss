// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const appStatus = {
  app: { name: 'openbank-app' },
  asOf: '2026-09-10',
  derived: { version: '1.0.0', useFakeData: false, certPinningActive: true, oauthScopes: ['openid'] },
  available: true,
  capabilities: [
    { id: 'payments', title: { cs: 'Platby', en: 'Payments' }, lens: ['technology', 'security'], status: 'live', gap: { cs: 'Živý tok.', en: 'Live flow.' }, resolvedAdrs: [{ id: 'ADR-0074', slug: '0074-customer-app', title: 'Customer app', status: 'Accepted' }], decisionMissing: false },
    { id: 'offline', title: { cs: 'Offline', en: 'Offline' }, lens: ['governance'], status: 'planned', gap: { cs: 'Rozhodnutí chybí.', en: 'Decision missing.' }, resolvedAdrs: [], decisionMissing: true },
  ],
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/customer-app — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme keeps plan-vs-reality understandable and WCAG A/AA`, async ({ page }) => {
      await page.route('**/api/app-status', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(appStatus) }))
      await page.goto('/docs/customer-app')
      await expect(page.getByRole('heading', { level: 1, name: /Customer App — plan vs reality|Aplikace — plán vs realita/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText(/Live \(running today\)|Live \(běží dnes\)/i).first()).toBeVisible()
      await expect(page.getByText(/decision missing|rozhodnutí chybí/i).first()).toBeVisible()
      await page.getByRole('button', { name: /Security|Bezpečnost/i }).click()
      await expect(page.getByText(/Payments|Platby/i).first()).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
