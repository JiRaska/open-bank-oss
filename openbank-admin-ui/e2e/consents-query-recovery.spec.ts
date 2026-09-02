// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.describe('Consent lookup recovery', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('retains evidence only for the exact same lens and identifier', async ({ page }) => {
    let failLookup = false
    let requests = 0

    await page.route('**/api/svc/consent-service/api/v1/consents/grantee/**', async route => {
      requests += 1
      if (!failLookup) {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify([{
            id: '11111111-1111-1111-1111-111111111111',
            partyId: '22222222-2222-2222-2222-222222222222',
            granteeId: 'party-service:marketing-comms',
            granteeType: 'INTERNAL_SERVICE',
            granteeName: 'Verified Marketing Service',
            scopes: ['MARKETING_COMMS_EMAIL'],
            accountIbans: null,
            status: 'ACTIVE',
            validFrom: '2026-01-01T00:00:00Z',
            validTo: '2027-01-01T00:00:00Z',
            createdAt: '2026-01-01T00:00:00Z',
          }]),
        })
        return
      }

      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/consents')
    await page.getByLabel(/Consent lookup lens|Pohled souhlasů/).selectOption('grantee')
    const lookup = page.getByRole('button', { name: /Look up|Vyhledat/, exact: true })
    await lookup.click()

    await expect(page.getByText('Verified Marketing Service')).toBeVisible()
    await expect(page.getByText('MARKETING_COMMS_EMAIL')).toBeVisible()

    const successfulRequests = requests
    failLookup = true
    await lookup.click()

    await expect(page.getByText('Verified Marketing Service')).toBeVisible()
    await expect(page.getByText('MARKETING_COMMS_EMAIL')).toBeVisible()
    await expect(page.getByText(/Failed to load: Consents|Načtení selhalo: Souhlasy/)).toBeVisible()
    expect(requests).toBe(successfulRequests + 1)

    await page.getByLabel(/Grantee ID|ID grantee/).fill('different-service:auditing')
    await lookup.click()

    await expect(page.getByText('Verified Marketing Service')).toBeHidden()
    await expect(page.getByText('MARKETING_COMMS_EMAIL')).toBeHidden()
    expect(requests).toBe(successfulRequests + 2)
  })
})
