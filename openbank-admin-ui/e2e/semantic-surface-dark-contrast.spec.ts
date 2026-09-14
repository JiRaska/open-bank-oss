// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'

const SEMANTIC_SURFACES = [
  '/campaigns/referrals',
  '/docs/api',
  '/docs/service-map',
  '/services',
] as const

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/api/**', route => {
    if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'temporarily unavailable' }),
    })
  })
})

for (const route of SEMANTIC_SURFACES) {
  test(`${route} preserves AA colour contrast in dark recovery state`, async ({ page }) => {
    await page.goto(route)
    await waitForSettledShell(page)
    await expect(page.locator('#main-content')).toBeVisible()
    await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
    await expect(page.locator('html')).toHaveCSS('--surface', '#111827')

    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withRules(['color-contrast'])
      .analyze()

    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  })
}
