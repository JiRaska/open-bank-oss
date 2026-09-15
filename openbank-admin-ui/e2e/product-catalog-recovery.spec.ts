// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PRODUCT = {
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
  versionHistory: [
    { version: '1.0.0', validFrom: '2026-01-01', isPublic: true, createdAt: '2026-01-01T00:00:00Z' },
    { version: '0.9.0', validFrom: '2025-12-01', validTo: '2025-12-31', isPublic: false, createdAt: '2025-12-01T00:00:00Z' },
  ],
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last product catalogue visible through a failed refresh and recovery', async ({ page }) => {
  let failNextRequest = false
  await page.route('**/api/svc/product-catalog/api/v1/products', route => {
    if (failNextRequest) {
      failNextRequest = false
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"catalog unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([PRODUCT]) })
  })

  await page.goto('/product-catalog')
  await expect(page.getByText(PRODUCT.code, { exact: true })).toBeVisible()

  failNextRequest = true
  await page.getByRole('button', { name: /Obnovit katalog produktů|Refresh product catalog/ }).click()

  const stale = page.getByRole('status').filter({ hasText: /poslední dostupná data|last available data/ })
  await expect(stale).toContainText(/poslední dostupná data|last available data/)
  await expect(page.getByText(PRODUCT.code, { exact: true })).toBeVisible()
  await expect(page.getByText('1', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: /Obnovit katalog produktů|Refresh product catalog/ }).click()
  await expect(stale).toBeHidden()
  await expect(page.getByText(PRODUCT.code, { exact: true })).toBeVisible()
})

test('renders selected and current-version emphasis with valid computed colours', async ({ page }) => {
  await page.route('**/api/svc/product-catalog/api/v1/products', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify([PRODUCT]) }),
  )

  await page.goto('/product-catalog')
  await page.getByText(PRODUCT.code, { exact: true }).click()
  await page.getByRole('button', { name: /Verze|Versions/ }).click()

  const current = page.getByText(/Aktuální|Current/, { exact: true })
  await expect(current).toBeVisible()
  const badgeStyle = await current.evaluate(element => {
    const style = getComputedStyle(element)
    return { background: style.backgroundColor, border: style.borderColor }
  })
  expect(badgeStyle.background).not.toBe('rgba(0, 0, 0, 0)')
  expect(badgeStyle.border).not.toBe('rgba(0, 0, 0, 0)')

  // The open Radix drawer correctly hides the background table from the accessibility
  // tree, so inspect this purely visual property through a CSS locator.
  const selectedRow = page.locator('tbody tr').filter({ hasText: PRODUCT.code })
  const selectedBackground = await selectedRow.evaluate(element => getComputedStyle(element).backgroundColor)
  expect(selectedBackground).not.toBe('rgba(0, 0, 0, 0)')
})
