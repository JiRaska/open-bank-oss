// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const dora = {
  overall: 'high',
  metrics: {
    deploymentFrequency: { level: 'elite', description: 'Several deployments daily', count30d: 84 },
    leadTime: { level: 'high', description: '4 hours' },
    changeFailureRate: { level: 'medium', description: 'Needs attention', pct: 12 },
    mttr: { level: 'low', description: '2 days', hours: 48 },
  },
  recentDeployments: [{ date: '2026-09-15T00:00:00Z', service: 'admin-ui', sha: 'abc1234' }],
  sources: { git: true, prometheus: true },
  collectedAt: '2026-09-15T00:00:00Z',
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/devops/dora', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(dora) }))
  await page.route('**/api/devops/insights', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ findings: [] }) }))
  await page.route('**/api/devops/gate-health', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: false }) }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} DORA cockpit communicates every performance level accessibly`, async ({ page }) => {
    await page.addInitScript(selectedTheme => window.localStorage.setItem('ob-admin-theme', selectedTheme), theme)
    await page.goto('/devops')

    const overview = page.getByTestId('dora-overview')
    await expect(overview.getByText('High', { exact: true })).toBeVisible()
    for (const label of ['Elite', 'High', 'Medium', 'Low']) await expect(page.getByText(label, { exact: true }).first()).toBeVisible()
    await expect(page.getByText('abc1234')).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const colors = await overview.evaluate(element => {
      const style = getComputedStyle(element)
      return { background: style.backgroundColor, border: style.borderColor }
    })
    expect(colors.background).not.toBe('rgba(0, 0, 0, 0)')
    expect(colors.border).not.toBe(colors.background)

    const results = await new AxeBuilder({ page })
      .include('main')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
