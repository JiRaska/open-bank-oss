// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const) {
  test(`shared operator header remains accessible and adaptive (${theme})`, async ({ page }) => {
    await page.addInitScript(selectedTheme => window.localStorage.setItem('openbank-theme', selectedTheme), theme)
    await page.goto('/dashboard')

    const accountMenu = page.getByRole('button', { name: /Open user menu|Otevřít uživatelskou nabídku/i })
    await accountMenu.focus()
    await accountMenu.press('Enter')
    const signOut = page.getByRole('menuitem', { name: /Sign out|Odhlásit se/i })
    await expect(signOut).toBeVisible()
    await expect(signOut).toBeFocused()

    const colors = await signOut.evaluate(element => {
      const style = getComputedStyle(element)
      const root = getComputedStyle(document.documentElement)
      return {
        color: style.color,
        dangerText: root.getPropertyValue('--danger-text').trim(),
        background: style.backgroundColor,
      }
    })
    expect(colors.color).not.toBe('rgb(220, 38, 38)')
    expect(colors.background).not.toBe('rgba(0, 0, 0, 0)')

    const scan = await new AxeBuilder({ page })
      .include('header')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations).toEqual([])
  })
}
