// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addInitScript(() => localStorage.setItem('ob-admin-theme', 'dark'))
  await page.route('**/api/**', route => route.request().url().includes('/api/auth/')
    ? route.continue()
    : route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' }))
})

async function expectReadableDarkTheme(page: import('@playwright/test').Page) {
  await expect(page.locator('html')).toHaveClass(/dark/)
  await expect(page.locator('main').first()).toBeVisible()
  await page.waitForTimeout(400)
  const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
  expect(scan.violations.flatMap(violation => violation.nodes.map(node => node.target.join(' > ')))).toEqual([])
}

test('campaign composer remains readable in dark mode', async ({ page }) => {
  await page.goto('/campaigns/new', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('.campaign-audience-card').first()).toBeVisible()
  await expectReadableDarkTheme(page)
})

test('referral principles remain readable in dark mode', async ({ page }) => {
  await page.goto('/campaigns/referrals', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('region', { name: /MGM principles|Zásady MGM/ })).toBeVisible()
  await expectReadableDarkTheme(page)
})

test('published referral cards remain readable in dark mode', async ({ page }) => {
  await page.route('**/api/referral-programs', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{
      id: 'program-1', name: 'Welcome referral', version: 2, rewardAmount: 500,
      currency: 'CZK', qualifyingEvent: 'FIRST_PAYMENT',
      attributionWindowEndsAt: '2026-12-31T00:00:00Z', status: 'PUBLISHED', checker: 'reviewer',
    }] }),
  }))
  await page.goto('/campaigns/referrals', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('[data-referral-program]')).toBeVisible()
  await expectReadableDarkTheme(page)
})
