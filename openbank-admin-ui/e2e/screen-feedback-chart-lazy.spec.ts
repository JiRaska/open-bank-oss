// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('does not load the chart runtime for an unavailable feedback board', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  const chartRuntimeRequests: string[] = []
  page.on('request', request => {
    if (/node_modules_recharts/i.test(request.url())) chartRuntimeRequests.push(request.url())
  })
  await page.route('**/api/feedback/screen-feedback', route => route.fulfill({
    json: { available: false, from: '2026-08-15', to: '2026-09-15', screens: [], recent: [], context: [], error: 'analytics_unavailable' },
  }))

  await page.goto('/feedback')
  await expect(page.getByRole('status')).toContainText('ClickHouse is not responding')
  await page.waitForTimeout(300)
  expect(chartRuntimeRequests).toEqual([])
})

test('loads an accessible themed chart after feedback data arrives', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  const chartRuntimeRequests: string[] = []
  page.on('request', request => {
    if (/node_modules_recharts/i.test(request.url())) chartRuntimeRequests.push(request.url())
  })
  await page.route('**/api/feedback/screen-feedback', route => route.fulfill({
    json: {
      available: true,
      from: '2026-08-15',
      to: '2026-09-15',
      screens: [{ screenId: '/payments', bugs: 3, ideas: 2, confusing: 1, total: 6, withScreenshot: 0, lastSeen: '2026-09-15T08:00:00Z' }],
      recent: [],
      context: [],
    },
  }))

  await page.goto('/feedback')
  await expect(page.getByRole('group', { name: 'Reports by screen and category' })).toBeVisible()
  expect(chartRuntimeRequests.length).toBeGreaterThan(0)
  await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
  await expect(page.getByRole('group', { name: 'Reports by screen and category' })).toBeVisible()
})
