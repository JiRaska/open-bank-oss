// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const services = [
  {
    serviceName: 'account-service', dataDomain: 'core', dataLineageRole: 'producer',
    lineage: {
      downstream: [{ serviceName: 'ledger-service', relationType: 'api' }],
      interfaces: { apis: ['/api/v1/accounts'], topics: ['account.events'], datastores: ['accounts'] },
    },
  },
  {
    serviceName: 'ledger-service', dataDomain: 'payments', dataLineageRole: 'consumer',
    lineage: { upstream: [{ serviceName: 'account-service', relationType: 'api' }] },
  },
]

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/lineage — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves lineage meaning and WCAG A/AA`, async ({ page }) => {
      await page.route('**/api/catalog/governance', route => route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ available: true, services }),
      }))
      await page.goto('/docs/lineage')
      await expect(page.getByRole('heading', { level: 1, name: /Data Lineage Flow|Tok datové lineage/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText('account', { exact: true })).toBeVisible()
      await page.getByText('account', { exact: true }).click()
      await expect(page.getByText('producer', { exact: true })).toBeVisible()
      await expect(page.getByText('API: /api/v1/accounts', { exact: true })).toBeVisible()
      await expect(page.getByText('DB: accounts', { exact: true })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
