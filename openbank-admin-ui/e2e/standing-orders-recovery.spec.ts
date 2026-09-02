// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.describe('Standing Orders recovery', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('keeps the last order snapshot visible when explicit refresh fails', async ({ page }) => {
    let requests = 0
    let failRefresh = false

    await page.route('**/api/svc/standing-order-service/api/v1/standing-orders', async route => {
      requests += 1
      if (!failRefresh) {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify([{
            id: '11111111-1111-1111-1111-111111111111',
            debtorAccountId: '22222222-2222-2222-2222-222222222222',
            creditorAccountId: '33333333-3333-3333-3333-333333333333',
            creditorName: 'Verified Supplier SE',
            amount: 7250,
            currency: 'CZK',
            frequency: 'MONTHLY',
            status: 'ACTIVE',
            nextExecutionDate: '2026-09-15',
            description: 'Monthly evidence payment',
          }]),
        })
        return
      }

      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/standing-orders')
    await expect(page.getByText('Verified Supplier SE')).toBeVisible()
    await expect(page.getByText('Monthly evidence payment')).toBeVisible()

    const initialRequests = requests
    failRefresh = true
    await page.getByRole('button', { name: /Refresh standing orders|Obnovit trvalé příkazy/ }).click()

    await expect(page.getByText('Verified Supplier SE')).toBeVisible()
    await expect(page.getByText('Monthly evidence payment')).toBeVisible()
    await expect(page.getByText(/Failed to load: Standing orders|Načtení selhalo: Trvalé příkazy/)).toBeVisible()
    expect(requests).toBe(initialRequests + 1)
  })
})
