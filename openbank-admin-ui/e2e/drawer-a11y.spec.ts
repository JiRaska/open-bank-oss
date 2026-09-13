// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const ONBOARDING_RECORD = {
  partyId: '00000000-1111-0000-0000-000000000001',
  legalName: 'Ada Banking s.r.o.',
  email: 'ada@example.test',
  partyStatus: 'ACTIVE',
  kycCaseId: null,
  kycStatus: 'APPROVED',
  scaEnrolled: true,
  deviceCount: 1,
  funnelStage: 'ACTIVE',
  blockedReason: null,
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-02T10:00:00Z',
}

const PRODUCT = {
  id: '00000000-2222-0000-0000-000000000002',
  code: 'TERM_DEPOSIT_6M_CZK',
  name: 'Term Deposit 6M',
  type: 'TERM_DEPOSIT',
  currency: 'CZK',
  status: 'ACTIVE',
  isPublic: true,
  version: '1.0.0',
  revision: 4,
  baseRate: 0.058,
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('onboarding drawer is modal, keyboard-dismissable and restores row focus', async ({ page }) => {
  await page.route('**/api/svc/onboarding-service/api/v1/onboarding/funnel', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ACTIVE: 1 }) }))
  await page.route('**/api/svc/onboarding-service/api/v1/onboarding/records?**', route =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ items: [ONBOARDING_RECORD], total: 1, page: 0, size: 20 }),
    }))

  await page.goto('/onboarding')
  const trigger = page.getByRole('row', { name: /Select onboarding party Ada Banking|Vybrat onboarding subjekt Ada Banking/ })
  await trigger.focus()
  await page.keyboard.press('Enter')

  const dialog = page.getByRole('dialog', { name: /Onboarding details for Ada Banking|Detail onboardingu Ada Banking/ })
  await expect(dialog).toBeVisible()
  await expect(dialog).toHaveAttribute('aria-modal', 'true')
  await expect(page.getByRole('button', { name: /Close onboarding details|Zavřít detail onboardingu/ })).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
})

test('product drawer supports keyboard activation and restores product-row focus', async ({ page }) => {
  await page.route('**/api/svc/product-catalog/api/v1/products', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify([PRODUCT]) }))

  await page.goto('/product-catalog')
  const trigger = page.getByRole('row', { name: /Open product details for Term Deposit 6M|Otevřít detail produktu Term Deposit 6M/ })
  await trigger.focus()
  await page.keyboard.press(' ')

  const dialog = page.getByRole('dialog', { name: /Product details for Term Deposit 6M|Detail produktu Term Deposit 6M/ })
  await expect(dialog).toBeVisible()
  await expect(dialog).toHaveAttribute('aria-modal', 'true')
  await expect(dialog).toContainText(PRODUCT.code)

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
})
