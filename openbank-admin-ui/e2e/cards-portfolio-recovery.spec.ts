// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.describe('Masked card portfolio recovery', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('keeps the masked portfolio visible when an explicit refresh fails', async ({ page }) => {
    let requests = 0
    let failRefresh = false

    await page.route('**/api/svc/card-issuance-service/api/v1/cards', async route => {
      requests += 1
      if (!failRefresh) {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify([{
            id: '11111111-1111-1111-1111-111111111111',
            partyId: '22222222-2222-2222-2222-222222222222',
            accountId: '33333333-3333-3333-3333-333333333333',
            productCode: 'DEBIT-CLASSIC',
            cardType: 'DEBIT',
            network: 'VISA',
            maskedPan: '411111******4242',
            cardholderName: 'Verified Cardholder',
            embossedName: 'VERIFIED CARDHOLDER',
            expiryDate: '12/29',
            status: 'ACTIVE',
            dailyLimitMinorUnits: 500000,
            monthlyLimitMinorUnits: 2000000,
            currency: 'CZK',
            createdAt: '2026-08-31T08:00:00Z',
          }]),
        })
        return
      }

      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/cards')
    await expect(page.getByText('411111******4242')).toBeVisible()
    await expect(page.getByText('Verified Cardholder')).toBeVisible()

    const initialRequests = requests
    failRefresh = true
    await page.getByRole('button', { name: /Refresh cards|Obnovit karty/ }).click()

    await expect(page.getByText('411111******4242')).toBeVisible()
    await expect(page.getByText('Verified Cardholder')).toBeVisible()
    await expect(page.getByText(/Failed to load: Cards|Načtení selhalo: Karty/)).toBeVisible()
    expect(requests).toBe(initialRequests + 1)
  })
})
