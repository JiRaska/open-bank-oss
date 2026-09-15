// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Route } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('shows every onboarding stage and purges customer evidence after authorization loss', async ({ page }) => {
  test.setTimeout(60_000)
  let unauthorized = false
  const funnel = {
    REGISTERED: 1, KYC_OPEN: 2, KYC_DOCUMENTS_REQUIRED: 3, KYC_UNDER_REVIEW: 4,
    SCA_PENDING: 5, ACTIVE: 6, BLOCKED: 7,
  }
  const customer = {
    partyId: '11111111-1111-4111-8111-111111111111', legalName: 'Sensitive Customer', email: 'customer@example.test',
    partyStatus: 'PENDING_KYC', kycCaseId: null, kycStatus: 'DOCUMENTS_REQUIRED', scaEnrolled: false,
    deviceCount: 0, funnelStage: 'KYC_DOCUMENTS_REQUIRED', blockedReason: null,
    createdAt: '2026-09-09T10:00:00Z', updatedAt: '2026-09-09T10:01:00Z',
  }
  const fulfill = (route: Route, body: unknown) => route.fulfill({
    status: unauthorized ? 401 : 200,
    contentType: 'application/json',
    body: JSON.stringify(unauthorized ? { error: 'unauthorized' } : body),
  })
  await page.route('**/api/svc/onboarding-service/api/v1/onboarding/funnel', route => fulfill(route, funnel))
  await page.route('**/api/svc/onboarding-service/api/v1/onboarding/records?*', route => {
    const stageFilter = new URL(route.request().url()).searchParams.get('stage')
    return fulfill(route, {
      items: [customer], total: 1, page: 0, size: 20,
      ...(stageFilter && { stageFilter }),
    })
  })

  await page.goto('/onboarding')
  const filters = page.getByRole('group', { name: 'Onboarding stage filters' })
  await expect(filters.getByRole('button')).toHaveCount(7)
  await expect(filters.getByRole('button', { name: /KYC Documents/ })).toContainText('3')
  await filters.getByRole('button', { name: /KYC Documents/ }).click()
  await expect(filters.getByRole('button', { name: /KYC Documents/ })).toHaveAttribute('aria-pressed', 'true')
  const row = page.getByRole('row', { name: /Sensitive Customer/ })
  await row.click()
  await expect(page.getByRole('dialog')).toContainText('customer@example.test')
  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate(selectedTheme => document.documentElement.classList.toggle('dark', selectedTheme === 'dark'), theme)
    await page.waitForTimeout(300)
    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations).toEqual([])
  }

  unauthorized = true
  await page.locator('[aria-label="Refresh onboarding"]').evaluate((button: HTMLButtonElement) => button.click())
  await expect(page.getByText('Sensitive Customer')).toBeHidden()
  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.getByText(/session has expired/).first()).toBeVisible()
})
