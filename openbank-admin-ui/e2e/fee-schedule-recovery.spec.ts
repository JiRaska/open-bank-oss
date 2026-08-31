// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const FEE = {
  id: 'fee-42',
  code: 'SEPA_INSTANT_OUT',
  name: 'SEPA Instant outgoing transfer',
  productCode: 'CURRENT_ACCOUNT',
  productName: 'Current Account',
  type: 'TRANSACTION',
  amount: 1.5,
  currency: 'EUR',
  frequency: 'PER_TRANSACTION',
  status: 'ACTIVE',
  waivable: false,
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last fee schedule during an outage and recovers', async ({ page }) => {
  let available = true
  let requests = 0
  await page.route('**/api/svc/product-catalog/api/v1/fees', route => {
    requests += 1
    if (!available) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([FEE]) })
  })

  await page.goto('/fees')
  await expect(page.getByText(FEE.code, { exact: true })).toBeVisible()
  await expect(page.getByText('1', { exact: true }).first()).toBeVisible()

  available = false
  await page.getByRole('button', { name: /Obnovit ceník poplatků|Refresh fee schedule/ }).click()

  await expect(page.getByText(/údaje mohou být zastaralé|data may be stale/)).toBeVisible()
  await expect(page.getByText(FEE.code, { exact: true })).toBeVisible()
  await expect(page.getByText(/Žádné poplatky nenalezeny|No fees found/)).toHaveCount(0)

  available = true
  await page.getByRole('button', { name: /Obnovit ceník poplatků|Refresh fee schedule/ }).click()
  await expect(page.getByText(/údaje mohou být zastaralé|data may be stale/)).toBeHidden()
  await expect(page.getByText(FEE.code, { exact: true })).toBeVisible()
  expect(requests).toBeGreaterThanOrEqual(3)
})
