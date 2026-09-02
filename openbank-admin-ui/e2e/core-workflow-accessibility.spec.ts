// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

const CORE_WORKFLOWS = [
  '/dashboard',
  '/accounts',
  '/payments',
  '/parties',
  '/approvals',
  '/audit',
  '/consents',
  '/kyc',
  '/sanctions',
  '/sdd',
] as const

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/**', route => {
    if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'temporarily unavailable' }),
    })
  })
})

for (const route of CORE_WORKFLOWS) {
  test(`${route} has no automated WCAG A/AA violations in its default state`, async ({ page }) => {
    await page.goto(route)
    await expect(page.locator('#main-content')).toBeVisible()
    // Several legacy consoles still use a short entry transition. Axe samples computed colours,
    // so scan the settled UI rather than a deliberately translucent animation frame.
    await page.waitForTimeout(300)
    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  })
}
