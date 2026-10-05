// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const portal = 'http://127.0.0.1:8098'
const tags = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

for (const route of ['/', '/api/']) {
  test(`developer portal ${route} has no automated WCAG A/AA violations`, async ({ page }) => {
    const response = await page.goto(`${portal}${route}`)
    expect(response?.ok()).toBe(true)
    if (route === '/api/') {
      // Audit the rendered API reference, not just the tiny HTML shell before Redoc loads.
      await expect(page.locator('redoc .redoc-wrap')).toBeVisible()
    }
    const scan = await new AxeBuilder({ page }).withTags(tags).analyze()
    expect(scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.length} node(s); ` +
      violation.nodes.slice(0, 3).map(node => node.target.join(' ')).join(', '),
    )).toEqual([])
  })
}

test('developer portal axe guard detects a seeded missing image description', async ({ page }) => {
  await page.goto(portal)
  await page.evaluate(() => {
    const image = document.createElement('img')
    image.src = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='
    document.body.append(image)
  })
  const scan = await new AxeBuilder({ page }).withRules(['image-alt']).analyze()
  expect(scan.violations.map(violation => violation.id)).toContain('image-alt')
})
