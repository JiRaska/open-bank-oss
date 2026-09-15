// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme, type OperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const satisfies readonly OperatorTheme[]) {
  test(`renders the baked card capability evidence in the ${theme} theme`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.setViewportSize({ width: 320, height: 760 })
    await page.goto('/cards/capabilities')

    const matrix = page.locator('[data-card-capability-matrix]')
    await expect(matrix).toBeVisible()
    await expect(page.getByRole('heading', { name: /Card capability matrix|Matice karetních schopností/ })).toBeVisible()
    await expect(page.getByRole('table')).toBeVisible()
    await expect(page.getByText(/This is not an integration status page|Toto není přehled stavu integrací/)).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

    const results = await new AxeBuilder({ page })
      .include('[data-card-capability-matrix]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
