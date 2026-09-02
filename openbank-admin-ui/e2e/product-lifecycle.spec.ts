// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const product = {
  id: '00000000-2222-0000-0000-000000000002',
  code: 'TERM_DEPOSIT_6M_CZK',
  name: 'Term Deposit 6M',
  type: 'TERM_DEPOSIT',
  currency: 'CZK',
  status: 'ACTIVE',
  isPublic: true,
  version: '1.0.0',
  revision: 4,
  baseRate: 0.058,
}

test.describe('product lifecycle', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('an operator can deactivate a product and reach governed add-on management', async ({ page }) => {
    await page.route('**/api/svc/product-catalog/api/v1/products', route => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify([product]) })
      }
      return route.fallback()
    })
    await page.route(`**/api/svc/product-catalog/api/v1/products/${product.id}/deactivate`, async route => {
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['if-match']).toBe('"4"')
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ...product, status: 'INACTIVE', revision: 5 }) })
    })

    await page.goto('/product-catalog')
    await expect(page.getByText('TERM_DEPOSIT_6M_CZK')).toBeVisible()
    await expect(page.locator('a.btn[href="/product-studio"]')).toBeVisible()

    page.once('dialog', dialog => dialog.accept())
    await page.locator('button[title="Deactivate"]').click()
  })
})
