// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const columns = [
  { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
  { key: 'settled_count', labelCs: 'Zúčtované transakce', labelEn: 'Settled transactions', format: 'number' },
]

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/reporting', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ reports: [{
      id: 'risk-settlement-daily',
      titleCs: 'Denní objem zúčtovaných transakcí',
      titleEn: 'Daily settled transaction volume',
      descriptionCs: 'Počet zúčtovaných transakcí po dnech.',
      descriptionEn: 'Settled transaction count by day.',
      permission: 'reports:read',
      params: [],
      columns,
    }] }),
  }))
  await page.route('**/api/reporting/risk-settlement-daily*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      reportId: 'risk-settlement-daily',
      columns,
      rows: [
        { day: '2026-09-14', settled_count: 12 },
        { day: '2026-09-15', settled_count: 18 },
      ],
      generatedAt: '2026-09-15T22:00:00Z',
      rowCount: 2,
      truncated: false,
    }),
  }))
})

test('loads the chart engine only after a governed report returns chartable evidence', async ({ page }) => {
  await page.goto('/reporting')

  await expect(page.getByRole('button', { name: /Daily settled transaction volume/ })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Daily report trend' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Run report' }).click()

  const trend = page.getByRole('region', { name: 'Daily report trend' })
  await expect(trend).toBeVisible()
  await expect(trend.getByText('30', { exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '18' })).toBeVisible()
})
