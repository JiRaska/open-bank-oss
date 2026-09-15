// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('never presents an unavailable risk source as zero exposure', async ({ page }) => {
  await page.route('**/api/svc/lending-service/api/v1/lending/risk/**', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'upstream unavailable' }),
  }))

  await page.goto('/lending/risk')
  await expect(page.getByText('lending-service did not answer for part of the risk evidence')).toBeVisible()
  await expect(page.getByText(/Do not interpret their empty values as zero risk/)).toBeVisible()

  const values = await page.locator('.stat-value').allTextContents()
  expect(values).not.toContain('0')
  expect(values.filter(value => value === '—').length).toBeGreaterThan(0)
})

test('distinguishes a verified empty loan book from an unavailable portfolio', async ({ page }) => {
  await page.route('**/api/svc/lending-service/api/v1/lending/risk/decisions/summary**', route => route.fulfill({ json: [] }))
  await page.route('**/api/svc/lending-service/api/v1/lending/risk/decisions**', route => route.fulfill({ json: [] }))
  await page.route('**/api/svc/lending-service/api/v1/lending/risk/portfolio**', route => route.fulfill({ json: [] }))
  await page.route('**/api/svc/lending-service/api/v1/lending/risk/policy**', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ error: 'policy unavailable' }),
  }))

  await page.goto('/lending/risk')
  await page.getByRole('button', { name: 'Show portfolio' }).click()
  await expect(page.getByText('No data yet: IFRS 9 portfolio')).toBeVisible()
  await expect(page.getByText('The source answered successfully, but the loan book is empty.')).toBeVisible()

  await page.getByRole('button', { name: 'Show policy' }).click()
  await expect(page.getByText('Failed to load: credit policy')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Retry' }).last()).toBeVisible()
})
