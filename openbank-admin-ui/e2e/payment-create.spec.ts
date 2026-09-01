// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('creates one SEPA payment with one idempotent BFF request under a double submit', async ({ page }) => {
  const posts: { key: string | null; body: Record<string, unknown> }[] = []

  await page.route('**/api/sepa-payments', async route => {
    const request = route.request()
    if (request.method() === 'POST') {
      posts.push({
        key: request.headers()['idempotency-key'] ?? null,
        body: request.postDataJSON() as Record<string, unknown>,
      })
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'sepa-e2e-1' }) })
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [] }) })
  })
  await page.route('**/api/domestic-payments', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: [] }),
  }))

  await page.goto('/payments')
  await page.getByRole('button', { name: 'New Payment' }).click()
  await page.getByRole('button', { name: /SEPA Credit Transfer/ }).click()

  await page.getByLabel('Debtor IBAN').fill('CZ6508000000192000145399')
  await page.getByLabel('Creditor IBAN').fill('DE89370400440532013000')
  await page.getByLabel('Creditor Name').fill('Jane Doe')
  await page.getByLabel('Amount (EUR)').fill('100.00')

  const form = page.getByLabel('Debtor IBAN').locator('xpath=ancestor::form')
  await form.evaluate(element => {
    const paymentForm = element as HTMLFormElement
    paymentForm.requestSubmit()
    paymentForm.requestSubmit()
  })

  await expect(page.getByText('SEPA payment created')).toBeVisible()
  expect(posts).toHaveLength(1)
  expect(posts[0].key).toMatch(/^[0-9a-f-]{8,}$/)
  expect(posts[0].body).toMatchObject({
    debtorIban: 'CZ6508000000192000145399',
    creditorIban: 'DE89370400440532013000',
    creditorName: 'Jane Doe',
    amount: 100,
    currency: 'EUR',
    instant: false,
  })
})
