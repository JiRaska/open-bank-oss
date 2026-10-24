// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('an incomplete discovery result stays unknown on a shared business page', async ({ page }) => {
  await page.route('**/api/svc/interest-service/**', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'discovery_unavailable' }),
  }))

  await page.goto('/interest')
  const main = page.locator('main')
  await expect(main.locator('[title="Deployment status is unknown"]')).toBeVisible()
  await expect(main.getByText('Deployment status is unknown', { exact: true })).toBeVisible()
  await expect(main.getByText(/This result does not establish whether the service is deployed or healthy/)).toBeVisible()
  await expect(main.getByText(/not deployed|idle \(scaled to zero\)|is not responding/i)).toHaveCount(0)
})

test('partial Temporal metric queries retain real zero and render missing values unavailable', async ({ page }) => {
  await page.route('**/api/temporal/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      temporalDeployed: true,
      degraded: true,
      metrics: {
        workflows: { scheduled1h: 5, completed1h: null, failed1h: 0, timedOut1h: null },
        latency: { activityScheduleToStartMs: null, workflowTaskScheduleToStartMs: 20, serverRequestP99Ms: null },
        persistence: { requestsPerSec: null },
        workers: { totalSlotsAvailable: null, slotsUsed: null },
        namespaces: [],
      },
    }),
  }))

  await page.goto('/temporal')
  await page.getByRole('button', { name: 'Metrics' }).click()
  const metrics = page.locator('main')
  await expect(metrics.getByRole('status')).toContainText('Some metric queries failed')
  await expect(metrics.getByLabel('Scheduled: 5')).toBeVisible()
  await expect(metrics.getByLabel('Completed: unavailable')).toBeVisible()
  await expect(metrics.getByLabel('Failed: 0')).toBeVisible()
  await expect(metrics.getByLabel('Timed out: unavailable')).toBeVisible()
  await expect(metrics.getByLabel('Activity (schedule→start): unavailable')).toBeVisible()
  await expect(metrics.getByLabel('Workflow task (schedule→start): 20 ms')).toBeVisible()
  await expect(metrics.getByLabel('Server gRPC (p99): unavailable')).toBeVisible()
})
