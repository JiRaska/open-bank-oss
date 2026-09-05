// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY = {
  id: '05a02ef1-381c-40e7-b73f-d6855eead42e',
  partyType: 'PERSON',
  status: 'ACTIVE',
  legalName: 'Jan Novák',
  email: 'jan.novak@example.test',
  kycStatus: 'VERIFIED',
  createdAt: '2026-08-01T08:00:00Z',
  updatedAt: '2026-08-31T08:00:00Z',
}

const ACCOUNT = {
  id: 'account-42',
  accountNumber: 'CZ6508000000192000145399',
  currencyCode: 'CZK',
  status: 'ACTIVE',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('distinguishes unavailable related accounts from a party with no accounts and retries', async ({ page }) => {
  let accountRequests = 0
  let accountsAvailable = false
  await page.route(`**/api/svc/party-service/api/v1/parties/${PARTY.id}`, route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(PARTY) }),
  )
  await page.route(`**/api/svc/kyc-service/api/v1/kyc/cases/party/${PARTY.id}`, route =>
    route.fulfill({ status: 404, contentType: 'application/json', body: '{}' }),
  )
  await page.route('**/api/svc/account-service/api/v1/accounts?partyId=*&limit=20', route => {
    accountRequests += 1
    if (!accountsAvailable) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"account service unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: [ACCOUNT] }) })
  })

  await page.goto(`/parties/${PARTY.id}`)
  await expect(page.getByRole('heading', { name: PARTY.legalName })).toBeVisible()

  const unavailable = page.getByRole('status').filter({ hasText: /neznamená, že subjekt nemá žádné účty|does not mean the party has no accounts/ })
  await expect(unavailable).toBeVisible()
  await expect(page.getByText(/Žádné účty|No accounts/)).toHaveCount(0)

  accountsAvailable = true
  await page.getByRole('button', { name: /Zkusit znovu načíst související účty|Retry loading related accounts/ }).click()
  await expect(unavailable).toBeHidden()
  await expect(page.getByText(ACCOUNT.accountNumber, { exact: true })).toBeVisible()
  expect(accountRequests).toBeGreaterThanOrEqual(2)
})
