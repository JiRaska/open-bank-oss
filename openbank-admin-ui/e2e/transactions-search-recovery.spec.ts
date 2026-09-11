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
              id: '33333333-3333-4333-8333-333333333333',
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
              completedAt: '2026-08-31T08:00:01Z',
            }],
            count: 1,
            limit: 51,
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
    const amount = page.getByText(/1.*250.*CZK/).first()
    await expect(amount).toBeVisible()
    expect(await amount.textContent()).not.toMatch(/[+-]/)

    await page.getByRole('button', { name: /Search transactions|Vyhledat transakce/ }).click()

    await expect(page.getByText('TXN-EVIDENCE-42')).toBeVisible()
    await expect(page.getByText('Verified settlement')).toBeVisible()
    await expect(page.getByText(/Failed to load: Transaction search|Načtení selhalo: Vyhledávání transakcí/)).toBeVisible()
    expect(requests).toBe(2)
  })

  test('explains an empty result and offers a keyboard-accessible recovery action', async ({ page }) => {
    await page.route('**/api/svc/transaction-service/api/v1/transactions/search**', route =>
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ data: [], count: 0, limit: 50, offset: 0 }),
      }),
    )

    await page.goto('/transactions')
    await expect(page.getByRole('status')).toContainText('Find a transaction using the details you have')

    await page.getByLabel('Filter by IBAN').fill('CZ6508000000192000145399')
    await page.getByRole('button', { name: 'Search transactions' }).click()

    const empty = page.getByRole('status')
    await expect(empty).toContainText('No transactions match your search criteria')
    await expect(empty).toContainText('not that the service is unavailable')

    const clear = page.getByRole('button', { name: 'Clear search criteria' })
    await clear.focus()
    await expect(clear).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByLabel('Filter by IBAN')).toHaveValue('')
    await expect(clear).toHaveCount(0)

    await page.locator('html').evaluate(element => element.classList.add('dark'))
    const background = await page.locator('.ui-empty-state').evaluate(element => getComputedStyle(element).backgroundImage)
    expect(background).not.toBe('none')
  })

  test('rejects a malformed successful response without replacing verified money-path evidence', async ({ page }) => {
    let malformed = false
    await page.route('**/api/svc/transaction-service/api/v1/transactions/search**', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [{
          id: '33333333-3333-4333-8333-333333333333', referenceNumber: 'TXN-EVIDENCE-42',
          type: 'CREDIT', sourceAccountId: null, targetAccountId: '22222222-2222-4222-8222-222222222222',
          amount: 1250, currencyCode: 'CZK', status: malformed ? 'SETTLED' : 'COMPLETED', description: 'Verified settlement',
          valueDate: '2026-08-31', bookingDate: '2026-08-31', initiatedAt: '2026-08-31T08:00:00Z', completedAt: '2026-08-31T08:00:01Z',
        }],
        count: 1, limit: 51, offset: 0,
      }),
    }))

    await page.goto('/transactions')
    await page.getByLabel(/Filter by IBAN|Filtrovat podle IBAN/).fill('CZ6508000000192000145399')
    const search = page.getByRole('button', { name: /Search transactions|Vyhledat transakce/ })
    await search.click()
    await expect(page.getByText('TXN-EVIDENCE-42')).toBeVisible()

    malformed = true
    await search.click()
    await expect(page.getByText('TXN-EVIDENCE-42')).toBeVisible()
    await expect(page.getByText(/Failed to load: Transaction search|Načtení selhalo: Vyhledávání transakcí/)).toBeVisible()
    await expect(page.getByText('SETTLED')).toBeHidden()
  })

  test('purges retained transaction evidence when authorization is lost', async ({ page }) => {
    let unauthorized = false
    await page.route('**/api/svc/transaction-service/api/v1/transactions/search**', route => {
      if (unauthorized) return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' })
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          data: [{
            id: '33333333-3333-4333-8333-333333333333', referenceNumber: 'TXN-PRIVATE-42',
            type: 'CREDIT', sourceAccountId: null, targetAccountId: null, amount: 1250,
            currencyCode: 'CZK', status: 'COMPLETED', description: 'Restricted evidence',
            valueDate: '2026-08-31', bookingDate: '2026-08-31', initiatedAt: '2026-08-31T08:00:00Z', completedAt: '2026-08-31T08:00:01Z',
          }],
          count: 1, limit: 51, offset: 0,
        }),
      })
    })

    await page.goto('/transactions')
    const search = page.getByRole('button', { name: /Search transactions|Vyhledat transakce/ })
    await search.click()
    await expect(page.getByText('TXN-PRIVATE-42')).toBeVisible()

    unauthorized = true
    await search.click()
    await expect(page.getByText(/Session expired|Vypršela relace/i)).toBeVisible()
    await expect(page.getByText('TXN-PRIVATE-42')).toHaveCount(0)
  })
})
