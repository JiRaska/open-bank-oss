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
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/finops/allocation', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(allocation) }))
})

test.describe('/finops/allocation — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves source-backed showback and WCAG A/AA`, async ({ page }) => {
      await page.goto('/finops/allocation')
      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByRole('heading', { level: 1, name: /Cost Allocation|Rozpad nákladů/i })).toBeVisible()
      await expect(page.getByText('payment-service')).toBeVisible()
      await expect(page.getByText(/Payment execution|Provedení platby/i)).toBeVisible()
      await expect(page.getByText(/running components.*not in the governance manifest|běžících komponent.*není v governance manifestu/i)).toBeVisible()
      await expect(page.getByText(/per-flow sum can exceed 100%|součet přes procesy proto může přesáhnout 100 %/i)).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
