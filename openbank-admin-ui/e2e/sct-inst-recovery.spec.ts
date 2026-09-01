// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PAYMENT = {
  paymentId: 'sct-inst-42',
  debtorIban: 'CZ6508000000192000145399',
  creditorIban: 'DE89370400440532013000',
  amount: 125.5,
  currency: 'EUR',
  endToEndId: 'E2E-RECOVERY-42',
  status: 'SETTLED',
  createdAt: '2026-08-31T08:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('preserves the last SCT Inst snapshot during an outage and recovers', async ({ page }) => {
  let listRequests = 0
  let listAvailable = true

  await page.route('**/api/sepa-payments', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/domestic-payments', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/svc/sepa-instant/q/health/ready', route =>
    route.fulfill({ status: listAvailable ? 200 : 503, contentType: 'application/json', body: '{}' }),
  )
  await page.route('**/api/svc/sepa-instant/api/v1/sepa-instant', route => {
    listRequests += 1
    if (!listAvailable) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([PAYMENT]) })
  })

  await page.goto('/payments?tab=sct-inst')
  await expect(page.getByText(PAYMENT.endToEndId, { exact: true })).toBeVisible()

  listAvailable = false
  await page.getByRole('button', { name: /Obnovit SCT platby|Refresh SCT payments/ }).click()

  await expect(page.getByRole('status').filter({ hasText: /mohou být zastaralé|may be stale/ })).toBeVisible()
  await expect(page.getByText(PAYMENT.endToEndId, { exact: true })).toBeVisible()
  await expect(page.getByText(/Žádné SCT Inst platby|No SCT Inst payments/)).toHaveCount(0)

  listAvailable = true
  await page.getByRole('button', { name: /Zkusit znovu|Retry/ }).click()
  await expect(page.getByRole('status').filter({ hasText: /mohou být zastaralé|may be stale/ })).toBeHidden()
  await expect(page.getByText(PAYMENT.endToEndId, { exact: true })).toBeVisible()
  expect(listRequests).toBeGreaterThanOrEqual(3)
})
