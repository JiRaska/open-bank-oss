// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const finding = {
  id: 'finding-deploy-health-42', detector: 'D4_DEPLOY_HEALTH', severity: 'CRITICAL',
  detectedAt: '2026-08-31T20:00:00Z', title: 'Repeated sandbox rollout failure',
  rawMetricValue: 4, threshold: 2, affectedResource: 'openbank-admin-ui',
  doraMetricImpacted: 'CHANGE_FAILURE_RATE', rootCause: 'Readiness probe regressed after rollout',
  remediationKind: 'PULL_REQUEST', proposalPrUrl: 'https://github.com/JiRaska/open-bank-oss/pull/7000',
  proposedRemediation: 'Restore the previous readiness timeout and add rollout coverage',
  status: 'PROPOSED', diagnosedAt: '2026-08-31T20:01:00Z', proposedAt: '2026-08-31T20:02:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('operator reviews exact AI remediation, retains failure, and retries unchanged decision', async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
  await page.route('**/api/devops/dora', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ overall: null, metrics: { deploymentFrequency: { level: null }, leadTime: { level: null }, changeFailureRate: { level: null }, mttr: { level: null } }, recentDeployments: [], sources: { git: true, prometheus: true }, collectedAt: '2026-08-31T20:00:00Z' }) }))
  await page.route('**/api/devops/insights', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ findings: [finding], available: true }) }))

  let attempts = 0
  const payloads: unknown[] = []
  await page.route('**/api/devops/decide', async route => {
    attempts += 1
    payloads.push(route.request().postDataJSON())
    if (attempts === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/devops')
  await page.getByRole('button', { name: 'Approve' }).click()
  const dialog = page.getByRole('alertdialog', { name: 'Review remediation approval' })
  await expect(dialog).toContainText(finding.title)
  await expect(dialog).toContainText(finding.rootCause)
  await expect(dialog).toContainText(finding.proposedRemediation)
  await expect(dialog.getByRole('link', { name: /Open proposal/ })).toHaveAttribute('href', finding.proposalPrUrl)
  expect(attempts).toBe(0)

  await dialog.getByRole('button', { name: 'Confirm approval' }).click()
  await expect(dialog.getByTestId('devops-decision-review-error')).toBeVisible()
  await expect(dialog).toBeVisible()

  await dialog.getByRole('button', { name: 'Confirm approval' }).click()
  await expect(dialog).toBeHidden()
  expect(payloads).toEqual([
    { id: finding.id, action: 'approve' },
    { id: finding.id, action: 'approve' },
  ])
})
