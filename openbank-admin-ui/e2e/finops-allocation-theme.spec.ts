// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const allocation = {
  available: true,
  currency: 'USD',
  periodStart: '2026-08-01',
  periodEnd: '2026-08-31',
  total: 1250,
  allocatable: 1000,
  platformOverhead: 250,
  byService: [
    { service: 'payment-service', domain: 'payments', amount: 600, pct: 60, cpuMillis: 500, memMiB: 512 },
    { service: 'compliance-service', domain: 'compliance', amount: 400, pct: 40, cpuMillis: 300, memMiB: 256 },
  ],
  byDomain: [
    { domain: 'payments', amount: 600, pct: 60, serviceCount: 1 },
    { domain: 'compliance', amount: 400, pct: 40, serviceCount: 1 },
  ],
  byFlow: [{ id: 'pay', labelEn: 'Payment execution', labelCs: 'Provedení platby', amount: 700, pct: 70, services: ['payment-service'], regulatoryRef: 'PSD2' }],
  unmapped: ['ingress'],
  method: 'requests-weighted',
  collectedAt: '2026-09-01T12:00:00Z',
  governanceSource: { source: 'published', status: 'ready', sourceSha: 'a'.repeat(40),
    sourceCollectedAt: '2026-09-01T11:00:00Z', fetchedAt: '2026-09-01T12:00:00Z' },
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/build-info', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ version: '4.4.1', gitSha: 'abcdef1234567890', buildDate: '2026-11-07T12:00:00Z' }),
  }))
  await page.route('**/api/finops/allocation', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(allocation) }))
})

test.describe('/finops/allocation — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves source-backed showback and WCAG A/AA`, async ({ page }) => {
      await page.goto('/finops/allocation')
      await expect(page.getByRole('heading', { level: 1, name: /Cost Allocation|Rozpad nákladů/i })).toBeVisible()
      await expect(page.getByText('payment-service')).toBeVisible()
      await expect(page.getByText(/Payment execution|Provedení platby/i)).toBeVisible()
      await expect(page.getByText(/running components have no verified domain|běžících komponent nemá ověřenou doménu/i)).toBeVisible()
      await expect(page.getByText(/per-flow sum can exceed 100%|součet přes procesy proto může přesáhnout 100 %/i)).toBeVisible()

      await expect(page.getByRole('region', { name: /Cost domain classification source|Zdroj klasifikace nákladových domén/i })).toBeVisible()

      const buildBadge = page.getByRole('banner').getByRole('link', { name: /v4\.4\.1.*abcdef1/ })
      await expect(buildBadge).toBeVisible()
      await expect(buildBadge).toHaveAttribute('href', '/docs/release-notes/admin-ui')

      const themeToggle = page.getByRole('banner').getByRole('button', { name: /Switch to the .* theme|Přepnout na .* motiv/i })
      if (theme === 'dark') await themeToggle.click()
      await expect(themeToggle).toHaveAttribute('aria-pressed', String(theme === 'dark'))
      if (theme === 'dark') await expect(page.locator('html')).toHaveClass(/\bdark\b/)
      else await expect(page.locator('html')).not.toHaveClass(/\bdark\b/)

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
