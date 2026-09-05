// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

const OUTAGE_WORKFLOWS = [
  '/dashboard',
  '/accounts',
  '/payments',
  '/parties',
  '/approvals',
  '/audit',
  '/consents',
  '/kyc',
  '/sanctions',
] as const

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/**', route => {
    if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'temporarily unavailable' }),
    })
  })
})

for (const route of OUTAGE_WORKFLOWS) {
  test(`${route} has no automated WCAG A/AA violations with non-auth APIs forced unavailable`, async ({ page }) => {
    await page.goto(route)
    await expect(page.locator('#main-content')).toBeVisible()
    // Several legacy consoles still use a short entry transition. Axe samples computed colours,
    // so scan the settled UI rather than a deliberately translucent animation frame.
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

test('/sdd has no automated WCAG A/AA violations in its healthy default state', async ({ page }) => {
  await page.route('**/api/svc/sdd-service/api/v1/sdd/mandates/recent**', async route => {
    expect(route.request().url()).toContain('limit=50')
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify([{
        id: '11111111-1111-1111-1111-111111111111',
        umr: 'UMR-EVIDENCE-42',
        creditorName: 'Verified Utilities SE',
        debtorIban: 'CZ6508000000192000145399',
        status: 'ACTIVE',
        scheme: 'CORE',
        createdAt: '2026-08-31T08:00:00Z',
      }]),
    })
  })

  await page.goto('/sdd')

  const mandateTable = page.getByRole('table')
  await expect(page.getByText('UMR-EVIDENCE-42', { exact: true })).toBeVisible()
  await expect(page.getByText('Verified Utilities SE', { exact: true })).toBeVisible()
  await expect(mandateTable).toBeVisible()
  await expect(mandateTable.getByRole('columnheader', { name: 'UMR', exact: true })).toBeVisible()
  await expect(mandateTable.getByRole('columnheader')).toHaveCount(6)

  // Several legacy consoles still use a short entry transition. Axe samples computed colours,
  // so scan the settled UI rather than a deliberately translucent animation frame.
  await page.waitForTimeout(300)
  const scan = await new AxeBuilder({ page })
    .include('#main-content')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(scan.violations, scan.violations.map(violation =>
    `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
  ).join('\n')).toEqual([])
})
