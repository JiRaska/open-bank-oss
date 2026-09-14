// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

function snapshot(from: string, to: string) {
  return {
    available: true,
    from,
    to,
    steps: [
      { step: 'WELCOME', stepOrdinal: 1, viewed: 100, completed: 82, holdAbandons: 0, dropOffPct: 18, medianSeconds: 12 },
      { step: 'IDENTITY', stepOrdinal: 2, viewed: 82, completed: 70, holdAbandons: 0, dropOffPct: 14.6, medianSeconds: 45 },
      { step: 'EMAIL', stepOrdinal: 3, viewed: 70, completed: 65, holdAbandons: 0, dropOffPct: 7.1, medianSeconds: 20 },
      { step: 'AGREEMENT', stepOrdinal: 4, viewed: 65, completed: 60, holdAbandons: 0, dropOffPct: 7.7, medianSeconds: 35 },
      { step: 'PASSKEY', stepOrdinal: 5, viewed: 60, completed: 55, holdAbandons: 0, dropOffPct: 8.3, medianSeconds: 50 },
      { step: 'SIGN', stepOrdinal: 6, viewed: 55, completed: 50, holdAbandons: 0, dropOffPct: 9.1, medianSeconds: 65 },
    ],
    signOutcomes: [{ day: to, attempts: 55, successes: 50, failures: 5 }],
    failReasons: [{ reason: 'OTP_EXPIRED', failures: 5 }, { reason: 'USER_CANCELLED', failures: 2 }],
    kycMethods: [{ method: 'BANK_ID', sessions: 70 }, { method: 'VIDEO', sessions: 30 }],
  }
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/onboarding/funnel-analytics?*', route => {
    const url = new URL(route.request().url())
    return route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(snapshot(url.searchParams.get('from')!, url.searchParams.get('to')!)),
    })
  })
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} onboarding funnel is educational, adaptive and accessible`, async ({ page }) => {
    await page.addInitScript(selectedTheme => window.localStorage.setItem('ob-admin-theme', selectedTheme), theme)
    await page.goto('/onboarding/analytics')

    await expect(page.getByRole('heading', { level: 1, name: 'Onboarding Conversion' })).toBeVisible()
    await expect(page.getByText('50.0 %', { exact: true })).toBeVisible()
    await expect(page.getByRole('img', { name: 'Chart of viewed and completed sessions at every funnel step' })).toBeVisible()
    await expect(page.getByRole('img', { name: 'Chart of daily agreement-signature success rate' })).toBeVisible()
    await expect(page.getByText('OTP_EXPIRED')).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const chartSuccess = await page.getByRole('img', { name: 'Chart of daily agreement-signature success rate' })
      .evaluate(element => getComputedStyle(element).getPropertyValue('--success').trim())
    expect(chartSuccess).not.toBe('')
    expect(await page.locator('.onboarding-analytics-kpis').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(4)

    const results = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}

test('mobile funnel reflows metrics and evidence without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/onboarding/analytics')
  await expect(page.getByText('OTP_EXPIRED')).toBeVisible()
  expect(await page.locator('.onboarding-analytics-kpis').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  expect(await page.locator('.onboarding-analytics-dwell').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
})
