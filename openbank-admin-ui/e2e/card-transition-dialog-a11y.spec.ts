// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const CARD_ID = '11111111-1111-1111-1111-111111111111'
const CARD = {
  id: CARD_ID,
  partyId: '22222222-2222-2222-2222-222222222222',
  accountId: '33333333-3333-3333-3333-333333333333',
  productCode: 'DEBIT-CLASSIC',
  cardType: 'DEBIT',
  network: 'VISA',
  maskedPan: '411111******4242',
  cardholderName: 'Verified Cardholder',
  embossedName: 'VERIFIED CARDHOLDER',
  expiryDate: '12/29',
  status: 'ACTIVE',
  dailyLimitMinorUnits: 500000,
  monthlyLimitMinorUnits: 2000000,
  currency: 'CZK',
  createdAt: '2026-08-31T08:00:00Z',
}

test('irreversible card dialog contains and restores focus without escaping while busy', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))

  let currentCard = CARD
  await page.route('**/api/svc/card-issuance-service/api/v1/cards', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify([currentCard]),
  }))

  let markRequestStarted!: () => void
  const requestStarted = new Promise<void>(resolve => { markRequestStarted = resolve })
  let releaseResponse!: () => void
  const responseReleased = new Promise<void>(resolve => { releaseResponse = resolve })
  let submittedReason: string | undefined
  await page.route(`**/api/svc/card-issuance-service/api/v1/cards/${CARD_ID}/block`, async route => {
    submittedReason = (route.request().postDataJSON() as { reason?: string }).reason
    markRequestStarted()
    await responseReleased
    currentCard = { ...CARD, status: 'BLOCKED' }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentCard) })
  })

  await page.goto('/cards')
  const trigger = page.getByRole('button', { name: 'Block', exact: true })
  await expect(trigger).toBeVisible({ timeout: 20_000 })
  await trigger.click()

  const dialog = page.getByRole('dialog', { name: 'Block card' })
  const reason = page.getByRole('textbox', { name: 'Reason for the operation' })
  const back = page.getByRole('button', { name: 'Back' })
  await expect(reason).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(back).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(reason).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()

  await trigger.click()
  await reason.fill('Customer request')
  await page.getByRole('button', { name: 'Confirm block' }).click()
  await requestStarted
  await expect(page.getByRole('button', { name: 'Submitting…' })).toBeDisabled()

  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()

  releaseResponse()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Card results' })).toBeFocused()
  expect(submittedReason).toBe('Customer request')
})

test('four-eyes outcome remains available inside the open modal', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))

  await page.route('**/api/svc/card-issuance-service/api/v1/cards', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify([CARD]),
  }))

  let submittedReason: string | undefined
  await page.route(`**/api/svc/card-issuance-service/api/v1/cards/${CARD_ID}/block`, async route => {
    submittedReason = (route.request().postDataJSON() as { reason?: string }).reason
    await route.fulfill({ status: 202, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/cards')
  await page.getByRole('button', { name: 'Block', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: 'Block card' })
  await page.getByRole('textbox', { name: 'Reason for the operation' }).fill('High-risk request')
  await page.getByRole('button', { name: 'Confirm block' }).click()

  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('status')).toContainText('queued for a second operator’s approval')
  await expect(page.getByText(
    'The operation is queued for a second operator’s approval (four-eyes).',
    { exact: true },
  )).toHaveCount(1)
  expect(submittedReason).toBe('High-risk request')
})
