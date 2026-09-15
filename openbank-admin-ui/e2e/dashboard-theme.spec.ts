// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/services/governance', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      timestamp: '2026-09-15T00:00:00Z',
      items: [
        { serviceName: 'account-service', dataDomain: 'core' },
        { serviceName: 'ledger-service', dataDomain: 'core' },
        { serviceName: 'aml-service', dataDomain: 'compliance' },
      ],
    }),
  }))
  await page.route('**/api/services/health', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ services: [
      { name: 'account-service', label: 'Accounts', group: 'core', status: 'UP', latencyMs: 12 },
      { name: 'ledger-service', label: 'Ledger', group: 'core', status: 'DOWN', latencyMs: 28 },
    ] }),
  }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} operations cockpit preserves hierarchy and trustworthy health semantics`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.goto('/dashboard')

    await expect(page.getByRole('heading', { level: 1, name: 'My workspace' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Work queues' })).toBeVisible()
    await expect(page.getByText('1/2', { exact: true })).toBeVisible()
    await expect(page.getByText('AML', { exact: true })).toBeVisible()
    await expect(page.getByText('Not deployed', { exact: true }).first()).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const workspace = page.getByRole('region', { name: 'Work queues' })
    const palette = await workspace.evaluate(element => {
      const style = getComputedStyle(element)
      return { surface: style.getPropertyValue('--surface').trim(), background: style.backgroundImage }
    })
    expect(palette.surface).toBe(theme === 'dark' ? '#111827' : '#fff')
    expect(palette.background).toContain('linear-gradient')

    const results = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}

test('mobile operations cockpit has no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: 'Work queues' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
})
