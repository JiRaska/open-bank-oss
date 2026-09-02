// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.describe('Transaction ledger search recovery', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('keeps the last successful result visible when a repeated search is unavailable', async ({ page }) => {
    let requests = 0

    await page.route('**/api/svc/transaction-service/api/v1/transactions/search**', async route => {
      requests += 1
      expect(route.request().url()).toContain('iban=CZ6508000000192000145399')

      if (requests === 1) {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            data: [{
              id: 'transaction-42',
              referenceNumber: 'TXN-EVIDENCE-42',
              type: 'CREDIT',
              sourceAccountId: '11111111-1111-1111-1111-111111111111',
              targetAccountId: '22222222-2222-2222-2222-222222222222',
              amount: 1250,
              currencyCode: 'CZK',
              status: 'COMPLETED',
              description: 'Verified settlement',
              valueDate: '2026-08-31',
              bookingDate: '2026-08-31',
              initiatedAt: '2026-08-31T08:00:00Z',
            }],
            count: 1,
            limit: 50,
            offset: 0,
          }),
        })
        return
      }

      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/transactions')
    await page.getByLabel(/Filter by IBAN|Filtrovat podle IBAN/).fill('CZ6508000000192000145399')
    await page.getByRole('button', { name: /Search transactions|Vyhledat transakce/ }).click()

    await expect(page.getByText('TXN-EVIDENCE-42')).toBeVisible()
    await expect(page.getByText('Verified settlement')).toBeVisible()

    await page.getByRole('button', { name: /Search transactions|Vyhledat transakce/ }).click()

    await expect(page.getByText('TXN-EVIDENCE-42')).toBeVisible()
    await expect(page.getByText('Verified settlement')).toBeVisible()
    await expect(page.getByText(/Failed to load: Transaction search|Načtení selhalo: Vyhledávání transakcí/)).toBeVisible()
    expect(requests).toBe(2)
  })
})
