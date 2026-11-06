// SPDX-License-Identifier: Apache-2.0

import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// The same canonical declarations feed each view. Namespace intentionally carries
// no business-domain meaning: all service fixtures share one deployment namespace.
const modules = [
  { name: 'openbank-pension-service', short: 'pension-service', dataDomain: 'pensions', port: 8201 },
  { name: 'openbank-pension-fund-service', short: 'pension-fund-service', dataDomain: 'pensions', port: 8202 },
  { name: 'openbank-wealth-service', short: 'wealth-service', dataDomain: 'wealth', port: 8203 },
  { name: 'openbank-ledger-service', short: 'ledger-service', dataDomain: 'accounting', port: 8205 },
  { name: 'openbank-devops-agent', short: 'devops-agent', dataDomain: 'platform', port: 8204 },
]

test('business domains stay consistent across map, lineage and service catalog', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/catalog/services', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ available: true, services: [
      ...modules.map(module => ({ ...module, kind: 'service', runnable: true })),
      { name: 'openbank-products', short: 'products', kind: 'component', runnable: false, dataDomain: 'products', apiTitle: 'Product definitions' },
    ] }),
  }))
  await page.route('**/api/services/health', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ source: 'kubernetes', discoveryStatus: 'complete', services: modules.map(module => ({
      name: module.short, label: module.short, namespace: 'platform', port: module.port,
      group: module.dataDomain, status: 'UP', reachable: true, latencyMs: 1,
    })) }),
  }))
  await page.route('**/api/catalog/graph', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ available: true, nodes: modules.map(module => ({ name: module.name, dependsOn: 0, dependedOnBy: 0 })), edges: [], infraNodes: [], externalNodes: [], infraEdges: [], externalEdges: [] }),
  }))
  await page.route('**/api/services/governance', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ available: true, byService: {} }),
  }))
  await page.route('**/api/catalog/governance', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ available: true, services: modules.map(module => ({
      serviceName: module.short, dataDomain: module.dataDomain, dataLineageRole: 'both',
    })) }),
  }))

  await page.route('**/api/services/*/docs', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ items: [{ title: 'Overview' }], source: 'bundle' }),
  }))

  await page.goto('/docs/service-map')
  const filters = page.getByRole('group', { name: 'Service group filters' })
  await filters.getByRole('button', { name: 'Pensions', exact: true }).click()
  await expect(page.locator('svg').getByRole('button', { name: 'Pension', exact: true })).toBeVisible()
  await expect(page.locator('svg').getByRole('button', { name: 'Pension Fund', exact: true })).toBeVisible()
  await expect(page.locator('svg').getByRole('button', { name: 'Wealth', exact: true })).toHaveCount(0)
  await filters.getByRole('button', { name: 'Wealth', exact: true }).click()
  await expect(page.locator('svg').getByRole('button', { name: 'Wealth', exact: true }).last()).toBeVisible()
  await expect(page.locator('svg').getByRole('button', { name: 'Pension Fund', exact: true })).toHaveCount(0)

  await page.goto('/docs/lineage')
  await expect(page.getByText('PENSIONS · 2', { exact: true })).toBeVisible()
  await expect(page.getByText('WEALTH · 1', { exact: true })).toBeVisible()
  await expect(page.getByText('TECHNICAL PLATFORM · 1', { exact: true })).toBeVisible()

  await page.goto('/docs/api')
  const apiFilters = page.getByRole('group', { name: 'Filter by domain' })
  await expect(apiFilters.getByRole('button', { name: 'Financial accounting', exact: true })).toBeVisible()
  await apiFilters.getByRole('button', { name: 'Pensions', exact: true }).click()
  await expect(page.getByText('Pension Service', { exact: true })).toBeVisible()
  await expect(page.getByText('Pension Fund Service', { exact: true })).toBeVisible()
  await expect(page.getByText('Wealth Service', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Ledger Service', { exact: true })).toHaveCount(0)

  await page.goto('/services')
  await page.getByRole('combobox').first().selectOption('pensions')
  await expect(page.getByText('Pension', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Pension Fund', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('link', { name: /^Wealth/ })).toHaveCount(0)
  await page.getByRole('combobox').first().selectOption('products')
  await expect(page.getByRole('link', { name: /^Product definitions/ })).toBeVisible()
})

test('generated governance catalog renders pension contracts and funds in their real business domain', async ({ page, context, baseURL }) => {
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
  await expect(page.locator('svg').getByRole('button')).toHaveCount(catalog.services.filter(service => service.runnable && service.port !== null).length)
  await expect(page.getByText(/The code-derived catalog.*knows/)).toHaveCount(0)
  for (const source of ['health', 'governance', 'topology']) {
    await expect(page.getByTestId(`map-evidence-${source}`).first()).not.toContainText('Loading', { timeout: 15_000 })
  }
  await page.screenshot({ path: '/private/tmp/domain-taxonomy-map.png', fullPage: true })
  await filters.getByRole('button', { name: 'Pensions', exact: true }).click()
  const nodes = page.locator('svg').getByRole('button')
  await expect(nodes.filter({ hasText: 'Pension Fund' })).toHaveCount(1)
  await expect(page.locator('svg').getByRole('button', { name: 'Pension', exact: true })).toBeVisible()
  await expect(page.locator('svg').getByRole('button', { name: 'Wealth', exact: true })).toHaveCount(0)
})
