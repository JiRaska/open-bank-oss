// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const SANCTIONS_LIST = {
  id: 'eu-consolidated',
  listType: 'EU',
  displayName: 'EU Consolidated Financial Sanctions List',
  sourceUrl: 'https://example.test/eu-sanctions',
  enabled: true,
  lastUpdatedAt: '2026-08-31T12:00:00Z',
  lastEntryCount: 1248,
  cronHour: 3,
  cronMinute: 0,
  cronDays: 'MON,TUE,WED,THU,FRI,SAT,SUN',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last sanctions-list configuration visible when status refresh fails', async ({ page }) => {
  let failNextRequest = false
  await page.route('**/api/sanctions/checks', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/sanctions/approvals', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/sanctions/lists', route => {
    if (failNextRequest) {
      failNextRequest = false
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"sanctions service unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([SANCTIONS_LIST]) })
  })

  await page.goto('/sanctions')
  await page.getByRole('button', { name: /Správa listů|List Management/ }).click()
  await expect(page.getByText(SANCTIONS_LIST.displayName)).toBeVisible()

  failNextRequest = true
  await page.getByRole('button', { name: /Obnovit stav sankčních listů|Refresh sanctions-list status/ }).click()

  const stale = page.getByRole('status')
  await expect(stale).toContainText(/poslední dostupná|last available/)
  await expect(page.getByText(SANCTIONS_LIST.displayName)).toBeVisible()

  await page.getByRole('button', { name: /Zkusit znovu načíst sankční listy|Retry loading sanctions lists/ }).click()
  await expect(stale).toBeHidden()
  await expect(page.getByText(SANCTIONS_LIST.displayName)).toBeVisible()
})
