// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

const serviceHealth = {
  services: [
    { name: 'balance-service', status: 'DOWN' },
    { name: 'aml-service', status: 'UP' },
    { name: 'sanctions-service', status: 'UP' },
    { name: 'kyc-service', status: 'UP' },
    { name: 'security-scanner', status: 'UP' },
    { name: 'notification-service', status: 'UP' },
  ],
}

const infrastructureHealth = {
  postgres: { status: 'UP' },
  kafka: { status: 'UP' },
  temporal: { status: 'UP' },
  keycloak: { status: 'UP' },
  openbao: { status: 'UP' },
  valkey: { status: 'UP' },
  'schema-registry': { status: 'UP' },
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(serviceHealth),
  }))
  await page.route('**/api/infra/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(infrastructureHealth),
  }))
  await page.route('**/api/catalog/services', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: '{"error":"temporarily unavailable"}',
  }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`keeps degraded compliance evidence readable and accessible in ${theme} theme`, async ({ page }) => {
    await page.goto('/docs/bcp')
    if (theme === 'dark') {
      await page.locator('html').evaluate(element => element.classList.add('dark'))
      await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
    }

    await expect(page.getByRole('heading', { level: 1, name: /Plán kontinuity provozu|Business Continuity Plan/ })).toBeVisible()
    await expect(page.getByText(/Compliance gate failed — payment processing BLOCKED|Compliance gate selhala — platební zpracování BLOKOVÁNO/)).toBeVisible()
    await expect(page.getByText(/BLOCKED|BLOKOVÁNO/, { exact: true }).first()).toBeVisible()
    await expect(page.getByText('1/6', { exact: true }).or(page.getByText('5/6', { exact: true }))).toBeVisible()
    await page.waitForTimeout(300)

    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  })
}
