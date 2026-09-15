// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { applyOperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('identity deduplication remains understandable and accessible in both themes', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 })
  await page.goto('/docs/identity-dedup')

  await expect(page.getByRole('heading', { level: 1, name: /Identita a deduplikace|Identity & Deduplication/ })).toBeVisible()
  await expect(page.locator('#main-content').getByText(/Tok rozhodnutí při onboardingu|Onboarding resolution flow/).first()).toBeVisible()
  await expect(page.getByText('MATCH_EXISTING', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('NEEDS_MANUAL_VERIFICATION', { exact: true }).first()).toBeVisible()
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  for (const dark of [false, true]) {
    await applyOperatorTheme(page, dark ? 'dark' : 'light')
    if (dark) {
      await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
    } else {
      await expect(page.locator('html')).not.toHaveClass(/\bdark\b/)
    }
    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  }
})
