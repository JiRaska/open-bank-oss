// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const) {
  test(`selected settings rail is legible in ${theme} mode`, async ({ page }) => {
    await page.goto('/settings')
    if (theme === 'dark') await page.getByRole('button', { name: 'Switch to the dark theme' }).click()

    const selectedTab = page.locator('#settings-tab-profile')
    await expect(selectedTab).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('html')).toHaveCSS('--surface', theme === 'dark' ? '#111827' : '#fff')
    const scan = await new AxeBuilder({ page })
      .include('#settings-tab-profile')
      .withRules(['color-contrast'])
      .analyze()
    expect(scan.violations).toEqual([])
  })

  test(`service documentation action is legible in ${theme} mode`, async ({ page }) => {
    await page.route('**/api/infra/status', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({}),
    }))
    await page.goto('/docs/service-map')
    if (theme === 'dark') await page.getByRole('button', { name: 'Switch to the dark theme' }).click()

    await page.getByRole('button', { name: 'Account', exact: true }).click()
    const action = page.locator('a[href^="/services/"][href$="/docs"]').filter({
      hasText: /Open service documentation|Otevřít dokumentaci služby/,
    })
    await expect(action).toBeVisible()
    await expect(page.locator('html')).toHaveCSS('--surface', theme === 'dark' ? '#111827' : '#fff')
    const scan = await new AxeBuilder({ page })
      .include('a[href^="/services/"][href$="/docs"]')
      .withRules(['color-contrast'])
      .analyze()
    expect(scan.violations).toEqual([])
  })
}
