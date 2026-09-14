// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

test.describe('/auth/login — branded semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves trust guidance and WCAG A/AA`, async ({ page }) => {
      await page.goto('/auth/login?error=SessionExpired')
      await page.locator('html').evaluate((element, selectedTheme) => {
        element.classList.toggle('dark', selectedTheme === 'dark')
        element.dataset.theme = selectedTheme
      }, theme)

      await expect(page.getByRole('heading', { level: 1, name: /Explore operations|Prozkoumejte provoz/ })).toBeVisible()
      await expect(page.getByRole('button', { name: /Continue with Keycloak|Pokračovat přes Keycloak/ })).toBeEnabled()
      await expect(page.locator('main [role="alert"]')).toContainText(/session expired|relace vypršela/i)
      await expect(page.getByText(/Protected by zero-trust|Chráněno principy zero trust/)).toBeVisible()
      if (theme === 'dark') {
        await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
      } else {
        await expect(page.locator('html')).not.toHaveClass(/\bdark\b/)
      }

      const scan = await new AxeBuilder({ page })
        .include('main')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
        .analyze()
      expect(scan.violations, scan.violations.map(violation =>
        `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
      ).join('\n')).toEqual([])
    })
  }
})
