// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('loads the complete party registry and removes it immediately after authorization loss', async ({ page }) => {
  let unauthorized = false
  const party = (id: string, legalName: string) => ({
    id, legalName, email: `${legalName.toLowerCase()}@example.test`, partyType: 'INDIVIDUAL',
    status: 'ACTIVE', kycStatus: 'APPROVED', createdAt: '2026-09-01T10:00:00Z',
  })
  const ada = party('24977cca-20b2-4877-80d1-403b40181a89', 'Ada Lovelace')
  const grace = party('77777777-7777-4777-8777-777777777777', 'Grace Hopper')

  await page.route('**/api/svc/party-service/api/v1/parties?*', route => {
    if (unauthorized) return route.fulfill({ status: 401, contentType: 'application/json', body: '{"error":"unauthorized"}' })
    const pageNumber = new URL(route.request().url()).searchParams.get('page')
    const body = pageNumber === '1'
      ? { items: [grace], total: 26, page: 1, size: 25 }
      : { items: [ada], total: 26, page: 0, size: 25 }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/parties')
  await expect(page.getByText('Loaded 1 of 26')).toBeVisible()
  await page.getByRole('button', { name: 'Load more parties from the list' }).click()
  await expect(page.getByText('Grace Hopper', { exact: true })).toBeVisible()
  await expect(page.getByText('Loaded 2 of 26')).toBeVisible()

  unauthorized = true
  await page.getByRole('button', { name: 'Refresh parties' }).click()
  await expect(page.getByText('Ada Lovelace', { exact: true })).toBeHidden()
  await expect(page.getByText('Grace Hopper', { exact: true })).toBeHidden()
  await expect(page.getByText(/session has expired/)).toBeVisible()
})
