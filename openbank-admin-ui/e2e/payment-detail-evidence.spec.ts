// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PAYMENT_ID = '123e4567-e89b-42d3-a456-426614174000'
const PAYMENT = {
  id: PAYMENT_ID,
  status: 'COMPLETED',
  amount: 125,
  currency: 'EUR',
  createdAt: '2026-09-09T12:00:00Z',
  creditorName: 'Verified creditor',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('authorization loss removes payment evidence and an open raw disclosure', async ({ page }) => {
  let request = 0
  await page.route(`**/api/svc/sepa-payment/api/v1/sepa-payments/${PAYMENT_ID}`, route => {
    request += 1
    if (request === 1) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PAYMENT) })
    }
    return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' })
  })

  await page.goto(`/payments/${PAYMENT_ID}?type=SEPA`)
  await expect(page.getByText('Verified creditor')).toBeVisible()
  await page.getByRole('button', { name: /Show raw payment payload|Zobrazit surová data platby/ }).click()
  await expect(page.getByRole('region', { name: /Raw payment payload|Surová data platby/ })).toContainText('Verified creditor')

  await page.getByRole('button', { name: /Refresh payment|Obnovit platbu/ }).click()
  await expect(page.getByText(/Session expired|Vypršela relace/)).toBeVisible()
  await expect(page.getByText('Verified creditor')).toHaveCount(0)
  await expect(page.getByRole('region', { name: /Raw payment payload|Surová data platby/ })).toHaveCount(0)
})
