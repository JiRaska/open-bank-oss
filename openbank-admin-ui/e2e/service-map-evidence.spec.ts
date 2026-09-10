// SPDX-License-Identifier: Apache-2.0

import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const graph = {
  available: true,
  nodes: [{ name: 'openbank-account-service', dependsOn: 0, dependedOnBy: 0 }],
  edges: [],
  infraNodes: [{ id: 'infra:postgres', kind: 'infra', tech: 'postgres', label: 'PostgreSQL' }],
  externalNodes: [],
  infraEdges: [{ from: 'openbank-account-service', to: 'infra:postgres', type: 'db' }],
  externalEdges: [],
}

test('Service Map verifies independent evidence then purges it at the authorization boundary', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let unauthorized = false
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ services: [{ port: 8100, status: 'UP' }] }),
  }))
  await page.route('**/api/services/governance', route => {
    return route.fulfill(unauthorized
      ? { status: 401, contentType: 'application/json', body: JSON.stringify({ error: 'unauthorized' }) }
      : { status: 200, contentType: 'application/json', body: JSON.stringify({ available: true, byService: {} }) })
  })
  await page.route('**/api/catalog/graph', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(graph),
  }))

  await page.goto('/docs/service-map')
  await expect(page.getByTestId('map-evidence-topology').first()).toContainText('Verified')
  await expect(page.getByText('PostgreSQL').first()).toBeVisible()
  await page.getByText('PostgreSQL').first().click()
  await expect(page.getByText(/CONNECTED SERVICES/i).first()).toBeVisible()

  unauthorized = true
  await page.getByRole('button', { name: /Refresh Status|Obnovit stav/ }).click()
  await expect(page.getByTestId('map-evidence-health').first()).toContainText('Session expired')
  await expect(page.getByTestId('map-evidence-governance').first()).toContainText('Session expired')
  await expect(page.getByTestId('map-evidence-topology').first()).toContainText('Session expired')
  await expect(page.getByText('PostgreSQL')).toHaveCount(0)
  await expect(page.getByText(/CONNECTED SERVICES/i)).toHaveCount(0)
})
