// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.startsWith('/api/auth/')) return route.continue()
    if (path === '/api/services/health') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, services: [] }) })
    }
    if (path === '/api/catalog/services') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, services: [] }) })
    }
    if (path === '/api/events') {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, topics: [] }) })
    }
    if (path.startsWith('/api/catalog/openapi/')) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ info: { title: 'OpenBank API', version: '1.0.0' }, paths: {} }) })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} API catalog uses accessible themed method surfaces`, async ({ page }) => {
    await page.addInitScript(selectedTheme => {
      window.localStorage.setItem('ob-admin-theme', selectedTheme)
    }, theme)
    await page.goto('/docs/api')

    await expect(page.getByRole('heading', { name: /API Catalog|API Katalog/i })).toBeVisible()
    await expect(page.getByRole('button', { name: /REST APIs/i })).toHaveAttribute('aria-pressed', 'true')
    await page.getByRole('button', { name: /AsyncAPI/i }).click()
    await expect(page.getByText(/Kafka Event Streams|Kafka event streamy/i)).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
