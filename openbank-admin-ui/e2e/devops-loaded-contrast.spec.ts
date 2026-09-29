// SPDX-License-Identifier: Apache-2.0
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

for (const theme of ['light', 'dark'] as const) {
  test(`loaded DevOps evidence meets AA contrast in ${theme} theme`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await context.addInitScript(chosen => localStorage.setItem('ob-admin-theme', chosen), theme)
    await page.route('**/api/devops/dora', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        overall: 'elite',
        metrics: {
          deploymentFrequency: { level: 'elite', perDay: 2, description: null },
          leadTime: { level: 'high', hours: 12, description: null },
          changeFailureRate: { level: 'medium', pct: 10, description: null },
          mttr: { level: 'low', hours: 8, description: null },
        },
        recentDeployments: [], sources: { git: true, prometheus: true },
        collectedAt: '2026-09-16T10:00:00Z',
      }),
    }))
    await page.route('**/api/devops/insights', route => route.fulfill({
      contentType: 'application/json', body: JSON.stringify({ findings: [] }),
    }))

    await page.goto('/devops', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('#main-content')).toContainText('Elite')
    await page.waitForTimeout(400)
    const scan = await new AxeBuilder({ page }).include('#main-content').withRules(['color-contrast']).analyze()
    expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
      target: node.target.join(' > '), message: node.any[0]?.message,
    })))).toEqual([])
  })
}
