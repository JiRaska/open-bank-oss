// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CARD = {
  id: '00000000-cccc-0000-0000-000000000001',
  partyId: '00000000-0000-0000-0000-000000000001',
  accountId: '00000000-0000-0000-0000-000000000002',
  productCode: 'DEBIT_STD',
  cardType: 'DEBIT',
  network: 'VISA',
  maskedPan: '4111 11** **** 1111',
  cardholderName: 'Alice Testerova',
  embossedName: 'ALICE TESTEROVA',
  expiryDate: '2029-08',
  status: 'ACTIVE',
  dailyLimitMinorUnits: 500000,
  monthlyLimitMinorUnits: 5000000,
  currency: 'CZK',
  createdAt: '2026-01-10T09:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last card portfolio visible through a failed refresh and recovers on retry', async ({ page }) => {
  let failNextRequest = false
  await page.route('**/api/svc/card-issuance-service/api/v1/cards', route => {
    if (failNextRequest) {
      failNextRequest = false
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"card-issuance-service unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([CARD]) })
  })

  await page.goto('/cards')
  await expect(page.getByText(CARD.maskedPan, { exact: true })).toBeVisible()
  await expect(page.getByText(CARD.cardholderName)).toBeVisible()

  failNextRequest = true
  await page.getByRole('button', { name: /Obnovit karty|Refresh cards/ }).click()

  const stale = page.getByRole('status').filter({ hasText: /poslední dostupná data|last available data/ })
  await expect(stale).toBeVisible()
  // The portfolio table and its KPIs must stay on screen — an outage banner
  // replacing them would make an operator read "no cards" as the truth.
  await expect(page.getByText(CARD.maskedPan, { exact: true })).toBeVisible()
  await expect(page.getByText(CARD.cardholderName)).toBeVisible()

  await page.getByRole('button', { name: /Obnovit karty|Refresh cards/ }).click()
  await expect(stale).toBeHidden()
  await expect(page.getByText(CARD.maskedPan, { exact: true })).toBeVisible()
})
