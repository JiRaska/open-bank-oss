// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const merchant = (descriptorKey: string, cleanName: string) => ({
  descriptorKey,
  cleanName,
  logoUrl: null,
  logoContentHash: null,
  category: null,
  lat: null,
  lon: null,
  city: null,
  country: null,
  updatedAt: '2026-09-09T08:00:00Z',
})

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the current merchant page visible when its refresh is malformed', async ({ page }) => {
  let malformed = false
  await page.route('**/api/svc/transaction-service/api/v1/merchants/unmatched**', route => route.fulfill({ json: [] }))
  await page.route('**/api/svc/transaction-service/api/v1/merchants?**', route => {
    const requestedPage = new URL(route.request().url()).searchParams.get('page')
    const row = requestedPage === '1' ? merchant('SECOND', 'Second merchant') : merchant('FIRST', 'First merchant')
    if (malformed && requestedPage === '1') row.updatedAt = 'not-a-date'
    return route.fulfill({ json: { data: [row], total: 51 } })
  })

  await page.goto('/merchants')
  await expect(page.getByText('First merchant')).toBeVisible()
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page.getByText('Second merchant')).toBeVisible()
  await expect(page.getByText('Showing 51–51 of 51')).toBeVisible()

  malformed = true
  await page.getByRole('button', { name: 'Refresh the merchant catalogue' }).click()
  await expect(page.getByText('Second merchant')).toBeVisible()
  await expect(page.getByText(/Načtení selhalo|Failed to load/)).toBeVisible()
})
