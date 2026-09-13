// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
})

test('keeps account extent visible while incrementally rendering a bounded result', async ({ page }) => {
  const accounts = Array.from({ length: 30 }, (_, index) => ({
    id: `00000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
    accountNumber: `ACCOUNT-${String(index + 1).padStart(2, '0')}`,
    accountType: 'CURRENT',
    currencyCode: 'CZK',
    status: 'ACTIVE',
    partyId: '11111111-1111-1111-1111-111111111111',
    openedAt: '2026-08-01T00:00:00Z',
  }))
  await page.route('**/api/svc/account-service/api/v1/accounts/search?**', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ data: accounts, pagination: { limit: 30, hasNextPage: false } }),
  }))

  await page.goto('/accounts')
  await page.locator('#accounts-query').fill('account')
  await page.getByRole('button', { name: 'Search accounts' }).click()

  await expect(page.getByText('Showing 25 of 30 accounts')).toBeVisible()
  await expect(page.getByText('ACCOUNT-26', { exact: true })).toHaveCount(0)
  const more = page.getByRole('button', { name: 'Load more accounts' })
  await expect(more).toHaveAttribute('aria-controls', 'accounts-results')
  await more.click()
  await expect(page.getByText('ACCOUNT-30', { exact: true })).toBeVisible()
  await expect(page.getByText('Showing 30 of 30 accounts')).toBeVisible()
  await expect(more).toHaveCount(0)
})

test('keeps masked card extent visible and removes an exhausted action', async ({ page }) => {
  const cards = Array.from({ length: 30 }, (_, index) => ({
    id: `10000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
    partyId: '22222222-2222-2222-2222-222222222222',
    accountId: '33333333-3333-3333-3333-333333333333',
    productCode: 'DEBIT-CLASSIC',
    cardType: 'DEBIT',
    network: 'VISA',
    maskedPan: `411111******${String(index + 1).padStart(4, '0')}`,
    cardholderName: `Cardholder ${index + 1}`,
    embossedName: `CARDHOLDER ${index + 1}`,
    expiryDate: '12/29',
    status: 'ACTIVE',
    dailyLimitMinorUnits: 500000,
    monthlyLimitMinorUnits: 2000000,
    currency: 'CZK',
    createdAt: '2026-08-31T08:00:00Z',
  }))
  await page.route('**/api/svc/card-issuance-service/api/v1/cards', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(cards),
  }))

  await page.goto('/cards')
  await expect(page.getByText('Showing 25 of 30 cards')).toBeVisible()
  await expect(page.getByText('411111******0030')).toHaveCount(0)
  const more = page.getByRole('button', { name: 'Load more cards' })
  await more.click()
  await expect(page.getByText('411111******0030')).toBeVisible()
  await expect(page.getByText('Showing 30 of 30 cards')).toBeVisible()
  await expect(more).toHaveCount(0)
})
