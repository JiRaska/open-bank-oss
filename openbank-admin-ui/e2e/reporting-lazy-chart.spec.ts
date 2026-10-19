// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { clickHouseRiskLargeSettledRows } from '../src/test/fixtures/clickhouse-risk-large-settled'

const columns = [
  { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
  { key: 'settled_count', labelCs: 'Zúčtované transakce', labelEn: 'Settled transactions', format: 'number' },
]
const settledColumns = [
  { key: 'settled_at', labelCs: 'Zúčtováno', labelEn: 'Settled at', format: 'datetime' },
  { key: 'transaction_id', labelCs: 'ID transakce', labelEn: 'Transaction ID', format: 'text' },
  { key: 'amount', labelCs: 'Částka', labelEn: 'Amount', format: 'number' },
  { key: 'currency_code', labelCs: 'Měna', labelEn: 'Currency', format: 'text' },
  { key: 'rail', labelCs: 'Rail', labelEn: 'Rail', format: 'text' },
  { key: 'transaction_type', labelCs: 'Typ', labelEn: 'Type', format: 'text' },
]

const reports = [
  {
    id: 'risk-settlement-daily', titleCs: 'Denní objem zúčtovaných transakcí', titleEn: 'Daily settled transaction volume',
    descriptionCs: 'Počet zúčtovaných transakcí po dnech.', descriptionEn: 'Settled transaction count by day.',
    permission: 'reports:read', params: [], columns,
  },
  {
    id: 'risk-large-settled', titleCs: 'Velké zúčtované transakce', titleEn: 'Large settled transactions',
    descriptionCs: 'Zúčtované transakce nad zvolenou hranicí.', descriptionEn: 'Settled transactions above the selected threshold.',
    permission: 'compliance:view',
    params: [
      { name: 'from', labelCs: 'Od', labelEn: 'From', type: 'date', required: true },
      { name: 'to', labelCs: 'Do', labelEn: 'To', type: 'date', required: true },
      { name: 'minAmount', labelCs: 'Minimální částka', labelEn: 'Minimum amount', type: 'number', required: false, defaultValue: '100000' },
    ],
    columns: settledColumns,
  },
]

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/reporting', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ reports }),
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
  await page.route('**/api/reporting/risk-large-settled*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      reportId: 'risk-large-settled',
      columns: settledColumns,
      rows: clickHouseRiskLargeSettledRows,
      generatedAt: '2026-10-19T12:35:00.000Z',
      rowCount: clickHouseRiskLargeSettledRows.length,
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

test('runs Large settled transactions and renders the RFC3339 DateTime64 value', async ({ page }, testInfo) => {
  await page.goto('/reporting')
  await page.getByRole('button', { name: /Large settled transactions/ }).click()
  await page.getByRole('button', { name: 'Run report' }).click()

  await expect(page.getByRole('cell', { name: '2026-10-19T12:34:56.789Z' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'fixture-tx-001' })).toBeVisible()
  await expect(page.getByText('Invalid report response')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('reporting-large-settled.png'), fullPage: true })
})

test('does not report a malformed successful payload as a ClickHouse outage', async ({ page }) => {
  await page.unroute('**/api/reporting/risk-large-settled*')
  await page.route('**/api/reporting/risk-large-settled*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      reportId: 'risk-large-settled',
      columns: settledColumns,
      rows: [{ ...clickHouseRiskLargeSettledRows[0], settled_at: '2026-10-19 12:34:56.789' }],
      generatedAt: '2026-10-19T12:35:00.000Z',
      rowCount: 1,
      truncated: false,
    }),
  }))
  await page.goto('/reporting')
  await page.getByRole('button', { name: /Large settled transactions/ }).click()
  await page.getByRole('button', { name: 'Run report' }).click()

  await expect(page.getByText('Invalid report response')).toBeVisible()
  await expect(page.getByText(/ClickHouse.*unavailable/i)).toHaveCount(0)
  await expect(page.getByRole('cell', { name: 'fixture-tx-001' })).toHaveCount(0)
})
