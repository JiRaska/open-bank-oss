// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps partial evidence usable, paginates it, then purges it on authorization loss', async ({ page }) => {
  let unauthorized = false
  const sepa = (index: number) => ({
    id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    status: 'COMPLETED', amount: 100 + index, currency: 'EUR',
    creditorIban: `DE${String(index).padStart(20, '0')}`,
    creditorName: `SEPA creditor ${index}`,
    createdAt: '2026-08-01T10:00:00Z',
  })

  await page.route('**/api/sepa-payments?*', async route => {
    if (unauthorized) return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' })
    const offset = new URL(route.request().url()).searchParams.get('offset')
    const body = offset === '50' ? [sepa(51)] : Array.from({ length: 50 }, (_, index) => sepa(index + 1))
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.route('**/api/domestic-payments?*', route => route.fulfill({
    status: 502,
    contentType: 'application/json',
    body: '{"error":"upstream_unreachable"}',
  }))

  await page.goto('/payments')
  await expect(page.getByText('SEPA creditor 1', { exact: true })).toBeVisible()
  await expect(page.getByText(/Domestic payment-service is not responding/)).toBeVisible()
  await page.getByRole('button', { name: 'Load more SEPA payments' }).click()
  await expect(page.getByText('SEPA creditor 51')).toBeVisible()

  unauthorized = true
  await page.getByRole('button', { name: 'Refresh payments' }).click()
  await expect(page.getByText('SEPA creditor 1', { exact: true })).toBeHidden()
  await expect(page.getByText('SEPA creditor 51')).toBeHidden()
  await expect(page.getByText(/session has expired/).first()).toBeVisible()
})
