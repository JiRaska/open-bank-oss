// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const partyId = '00000000-1111-0000-0000-000000000001'
const productId = '00000000-2222-0000-0000-000000000002'
const accountId = '00000000-3333-0000-0000-000000000003'

test.describe('term-deposit account opening', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('selecting an active term-deposit product fills the account and opens it once', async ({ page }) => {
    const accountOpeningPosts: Array<{ idempotencyKey: string; body: Record<string, unknown> }> = []
    await page.route('**/api/svc/product-catalog/api/v1/products?status=ACTIVE', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: productId,
          code: 'TERM_DEPOSIT_6M_CZK',
          name: 'Termínovaný vklad 6 měsíců',
          type: 'TERM_DEPOSIT',
          currency: 'CZK',
          status: 'ACTIVE',
        },
      ]),
    }))
    await page.route('**/api/svc/account-service/api/v1/accounts', async route => {
      const request = route.request()
      expect(request.method()).toBe('POST')
      accountOpeningPosts.push({
        idempotencyKey: request.headers()['idempotency-key'] ?? '',
        body: request.postDataJSON() as Record<string, unknown>,
      })
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ id: accountId }),
      })
    })

    await page.goto('/accounts/new')
    await page.locator('#account-party-id').fill(partyId)
    await page.locator('#account-product-id').selectOption(productId)

    await expect(page.locator('#account-type')).toHaveValue('TERM_DEPOSIT')
    await expect(page.locator('#account-currency')).toHaveValue('CZK')

    await page.locator('#account-legal-name').fill('Test Customer')
    await page.locator('form').evaluate(form => {
      const accountForm = form as HTMLFormElement
      accountForm.requestSubmit()
      accountForm.requestSubmit()
    })

    await expect(page).toHaveURL(`/accounts/${accountId}`)
    expect(accountOpeningPosts).toHaveLength(1)
    expect(accountOpeningPosts[0].idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(accountOpeningPosts[0].body).toMatchObject({
      partyId,
      productId,
      accountType: 'TERM_DEPOSIT',
      currencyCode: 'CZK',
      legalName: 'Test Customer',
    })
  })
})
