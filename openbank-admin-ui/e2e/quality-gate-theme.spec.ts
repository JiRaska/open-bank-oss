// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const snapshot = {
  available: true,
  collectedAt: '2026-09-15T00:00:00Z',
  estate: { total: 12, enforced: 10, advisory: 2, withSelftest: 12, withFloor: 11, withBudget: 12, flaky: 1 },
  shardHistory: [{
    runId: 42,
    sha: 'abc1234',
    event: 'pull_request',
    createdAt: '2026-09-15T00:00:00Z',
    shards: [
      { name: 'gates (gitops-api)', conclusion: 'success', seconds: 21 },
      { name: 'gates (lint-supplychain-security)', conclusion: 'failure', seconds: 48 },
    ],
  }],
  gates: [{
    id: 'admin-ui', group: 'ui', mode: 'enforced', status: 'active',
    lastRed: { runId: 42, sha: 'abc1234', createdAt: '2026-09-15T00:00:00Z' },
    flaky: true, selftestDeclared: true, minSubjects: 1, budgetSeconds: 90, runsObserved: 10,
  }],
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/devops/dora', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    overall: null,
    metrics: {
      deploymentFrequency: { level: null, description: null },
      leadTime: { level: null, description: null },
      changeFailureRate: { level: null, description: null },
      mttr: { level: null, description: null },
    },
    recentDeployments: [], sources: { git: false, prometheus: false }, collectedAt: '2026-09-15T00:00:00Z',
  }) }))
  await page.route('**/api/devops/insights', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ findings: [] }) }))
  await page.route('**/api/devops/gate-health', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(snapshot) }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} quality-gate evidence distinguishes failure and flakiness accessibly`, async ({ page }) => {
    await page.addInitScript(selectedTheme => window.localStorage.setItem('ob-admin-theme', selectedTheme), theme)
    await page.goto('/devops')

    const panel = page.getByTestId('quality-gate-health')
    await expect(panel.getByText('CI quality-gate health')).toBeVisible()
    await expect(panel.getByText('lint-supplychain-security · 48s')).toBeVisible()
    await expect(panel.getByText('admin-ui', { exact: true })).toHaveCount(2)
    await expect(panel.getByText('Flaky gates (PASS and FAIL seen on distinct commits)')).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const results = await new AxeBuilder({ page })
      .include('[data-testid="quality-gate-health"]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
