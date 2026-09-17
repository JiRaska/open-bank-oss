// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

function contrastFailures(scan: Awaited<ReturnType<AxeBuilder['analyze']>>) {
  return scan.violations.flatMap(violation => violation.nodes.map(node => ({
    target: node.target.join(' > '),
    message: node.any[0]?.message,
  })))
}

for (const theme of ['light', 'dark'] as const) {
  test(`selected settings tab has AA text contrast in ${theme} theme`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await page.goto('/settings')
    if (theme === 'dark') await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
    await expect(page.locator('#settings-tab-profile')).toHaveAttribute('aria-selected', 'true')

    const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
    expect(contrastFailures(scan)).toEqual([])
  })

  for (const drift of [false, 'unknown'] as const) {
    test(`service-map evidence detail has AA text contrast in ${theme} theme with ${drift} drift`, async ({ page, context, baseURL }) => {
      await signInAsOperator(context, baseURL!)
      await page.route('**/api/services/health', route => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ services: [{ port: 8100, status: 'UP' }] }),
      }))
      await page.route('**/api/services/governance', route => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ available: true, byService: {
          'account-service': {
            serviceName: 'account-service', primaryDatastore: 'PostgreSQL', databaseName: 'account',
            dataLineageRole: 'source', flywayDeclaredVersion: '1', flywayCurrentVersion: '1',
            flywayDrift: drift, evidenceExported: drift === false,
          },
        } }),
      }))
      await page.route('**/api/catalog/graph', route => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          available: true, nodes: [], edges: [], infraNodes: [], externalNodes: [], infraEdges: [], externalEdges: [],
        }),
      }))

      await page.goto('/docs/service-map')
      if (theme === 'dark') await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
      await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
      await page.getByRole('button', { name: 'Account', exact: true }).click()
      const detail = page.getByTestId('map-service-detail')
      await expect(detail).toContainText('PostgreSQL')
      for (const source of ['health', 'governance', 'topology']) {
        const evidence = page.getByTestId(`map-evidence-${source}`)
        await expect(evidence).toHaveCount(1)
        await expect(evidence).toContainText('Verified')
      }

      const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
      expect(contrastFailures(scan)).toEqual([])
    })
  }
}
