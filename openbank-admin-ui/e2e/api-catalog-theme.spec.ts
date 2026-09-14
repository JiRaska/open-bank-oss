// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/api/**', route => {
    if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
    return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"temporarily unavailable"}' })
  })
})

for (const theme of ['light', 'dark'] as const) {
  test(`/docs/api keeps REST and event education accessible in ${theme} mode`, async ({ page }) => {
    await page.goto('/docs/api')
    await waitForSettledShell(page)
    await page.locator('html').evaluate((element, selectedTheme) => {
      element.classList.toggle('dark', selectedTheme === 'dark')
      element.dataset.theme = selectedTheme
    }, theme)

    await expect(page.getByRole('heading', { level: 1, name: /API Catalog|API Katalog/ })).toBeVisible()
    await expect(page.getByLabel(/Search service or endpoint|Hledat službu nebo endpoint/)).toBeVisible()
    await expect(page.getByRole('group', { name: /Filter by domain|Filtrovat podle domény/ })).toBeVisible()
    await page.getByRole('button', { name: /AsyncAPI \/ Kafka/ }).click()
    await expect(page.getByText(/Kafka Event Streams|Kafka event streamy/)).toBeVisible()
    await expect(page.getByRole('link', { name: 'AsyncAPI YAML' })).toBeVisible()
    if (theme === 'dark') await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')

    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  })
}
