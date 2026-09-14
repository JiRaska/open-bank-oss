// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

test.describe('/privacy — public semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme keeps the data journey understandable and WCAG A/AA`, async ({ page }) => {
      await page.goto('/privacy')
      await page.locator('html').evaluate((element, selectedTheme) => {
        element.classList.toggle('dark', selectedTheme === 'dark')
        element.dataset.theme = selectedTheme
      }, theme)

      await expect(page.getByRole('heading', { level: 1, name: /operator data|údaji operátora/i })).toBeVisible()
      await expect(page.getByRole('heading', { name: /data journey|cesta údajů/i })).toBeVisible()
      await expect(page.getByText(/no marketing or tracking cookies|marketingové ani sledovací cookies/i)).toBeVisible()
      await expect(page.getByRole('link', { name: /security@open-bank.tech/ })).toHaveAttribute('href', 'mailto:security@open-bank.tech')
      await expect(page.getByRole('link', { name: /security.txt/ })).toHaveAttribute('href', '/.well-known/security.txt')
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
