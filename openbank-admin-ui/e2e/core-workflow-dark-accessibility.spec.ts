// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

const CORE_WORKFLOWS = [
  { route: '/dashboard', heading: /Můj pracovní prostor|My workspace/ },
  { route: '/accounts', heading: /Účty zákazníků|Customer Accounts/ },
  { route: '/payments', heading: /Platby|Payments/ },
  { route: '/parties', heading: /Subjekty|Parties/ },
  { route: '/approvals', heading: /Fronta schvalování \(AI agent\)|Approval queue \(AI agent\)/ },
  { route: '/audit', heading: /Auditní log|Audit Log/ },
  { route: '/consents', heading: /Souhlasy|Consents/ },
  { route: '/kyc', heading: /KYC Případy|KYC Cases/ },
  { route: '/sanctions', heading: /Prověření sankcí|Sanctions Screening/ },
  { route: '/sdd', heading: /Mandáty inkas|Direct debit mandates/ },
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

for (const { route, heading } of CORE_WORKFLOWS) {
  test(`${route} has no automated WCAG A/AA violations in its controlled dark state`, async ({ page }) => {
    await page.goto(route)
    await expect(page.locator('#main-content')).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
    await page.locator('html').evaluate(element => element.classList.add('dark'))
    await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
    if (route === '/approvals') {
      await expect(page.getByRole('alert').filter({ hasText: /Agent (je nedostupný|unreachable)/ })).toBeVisible()
    }
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
