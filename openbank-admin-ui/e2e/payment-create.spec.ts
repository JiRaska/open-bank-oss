// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('reviews and creates one SEPA payment with one idempotent BFF request under a double confirmation', async ({ page }) => {
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
  await form.evaluate(element => (element as HTMLFormElement).requestSubmit())

  const review = page.getByRole('alertdialog', { name: 'Review payment order' })
  await expect(review).toBeVisible()
  await expect(review).toContainText('100.00 EUR')
  await expect(review).toContainText('Jane Doe')
  await expect(review).toContainText('DE89370400440532013000')
  expect(posts).toHaveLength(0)

  await page.getByRole('button', { name: 'Confirm and submit' }).evaluate((button: HTMLButtonElement) => {
    button.click()
    button.click()
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

test('reviews the exact domestic payment before the BFF request leaves the browser', async ({ page }) => {
  const posts: { key: string | null; body: Record<string, unknown> }[] = []
  await page.route('**/api/sepa-payments', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))
  await page.route('**/api/domestic-payments', async route => {
    const request = route.request()
    if (request.method() === 'POST') {
      posts.push({ key: request.headers()['idempotency-key'] ?? null, body: request.postDataJSON() as Record<string, unknown> })
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'domestic-e2e-1' }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.goto('/payments')
  await page.getByRole('button', { name: 'New Payment' }).click()
  await page.getByRole('button', { name: /Domestic Standard/ }).click()
  await page.getByLabel('Debtor Account ID').fill('account-42')
  await page.getByLabel('Debtor Account No.').fill('1234567890')
  await page.getByLabel('Debtor bank code').fill('0100')
  await page.getByLabel('Debtor Name').fill('OpenBank Treasury')
  await page.getByLabel('Creditor Account No.').fill('7654321')
  await page.getByLabel('Creditor bank code').fill('0800')
  await page.getByLabel('Creditor Name').fill('Czech Supplier s.r.o.')
  await page.getByLabel('Amount').fill('1250.50')
  await page.getByLabel('Variable Symbol').fill('20260901')
  await page.getByRole('button', { name: 'Create', exact: true }).click()

  const review = page.getByRole('alertdialog', { name: 'Review payment order' })
  await expect(review).toContainText('1,250.50 CZK')
  await expect(review).toContainText('OpenBank Treasury')
  await expect(review).toContainText('7654321/0800')
  await expect(review).toContainText('20260901')
  expect(posts).toHaveLength(0)

  await page.getByRole('button', { name: 'Confirm and submit' }).click()
  await expect(page.getByText('Payment created')).toBeVisible()
  expect(posts).toHaveLength(1)
  expect(posts[0].key).toMatch(/^[0-9a-f-]{8,}$/)
  expect(posts[0].body).toMatchObject({
    debtorAccountId: 'account-42',
    creditorAccountNumber: '7654321',
    creditorBankCode: '0800',
    creditorName: 'Czech Supplier s.r.o.',
    amount: 1250.5,
    currency: 'CZK',
    variableSymbol: '20260901',
  })
})
