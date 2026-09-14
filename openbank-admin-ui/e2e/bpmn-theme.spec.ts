// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} BPMN education keeps process meaning accessible`, async ({ page }) => {
    await page.addInitScript(selectedTheme => window.localStorage.setItem('ob-admin-theme', selectedTheme), theme)
    await page.goto('/docs/bpmn')

    await expect(page.getByRole('heading', { name: /Business Process Diagrams/i })).toBeVisible()
    await expect(page.getByRole('group', { name: /Business process selector|Výběr obchodního procesu/i })).toBeVisible()
    const diagram = page.getByTestId('bpmn-diagram')
    await expect(diagram).toBeVisible()
    const startFill = await diagram.locator('circle').first().evaluate(element => getComputedStyle(element).fill)
    expect(startFill).toBe(theme === 'dark' ? 'rgb(52, 211, 153)' : 'rgb(16, 185, 129)')
    await expect(page.getByText(/Message event \(catch\/throw\)/i)).toBeVisible()

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
