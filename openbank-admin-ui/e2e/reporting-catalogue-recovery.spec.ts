// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const REPORT = {
  id: 'daily-volume',
  titleCs: 'Denní objem',
  titleEn: 'Daily volume',
  descriptionCs: 'Governovaný denní objem.',
  descriptionEn: 'Governed daily volume.',
  permission: 'reporting:view',
  params: [],
  columns: [{ key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' }],
}

test('keeps catalogue failure distinct from an empty catalogue and recovers', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let attempts = 0
  let catalogueAvailable = false
  await page.route('**/api/reporting', route => {
    attempts += 1
    if (!catalogueAvailable) return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    return route.fulfill({ json: { reports: [REPORT] } })
  })

  await page.goto('/reporting')
  await expect(page.getByText('The catalogue could not be loaded, so no governed report can be safely selected or run yet.')).toBeVisible()
  await expect(page.getByText('No data yet: report')).toHaveCount(0)

  catalogueAvailable = true
  await page.getByRole('button', { name: 'Retry catalogue' }).click()
  await expect(page.getByRole('button', { name: /Daily volume/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Run report' })).toBeEnabled()
  expect(attempts).toBeGreaterThanOrEqual(2)
})
