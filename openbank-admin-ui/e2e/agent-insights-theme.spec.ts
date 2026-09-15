// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

const dora = {
  overall: 'high',
  metrics: {
    deploymentFrequency: { level: 'high', description: 'Daily', count30d: 30 },
    leadTime: { level: 'high', description: '4 hours' },
    changeFailureRate: { level: 'high', description: 'Low', pct: 2 },
    mttr: { level: 'high', description: '45 minutes', hours: 0.75 },
  },
  recentDeployments: [], sources: { git: true, prometheus: true }, collectedAt: '2026-09-15T00:00:00Z',
}

const finding = {
  id: 'finding-1', detector: 'D3_RUNNER_CAPACITY', severity: 'WARNING', detectedAt: '2026-09-15T00:00:00Z',
  title: 'Idle runner capacity', rawMetricValue: 70, threshold: 50, affectedResource: 'admin-ui',
  doraMetricImpacted: 'LEAD_TIME_FOR_CHANGES', rootCause: 'Reserved capacity exceeds observed demand.',
  remediationKind: 'PULL_REQUEST', proposalPrUrl: 'https://example.test/proposal/1',
  proposedRemediation: 'Right-size the runner pool.', status: 'PROPOSED', diagnosedAt: null, proposedAt: '2026-09-15T00:01:00Z',
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/devops/dora', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(dora) }))
  await page.route('**/api/devops/insights', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ findings: [finding] }) }))
  await page.route('**/api/devops/gate-health', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: false }) }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} shared agent insights preserve semantic HITL states`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.goto('/devops')

    const panel = page.getByTestId('agent-insights-panel')
    await expect(panel.getByText('D3_RUNNER_CAPACITY')).toBeVisible()
    await expect(panel.getByText('PROPOSED')).toBeVisible()
    await expect(panel.getByRole('link', { name: /View proposal|Zobrazit návrh/i })).toBeVisible()
    await panel.getByRole('button', { name: /Approve|Schválit/i }).click()
    const review = page.locator('[data-remediation-review]')
    await expect(review).toBeVisible()
    await expect(review.getByText(/Confirming may trigger operational action|Potvrzení může spustit operační zásah/)).toBeVisible()
    await expect(review.getByRole('button', { name: /Back to review|Zpět ke kontrole/ })).toBeFocused()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)

    const results = await new AxeBuilder({ page })
      .include('[data-remediation-review]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
