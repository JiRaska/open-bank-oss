// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const finding = {
  id: 'finding-1', detector: 'D1_CI_PIPELINE_HEALTH', severity: 'CRITICAL',
  detectedAt: '2026-09-16T10:00:00Z', title: 'Investigate delayed delivery',
  rawMetricValue: 2, threshold: 1, affectedResource: 'release',
  doraMetricImpacted: null, rootCause: 'The release gate stopped reporting.',
  remediationKind: 'NONE', proposalPrUrl: null, proposedRemediation: null,
  status: 'FUTURE_STATE', diagnosedAt: null, proposedAt: null,
}

for (const theme of ['light', 'dark'] as const) {
  test(`agent findings keep unknown status neutral and readable in ${theme} theme`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await context.addInitScript(chosen => localStorage.setItem('ob-admin-theme', chosen), theme)
    await page.route('**/api/devops/dora', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        overall: null,
        metrics: { deploymentFrequency: { level: null }, leadTime: { level: null },
          changeFailureRate: { level: null }, mttr: { level: null } },
        recentDeployments: [], sources: { git: false, prometheus: false },
        collectedAt: '2026-09-16T10:00:00Z',
      }),
    }))
    await page.route('**/api/devops/insights', route => route.fulfill({
      contentType: 'application/json', body: JSON.stringify({ findings: [finding] }),
    }))

    await page.goto('/devops', { waitUntil: 'domcontentloaded' })
    const workspace = page.getByLabel(/DevOps findings workspace|Pracovní plocha DevOps nálezů/)
    const status = workspace.getByText('FUTURE_STATE')
    await expect(status).toBeVisible()
    await expect(status).toHaveCSS('color', theme === 'dark' ? 'rgb(203, 213, 225)' : 'rgb(71, 85, 105)')
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
    await page.waitForTimeout(400)
    const scan = await new AxeBuilder({ page }).include('[aria-label="DevOps findings workspace"]').withRules(['color-contrast']).analyze()
    expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
      target: node.target.join(' > '), message: node.any[0]?.message,
    })))).toEqual([])
  })
}
