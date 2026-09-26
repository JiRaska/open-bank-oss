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
            partyId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            debtorAccountId: '22222222-2222-2222-2222-222222222222',
            creditorIban: 'CZ6508000000192000145399',
            creditorName: 'Verified Supplier SE',
            amountMinorUnits: 725000,
            currency: 'CZK',
            frequency: 'MONTHLY',
            paymentType: 'DOMESTIC',
            status: 'PAUSED',
            nextExecutionDate: '2026-09-15',
            remittanceInfo: 'Monthly evidence payment',
            executionCount: 3,
            createdAt: '2026-01-01T08:00:00Z',
            updatedAt: '2026-09-01T08:00:00Z',
          }]),
        })
        return
      }

      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/standing-orders')
    await expect(page.getByText('Verified Supplier SE')).toBeVisible()
    await expect(page.getByText('Monthly evidence payment')).toBeVisible()
    await expect(page.getByText('PAUSED', { exact: true })).toBeVisible()
    await expect(page.getByText(/Domestic payment|Domácí platba/)).toBeVisible()

    const initialRequests = requests
    failRefresh = true
    await page.getByRole('button', { name: /Refresh standing orders|Obnovit trvalé příkazy/ }).click()

    await expect(page.getByText('Verified Supplier SE')).toBeVisible()
    await expect(page.getByText('Monthly evidence payment')).toBeVisible()
    await expect(page.getByText(/Failed to load: Standing orders|Načtení selhalo: Trvalé příkazy/)).toBeVisible()
    expect(requests).toBe(initialRequests + 1)
  })
})
