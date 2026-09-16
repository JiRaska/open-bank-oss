// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'

for (const { route, endpoint, heading, fallback } of [
  { route: '/docs/cluster', endpoint: '/api/cluster/topology', heading: 'Cluster & container — topology and hardening', fallback: 'Topology evidence unavailable' },
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
    await expect(page.getByRole('heading', { name: heading })).toBeVisible()
    await waitForSettledShell(page)
    await expect(page.locator('#main-content')).toHaveCount(1)
    await expect(page.locator('#main-content')).toContainText(fallback)
    if (route === '/docs/cluster') {
      await expect(page.getByRole('status')).toContainText('Counts and security states are hidden')
      await expect(page.getByText('Namespaces', { exact: true })).toHaveCount(0)
    }
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

test('cluster topology retry replaces unavailable evidence with verified data', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let attempts = 0
  await page.route('**/api/cluster/topology', route => {
    attempts += 1
    return attempts === 1
      ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"temporarily unavailable"}' })
      : route.continue()
  })
  await page.goto('/docs/cluster', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('status')).toContainText('Topology evidence unavailable')
  await page.getByRole('button', { name: 'Try again' }).click()
  await expect(page.getByRole('button', { name: /admin-ui/i })).toBeVisible()
  await expect(page.getByRole('status')).toHaveCount(0)
  expect(attempts).toBe(2)
})
