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
    let deactivationRequests = 0
    let activationRequests = 0
    let currentProduct = product
    const catalogueReads: Array<{ status: string; revision: number }> = []
    await page.route('**/api/svc/product-catalog/api/v1/products', route => {
      if (route.request().method() === 'GET') {
        catalogueReads.push({ status: currentProduct.status, revision: currentProduct.revision })
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify([currentProduct]) })
      }
      return route.fallback()
    })
    await page.route(`**/api/svc/product-catalog/api/v1/products/${product.id}/deactivate`, async route => {
      deactivationRequests += 1
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['if-match']).toBe('"4"')
      currentProduct = { ...product, status: 'INACTIVE', revision: 5 }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentProduct) })
    })
    await page.route(`**/api/svc/product-catalog/api/v1/products/${product.id}/activate`, async route => {
      activationRequests += 1
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['if-match']).toBe('"5"')
      currentProduct = { ...currentProduct, status: 'ACTIVE', revision: 6 }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentProduct) })
    })

    await page.goto('/product-catalog')
    await expect(page.getByText('TERM_DEPOSIT_6M_CZK')).toBeVisible()
    await expect(page.locator('a.btn[href="/product-studio"]')).toBeVisible()

    await page.getByRole('button', { name: 'Deactivate product', exact: true }).click()

    const dialog = page.getByRole('dialog', { name: 'Deactivate product?' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(product.name)
    await expect(dialog).toContainText(product.code)
    await expect(dialog.getByText('ACTIVE', { exact: true })).toBeVisible()
    await expect(dialog.getByText('INACTIVE', { exact: true })).toBeVisible()
    await expect(dialog).toContainText('History and existing accounts remain available. The product stops being offered for new accounts.')
    expect(deactivationRequests).toBe(0)

    await dialog.getByRole('button', { name: `Confirm deactivation of ${product.name}`, exact: true }).click()

    await expect.poll(() => deactivationRequests).toBe(1)
    await expect.poll(() => catalogueReads[catalogueReads.length - 1]).toEqual({ status: 'INACTIVE', revision: 5 })
    await expect(dialog).toBeHidden()
    const productRow = page.getByRole('row').filter({ hasText: product.code })
    await expect(productRow.getByText('INACTIVE', { exact: true })).toBeVisible()
    const activate = productRow.getByRole('button', { name: 'Activate product', exact: true })
    await expect(activate).toBeVisible()
    expect(deactivationRequests).toBe(1)

    await activate.click()
    const activationDialog = page.getByRole('dialog', { name: 'Activate product?' })
    await expect(activationDialog).toBeVisible()
    await expect(activationDialog.getByText('INACTIVE', { exact: true })).toBeVisible()
    await expect(activationDialog.getByText('ACTIVE', { exact: true })).toBeVisible()
    expect(activationRequests).toBe(0)

    await activationDialog.getByRole('button', { name: `Confirm activation of ${product.name}`, exact: true }).click()

    await expect.poll(() => activationRequests).toBe(1)
    await expect.poll(() => catalogueReads[catalogueReads.length - 1]).toEqual({ status: 'ACTIVE', revision: 6 })
    await expect(activationDialog).toBeHidden()
    await expect(productRow.getByText('ACTIVE', { exact: true })).toBeVisible()
    await expect(productRow.getByRole('button', { name: 'Deactivate product', exact: true })).toBeVisible()
    expect(deactivationRequests).toBe(1)
    expect(activationRequests).toBe(1)
  })
})
