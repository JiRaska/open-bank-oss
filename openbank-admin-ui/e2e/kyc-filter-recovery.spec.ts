// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY_ID = '05a02ef1-381c-40e7-b73f-d6855eead42e'
const OTHER_PARTY_ID = '86d667cb-52d7-4985-8624-e8130dc28cab'
const CASE = {
  id: 'a83f3848-09c0-47fd-b34f-af8cbbfd29c5',
  partyId: PARTY_ID,
  status: 'IN_REVIEW',
  checks: [{ checkType: 'IDENTITY', status: 'PASSED' }],
  createdAt: '2026-08-31T08:00:00Z',
  updatedAt: '2026-08-31T08:30:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('applies a Party UUID explicitly, preserves only the same-filter snapshot, and recovers', async ({ page }) => {
  let partyAvailable = true
  let partyRequests = 0
  await page.route('**/api/svc/kyc-service/api/v1/kyc/cases', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route(`**/api/svc/kyc-service/api/v1/kyc/cases/party/${PARTY_ID}`, route => {
    partyRequests += 1
    if (!partyAvailable) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(CASE) })
  })
  await page.route(`**/api/svc/kyc-service/api/v1/kyc/cases/party/${OTHER_PARTY_ID}`, route =>
    route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' }),
  )

  await page.goto('/kyc')
  const input = page.locator('#kyc-party-id')
  const search = page.getByRole('button', { name: /Vyhledat KYC případy|Search KYC cases/ })

  await input.fill(PARTY_ID)
  expect(partyRequests).toBe(0)
  await search.click()
  await expect(page.getByText('IN_REVIEW', { exact: true })).toBeVisible()
  expect(partyRequests).toBe(1)

  partyAvailable = false
  await search.click()
  await expect(page.getByText(/poslední ověřený snapshot|last verified snapshot/)).toBeVisible()
  await expect(page.getByText('IN_REVIEW', { exact: true })).toBeVisible()

  partyAvailable = true
  await search.click()
  await expect(page.getByText(/poslední ověřený snapshot|last verified snapshot/)).toBeHidden()
  await expect(page.getByText('IN_REVIEW', { exact: true })).toBeVisible()

  await input.fill(OTHER_PARTY_ID)
  await search.click()
  await expect(page.getByText('IN_REVIEW', { exact: true })).toHaveCount(0)
  await expect(page.getByText(/poslední ověřený snapshot|last verified snapshot/)).toHaveCount(0)
  expect(partyRequests).toBe(3)
})
