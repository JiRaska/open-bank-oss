// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

for (const route of [
  '/docs',
  '/docs/sensors',
  '/docs/identity-dedup',
  '/docs/qrlesspay',
  '/services',
  '/system/tests',
  '/day-end',
  '/iaops',
  '/transactions',
  '/customer-360',
]) {
  test(`${route} keeps responsive card grids inside a 320px viewport`, async ({ page, context, baseURL }) => {
    await page.setViewportSize({ width: 320, height: 760 })
    await signInAsOperator(context, baseURL!)
    await page.goto(route)
    await expect(page.locator('h1')).toBeVisible()

    const layout = await page.evaluate(() => ({
      viewport: document.documentElement.clientWidth,
      documentWidth: document.documentElement.scrollWidth,
      overflowingGrids: Array.from(document.querySelectorAll<HTMLElement>('[style*="grid-template-columns"]'))
        .filter(element => {
          const bounds = element.getBoundingClientRect()
          return bounds.width > 0 && (bounds.left < 0 || bounds.right > document.documentElement.clientWidth + 0.5)
        }).length,
    }))

    expect(layout.documentWidth).toBe(layout.viewport)
    expect(layout.overflowingGrids).toBe(0)
  })
}
