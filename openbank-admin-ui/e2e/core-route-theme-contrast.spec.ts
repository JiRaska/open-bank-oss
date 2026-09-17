// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// A cross-domain first-paint sweep complements the loaded-state tests. Service failures are
// deliberate: an operator must still be able to read a console when its evidence is unavailable.
const CORE_ROUTES = [
  '/dashboard', '/accounts', '/transactions', '/ledger', '/day-end',
  '/lending/risk', '/payments', '/fx', '/kyc', '/aml', '/fraud',
  '/sanctions', '/audit', '/finops', '/devops', '/identity-cases',
  '/approvals', '/docs/service-map', '/product-catalog',
] as const

test.describe.configure({ mode: 'parallel' })

for (const theme of ['light', 'dark'] as const) {
  for (const routePath of CORE_ROUTES) {
    test(`${routePath} has readable first-paint fallback in ${theme} theme`, async ({ page, context, baseURL }) => {
      await signInAsOperator(context, baseURL!)
      await context.addCookies([{ name: 'ob-admin-theme', value: theme, url: baseURL! }])
      await page.route('**/api/**', route => {
        const pathname = new URL(route.request().url()).pathname
        return pathname.startsWith('/api/') && !pathname.startsWith('/api/auth/')
          ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
          : route.continue()
      })

      const response = await page.goto(routePath, { waitUntil: 'domcontentloaded' })
      expect(response?.status()).toBeLessThan(400)
      await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
      // App Router can briefly retain a hidden outgoing tree; only the active main is exposed.
      const main = page.locator('#main-content:visible')
      await expect(main).toHaveCount(1)
      await expect.poll(async () => (await main.innerText()).trim().length).toBeGreaterThan(20)
      await page.waitForTimeout(300)

      const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
      expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
        target: node.target.join(' > '),
        message: node.any[0]?.message,
      })))).toEqual([])
    })
  }
}
