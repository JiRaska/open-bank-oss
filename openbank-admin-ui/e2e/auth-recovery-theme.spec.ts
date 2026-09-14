// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const boundaries = [
  {
    path: '/auth/error?error=AccessDenied',
    heading: /We could not sign you in|Přihlášení se nepodařilo/,
    action: /Try secure sign-in again|Zkusit bezpečné přihlášení znovu/,
  },
  {
    path: '/auth/forbidden?path=%2Fpayments%2Fapproval',
    heading: /This area is not in your role|Tato oblast není součástí vaší role/,
    action: /Return to your dashboard|Vrátit se na svůj dashboard/,
  },
] as const

test.describe('authentication recovery boundaries — semantic theme', () => {
  for (const boundary of boundaries) {
    for (const theme of ['light', 'dark'] as const) {
      test(`${boundary.path} remains safe and accessible in ${theme}`, async ({ page }) => {
        await page.goto(boundary.path)
        await page.locator('html').evaluate((element, selectedTheme) => {
          element.classList.toggle('dark', selectedTheme === 'dark')
          element.dataset.theme = selectedTheme
        }, theme)

        await expect(page.getByRole('heading', { level: 1, name: boundary.heading })).toBeVisible()
        await expect(page.getByRole('button', { name: boundary.action })).toBeEnabled()
        await expect(page.getByText(/never share a password or token|nikdy neposílejte heslo ani token|normal security control|běžný bezpečnostní mechanismus/i)).toBeVisible()
        if (boundary.path.includes('forbidden')) {
          await expect(page.getByText('/payments/approval')).toBeVisible()
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
  }
})
