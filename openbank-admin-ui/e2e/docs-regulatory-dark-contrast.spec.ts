// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addInitScript(() => localStorage.setItem('ob-admin-theme', 'dark'))
  await page.route('**/api/**', route => route.request().url().includes('/api/auth/')
    ? route.continue()
    : route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' }))
})

for (const route of ['/docs/api', '/docs/cluster', '/docs/sensors/shortcuts', '/regulatory', '/services', '/system/tests']) {
  test(`${route} fallback is readable in dark mode`, async ({ page }) => {
    await page.goto(route, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('html')).toHaveClass(/dark/)
    await expect(page.locator('main').first()).toBeVisible()
    if (route === '/docs/cluster') {
      await expect(page.getByText(/Failed to load: cluster topology|Načtení selhalo: topologie clusteru/)).toBeVisible()
    }
    if (route === '/system/tests') {
      await expect(page.getByText(/Report is unavailable|Report není dostupný/)).toBeVisible()
    }
    await page.waitForTimeout(400)
    const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
    expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
      target: node.target.join(' > '), message: node.any[0]?.message,
    })))).toEqual([])
  })
}

test('cluster dossier explains a missing generated topology without inventing posture', async ({ page }) => {
  await page.route('**/api/cluster/topology', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ schema: 'openbank.cluster-topology/v1', source: 'unavailable',
      generatedAt: null, counts: {}, groups: [], namespaces: [], securityLayers: [],
      imageAnatomy: { steps: [] }, planVsReality: [] }),
  }))
  await page.goto('/docs/cluster', { waitUntil: 'domcontentloaded' })
  await expect(page.getByText(/Failed to load: cluster topology|Načtení selhalo: topologie clusteru/)).toBeVisible()
  await expect(page.locator('#screen-error-title')).toHaveCount(0)
})
