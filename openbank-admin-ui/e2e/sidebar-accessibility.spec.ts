// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const) {
  test(`shared navigation remains accessible in ${theme}`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.goto('/dashboard')
    await expect(page.locator('#admin-sidebar')).toBeVisible()
    const scan = await new AxeBuilder({ page })
      .include('#admin-sidebar')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations).toEqual([])
  })
}
