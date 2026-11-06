// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('generated governance catalog renders pension contracts and funds in their real business domain', async ({ page, context, baseURL }, testInfo) => {
  await signInAsOperator(context, baseURL!)
  // Runtime health is independent of business ownership. Keep the source catalog
  // and governance endpoints real so this exercises generated source through UI.
  await page.route('**/api/services/health', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ source: 'static', discoveryStatus: 'static', services: [] }),
  }))
  const response = await page.request.get('/api/catalog/services')
  expect(response.ok()).toBeTruthy()
  const catalog = await response.json() as { services: { name: string; dataDomain: string | null; runnable: boolean; port: number | null }[] }
  for (const name of ['openbank-pension-service', 'openbank-pension-fund-service']) {
    expect(catalog.services.find(service => service.name === name)?.dataDomain).toBe('pensions')
  }
  expect(catalog.services.find(service => service.name === 'openbank-wealth-service')?.dataDomain).toBe('wealth')

  await page.goto('/docs/service-map')
  const filters = page.getByRole('group', { name: 'Service group filters' })
  await expect(filters.getByRole('button', { name: 'Pensions', exact: true })).toBeVisible()
  await expect(page.locator('svg').getByRole('button', { name: 'Pension Fund', exact: true })).toBeVisible()
  // Service identity is independent of the infrastructure and external layers,
  // which also expose accessible node buttons and can arrive in either order.
  const serviceNodes = page.locator('svg [data-service-name]')
  const expectedServices = catalog.services.filter(service => service.runnable && service.port !== null).map(service => service.name).sort()
  await expect(serviceNodes).toHaveCount(expectedServices.length)
  expect(await serviceNodes.evaluateAll(nodes => nodes.map(node => node.getAttribute('data-service-name')).sort())).toEqual(expectedServices)
  await expect(page.getByText(/The code-derived catalog.*knows/)).toHaveCount(0)
  for (const source of ['health', 'governance', 'topology']) {
    await expect(page.getByTestId(`map-evidence-${source}`).first()).not.toContainText('Loading', { timeout: 15_000 })
  }
  await page.screenshot({ path: testInfo.outputPath('domain-taxonomy-map.png'), fullPage: true })
  await filters.getByRole('button', { name: 'Pensions', exact: true }).click()
  const nodes = page.locator('svg').getByRole('button')
  await expect(nodes.filter({ hasText: 'Pension Fund' })).toHaveCount(1)
  await expect(page.locator('svg').getByRole('button', { name: 'Pension', exact: true })).toBeVisible()
  await expect(page.locator('svg').getByRole('button', { name: 'Wealth', exact: true })).toHaveCount(0)
})
