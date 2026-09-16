// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

for (const theme of ['light', 'dark'] as const) {
  test(`selected settings tab has AA text contrast in ${theme} theme`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await context.addInitScript(selected => localStorage.setItem('ob-admin-theme', selected), theme)
    await page.goto('/settings')
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
    await expect(page.locator('#settings-tab-profile')).toHaveAttribute('aria-selected', 'true')

    const scan = await new AxeBuilder({ page }).include('#settings-tab-profile').withRules(['color-contrast']).analyze()
    expect(scan.violations).toEqual([])
  })

  for (const drift of [false, 'unknown'] as const) {
    test(`service-map evidence detail has AA text contrast in ${theme} theme with ${drift} drift`, async ({ page, context, baseURL }) => {
      await signInAsOperator(context, baseURL!)
      await context.addInitScript(selected => localStorage.setItem('ob-admin-theme', selected), theme)
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
      await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
      await page.getByRole('button', { name: 'Account', exact: true }).click()
      const detail = page.getByTestId('map-service-detail')
      await expect(detail).toContainText('PostgreSQL')

      const scan = await new AxeBuilder({ page }).include('[data-testid="map-service-detail"]').withRules(['color-contrast']).analyze()
      expect(scan.violations).toEqual([])
    })
  }
}
