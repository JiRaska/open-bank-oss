// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.describe('SCT Inst payee verification', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('blocks a confirmed VoP mismatch and sends exactly one verified instant payment', async ({ page }) => {
    let paymentRequests = 0

    await page.route('**/api/sepa-payments', async route => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ contentType: 'application/json', body: '[]' })
      }

      paymentRequests += 1
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
      expect(await route.request().postDataJSON()).toEqual({
        debtorIban: 'CZ6508000000192000145399',
        creditorIban: 'DE89370400440532013000',
        creditorName: 'Verified Supplier GmbH',
        amount: 125.5,
        currency: 'EUR',
        instant: true,
      })
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'sct-inst-42' }) })
    })
    await page.route('**/api/domestic-payments', route =>
      route.fulfill({ contentType: 'application/json', body: '[]' }),
    )
    await page.route('**/api/svc/vop-service/api/v1/vop/verify', async route => {
      expect(route.request().method()).toBe('POST')
      const body = await route.request().postDataJSON() as { creditorIban: string; creditorName: string }
      expect(body.creditorIban).toBe('DE89370400440532013000')
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ status: body.creditorName.startsWith('Wrong') ? 'no_match' : 'match' }),
      })
    })

    await page.goto('/payments')
    await page.getByRole('button', { name: /New Payment|Nová platba/ }).click()
    await page.getByRole('button', { name: /SEPA Instant/ }).click()

    await page.locator('#sepa-debtor-iban').fill('CZ6508000000192000145399')
    await page.locator('#sepa-creditor-iban').fill('DE89370400440532013000')
    await page.locator('#sepa-creditor-name').fill('Wrong Supplier GmbH')
    await page.locator('#sepa-amount').fill('125.50')

    await page.getByRole('button', { name: /Verify|Ověřit/ }).click()
    await expect(page.getByText(/NO_MATCH/)).toBeVisible()
    await page.getByRole('button', { name: /Send instant|Odeslat okamžitě/ }).click()
    await expect(page.getByText(/Payee name does not match|Jméno příjemce nesouhlasí/)).toBeVisible()
    expect(paymentRequests).toBe(0)

    await page.locator('#sepa-creditor-name').fill('Verified Supplier GmbH')
    await page.getByRole('button', { name: /Verify|Ověřit/ }).click()
    await expect(page.getByText(/MATCH —/)).toBeVisible()
    await page.getByRole('button', { name: /Send instant|Odeslat okamžitě/ }).click()
    const review = page.getByRole('alertdialog', { name: /Review payment order|Zkontrolovat platební příkaz/ })
    await expect(review).toContainText('MATCH')
    await expect(review).toContainText('Verified Supplier GmbH')
    expect(paymentRequests).toBe(0)
    await page.getByRole('button', { name: /Confirm and submit|Potvrdit a odeslat/ }).click()

    await expect(page.getByText(/SCT Inst payment created|SCT Inst platba vytvořena/)).toBeVisible()
    expect(paymentRequests).toBe(1)
  })
})
