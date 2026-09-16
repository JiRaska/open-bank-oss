// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'

for (const { route, endpoint, heading, fallback } of [
  { route: '/docs/cluster', endpoint: '/api/cluster/topology', heading: 'Cluster & container — topology and hardening', fallback: '—' },
  { route: '/system/tests', endpoint: '/api/test-intelligence', heading: 'Test Intelligence', fallback: 'Report is unavailable.' },
]) {
  test(`${route} preserves the shell when its evidence API returns 503`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await page.route(`**${endpoint}`, request => request.fulfill({
      status: 503,
      contentType: 'application/json',
      body: '{"error":"temporarily unavailable"}',
    }))
    await page.goto(route, { waitUntil: 'domcontentloaded' })
    await waitForSettledShell(page)
    await expect(page.getByRole('heading', { name: heading })).toBeVisible()
    await expect(page.locator('#main-content')).toContainText(fallback)
    await expect(page.getByText('This screen failed to render')).toHaveCount(0)
    for (const dark of [false, true]) {
      await page.locator('html').evaluate((element, enabled) => element.classList.toggle('dark', enabled), dark)
      const scan = await new AxeBuilder({ page }).include('#main-content').withRules(['color-contrast']).analyze()
      expect(scan.violations, scan.violations.flatMap(violation => violation.nodes.map(node =>
        `${node.target.join(' ')}: ${node.failureSummary}`,
      )).join('\n')).toEqual([])
    }
  })
}
