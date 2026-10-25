// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

const serviceHealth = {
  source: 'kubernetes',
  discoveryStatus: 'complete',
  services: [
    { name: 'balance-service', status: 'UP' },
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
})

for (const theme of ['light', 'dark'] as const) {
  test(`keeps recovery evidence accurate, keyboard accessible, and readable in ${theme} theme`, async ({ page }) => {
    const catalogResponse = await page.request.get('/api/catalog/services')
    expect(catalogResponse.ok()).toBeTruthy()
    const catalog = await catalogResponse.json() as {
      available?: boolean
      services?: Array<{ runnable?: boolean; short: string }>
    }
    expect(catalog.available).toBe(true)
    // The BCP roster covers every runnable workload, including runnable components
    // whose catalog kind is not `service` (for example security-scanner).
    const catalogServices = catalog.services?.filter(service => service.runnable === true) ?? []
    const catalogServiceCount = catalogServices.length
    expect(catalogServiceCount).toBeGreaterThan(0)
    const catalogShortNames = new Set(catalogServices.map(service => service.short))
    const observedHealthyCount = serviceHealth.services.filter(service =>
      service.status === 'UP' && catalogShortNames.has(service.name),
    ).length

    await page.goto('/docs/bcp')
    await expect(page.getByRole('heading', { level: 1, name: /Plán kontinuity provozu|Business Continuity Plan/ })).toBeVisible()
    const themeButton = page.getByRole('button', { name: /Switch to the (light|dark) theme|Přepnout na (světlý|tmavý) motiv/ })
    const currentlyDark = await themeButton.getAttribute('aria-pressed') === 'true'
    if ((theme === 'dark') !== currentlyDark) {
      await themeButton.focus()
      await page.keyboard.press('Enter')
    }
    await expect(themeButton).toHaveAttribute('aria-pressed', String(theme === 'dark'))

    const overallStatus = page.locator('.card').filter({ hasText: /Overall status|Celkový stav/ })
    await expect(overallStatus.getByText('UNKNOWN', { exact: true })).toBeVisible()
    await expect(page.getByText(`${observedHealthyCount}/${catalogServiceCount} catalog services healthy`)).toBeVisible()

    const complianceHealth = page.locator('.card').filter({ hasText: /Compliance service health/ })
    await expect(complianceHealth.getByText('HEALTHY', { exact: true })).toBeVisible()
    await expect(complianceHealth.getByText(/health probes only; this is not regulatory-control status/).first()).toBeVisible()
    const paymentHealth = page.locator('.card').filter({ hasText: /Payment-path service health/ })
    await expect(paymentHealth.getByText('UNVERIFIED', { exact: true })).toBeVisible()
    await expect(paymentHealth.getByText(/this page does not authorize transactions/)).toBeVisible()
    // The fixture intentionally observes only six workloads. Missing observations
    // must remain visible as unknown even when catalog coverage is complete.
    await expect(page.getByText('unknown', { exact: true }).first()).toBeVisible()
    for (const unsupportedDecision of ['BLOCKED', 'BLOKOVÁNO', 'CLEAR', 'ALLOWED']) {
      await expect(page.getByText(unsupportedDecision, { exact: true })).toHaveCount(0)
    }

    const firstTier = page.getByRole('button', { name: /T0 — Payment execution/ })
    await expect(firstTier).toHaveAttribute('aria-expanded', 'true')
    await firstTier.focus()
    await page.keyboard.press('Enter')
    await expect(firstTier).toHaveAttribute('aria-expanded', 'false')
    await page.keyboard.press('Space')
    await expect(firstTier).toHaveAttribute('aria-expanded', 'true')

    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  })
}
