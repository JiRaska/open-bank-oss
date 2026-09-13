// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY_ID = '11111111-1111-4111-8111-111111111111'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('rejects malformed loyalty evidence and recovers with verified customer data', async ({ page }) => {
  await page.route('**/api/loyalty', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      state: 'ok',
      benefits: [],
      earnSources: [],
      provisioning: {
        at: '2026-09-09T08:00:00Z',
        outstandingLeaves: 1200,
        annualCapPerParty: 1000,
        ruleVersion: 'v1',
      },
    }),
  }))

  let lookup = 0
  await page.route('**/api/loyalty/party/*', route => {
    lookup += 1
    const body = lookup === 1
      ? { state: 'ok', partyId: '22222222-2222-4222-8222-222222222222', balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [] }
      : { state: 'ok', partyId: PARTY_ID, balance: 80, earnedThisYear: 120, earnedTotal: 300, nextExpiry: null, history: [] }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/loyalty')
  await page.getByRole('button', { name: /Customer|Klient/ }).click()
  await page.getByLabel(/Customer UUID|UUID klienta/).fill(PARTY_ID)
  const submit = page.getByRole('button', { name: /Look up|Vyhledat/ })

  await submit.click()
  await expect(page.getByText(/Loyalty-service is not responding|Loyalty-service neodpovídá/)).toBeVisible()
  await expect(page.getByText('80', { exact: true })).toHaveCount(0)

  await submit.click()
  await expect(page.getByText('80', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /Open Customer 360|Otevřít Customer 360/ })).toHaveAttribute('href', `/customer-360?partyId=${PARTY_ID}`)
})
