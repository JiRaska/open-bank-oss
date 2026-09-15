// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const REPORT = {
  id: 'risk-settlement-daily',
  titleCs: 'Denní zúčtované transakce',
  titleEn: 'Daily settled transaction volume',
  descriptionCs: 'Denní objem.',
  descriptionEn: 'Daily volume.',
  permission: 'reporting:view',
  params: [],
  columns: [
    { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
    { key: 'settled_count', labelCs: 'Počet', labelEn: 'Count', format: 'number' },
  ],
}

test('loads the chart runtime only after a supported report returns data', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  const chartRuntimeRequests: string[] = []
  page.on('request', request => {
    if (/node_modules_recharts/i.test(request.url())) chartRuntimeRequests.push(request.url())
  })
  await page.route('**/api/reporting', route => route.fulfill({ json: { reports: [REPORT] } }))
  await page.route('**/api/reporting/risk-settlement-daily**', route => route.fulfill({
    json: {
      available: true,
      reportId: REPORT.id,
      columns: REPORT.columns,
      rows: [{ day: '2026-09-14', settled_count: 7 }],
      generatedAt: '2026-09-15T08:00:00Z',
      rowCount: 1,
      truncated: false,
    },
  }))

  await page.goto('/reporting')
  await expect(page.getByRole('button', { name: 'Run report' })).toBeEnabled()
  await page.waitForTimeout(300)
  expect(chartRuntimeRequests).toEqual([])

  await page.getByRole('button', { name: 'Run report' }).click()
  await expect(page.getByRole('region', { name: 'Daily report trend' })).toBeVisible()
  expect(chartRuntimeRequests.length).toBeGreaterThan(0)
  await expect(page.getByRole('cell', { name: '7' })).toBeVisible()
})
