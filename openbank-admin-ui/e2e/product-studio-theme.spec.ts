// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/**', route => {
    if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
    return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'temporarily unavailable' }) })
  })
})

test('Product Studio remains understandable and accessible in both themes', async ({ page }) => {
  await page.goto('/product-studio')
  await expect(page.getByRole('heading', { level: 1, name: /Od nápadu k důvěryhodné nabídce|From product idea to a trusted offer/ })).toBeVisible()
  await expect(page.getByRole('navigation', { name: /Životní cyklus nabídky|Offer lifecycle/ })).toBeVisible()
  await expect(page.getByText(/Inteligence radí, člověk rozhoduje|Intelligence advises; people decide/)).toBeVisible()

  for (const dark of [false, true]) {
    await page.locator('html').evaluate((element, enabled) => element.classList.toggle('dark', enabled), dark)
    if (dark) await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  }
})
